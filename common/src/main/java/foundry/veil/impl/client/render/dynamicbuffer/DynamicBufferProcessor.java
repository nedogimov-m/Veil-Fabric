package foundry.veil.impl.client.render.dynamicbuffer;

import com.mojang.renderpearl.api.vertex.VertexFormat;
import foundry.veil.impl.client.render.shader.processor.NativeGlslNodeWriter;
import foundry.veil.impl.client.render.shader.processor.NativeGlslTreeWriter;
import foundry.veil.api.client.render.dynamicbuffer.DynamicBufferType;
import foundry.veil.api.client.render.shader.processor.ShaderPreProcessor;
import io.github.ocelot.glslprocessor.api.GlslInjectionPoint;
import io.github.ocelot.glslprocessor.api.GlslParser;
import io.github.ocelot.glslprocessor.api.GlslSyntaxException;
import io.github.ocelot.glslprocessor.api.grammar.GlslSpecifiedType;
import io.github.ocelot.glslprocessor.api.grammar.GlslTypeSpecifier;
import io.github.ocelot.glslprocessor.api.grammar.GlslVersionStatement;
import io.github.ocelot.glslprocessor.api.node.GlslNode;
import io.github.ocelot.glslprocessor.api.node.GlslNodeList;
import io.github.ocelot.glslprocessor.api.node.GlslTree;
import io.github.ocelot.glslprocessor.api.node.constant.GlslConstantNode;
import io.github.ocelot.glslprocessor.api.node.expression.GlslAssignmentNode;
import io.github.ocelot.glslprocessor.api.node.expression.GlslOperationNode;
import io.github.ocelot.glslprocessor.api.node.function.GlslFunctionNode;
import io.github.ocelot.glslprocessor.api.node.function.GlslInvokeFunctionNode;
import io.github.ocelot.glslprocessor.api.node.variable.GlslNewFieldNode;
import io.github.ocelot.glslprocessor.api.node.variable.GlslVariableNode;
import io.github.ocelot.glslprocessor.api.visitor.GlslNodeStringWriter;
import io.github.ocelot.glslprocessor.lib.anarres.cpp.LexerException;
import org.jetbrains.annotations.ApiStatus;

import java.io.IOException;
import java.util.*;

@ApiStatus.Internal
public class DynamicBufferProcessor implements ShaderPreProcessor {

    private static final String[] VECTOR_ELEMENTS = {".x", ".y", ".z", ".w"};
    private static final Set<String> BLOCK_SHADERS = Set.of("rendertype_solid", "rendertype_cutout", "rendertype_cutout_mipped", "rendertype_translucent");
    private static final org.slf4j.Logger LOGGER = org.slf4j.LoggerFactory.getLogger("Veil");
    private int producedBuffers;

    /** Fragment outputs actually emitted by this processor, after the vertex/fragment pair is processed. */
    public int producedBuffers() { return this.producedBuffers; }

    public DynamicBufferProcessor() {
    }

    private static String lightmapCoordinate(GlslNode uv, Context context) {
        String coordinate = "vec2(" + NativeGlslNodeWriter.expression(uv) + ") / 256.0";
        if (context instanceof MinecraftContext) coordinate += " + vec2(0.5 / 16.0)";
        return "clamp(" + coordinate + ", vec2(0.5 / 16.0), vec2(15.5 / 16.0))";
    }
    private static boolean samplesAlbedo(GlslNode node) {
        return node.stream().anyMatch(child -> child instanceof GlslInvokeFunctionNode call
                && call.getHeader() instanceof GlslVariableNode function
                && Set.of("texture", "sampleNearest", "sampleRGSS").contains(function.getName())
                && !call.getParameters().isEmpty() && call.getParameters().getFirst() instanceof GlslVariableNode sampler
                && sampler.getName().equals("Sampler0"));
    }
    /** Keep the complete native filtering choice (including RGSS conditionals), excluding already-lit vertex tint. */
    private static Optional<GlslNode> albedoSample(GlslNode statement) {
        return statement.stream().filter(DynamicBufferProcessor::samplesAlbedo)
                .filter(node -> node.stream().noneMatch(child -> child instanceof GlslVariableNode variable
                        && Set.of("vertexColor", "ColorModulator").contains(variable.getName())))
                .filter(node -> !(node instanceof GlslNewFieldNode) && !(node instanceof GlslAssignmentNode))
                .findFirst();
    }

    /** Native shaderc requires explicit matching stage locations, including generated marker varyings. */
    @SuppressWarnings("unchecked")
    private static String varying(Context context, DynamicBufferType type, String direction, String valueType, String name, GlslTree tree) {
        Map<DynamicBufferType, Integer> locations = (Map<DynamicBufferType, Integer>) context.customProgramData()
                .computeIfAbsent("veil:dynamic_locations", key -> new EnumMap<>(DynamicBufferType.class));
        Integer location = locations.get(type);
        if (location == null) {
            Set<Integer> occupied = new HashSet<>(locations.values());
            var matcher = java.util.regex.Pattern.compile("layout\\s*\\([^)]*\\blocation\\s*=\\s*(\\d+)").matcher(NativeGlslTreeWriter.source(tree));
            while (matcher.find()) occupied.add(Integer.parseInt(matcher.group(1)));
            int next = 16;
            while (occupied.contains(next)) next++;
            if (next >= 32) throw new IllegalArgumentException("No free native varying location for " + type);
            location = next; locations.put(type, location);
        }
        return "layout(location = " + location + ") " + direction + " " + valueType + " " + name;
    }

    @Override
    public void modify(Context ctx, GlslTree tree) throws IOException, GlslSyntaxException, LexerException {
        if (!ctx.isSourceFile() || ctx.activeBuffers() == 0) return;
        DynamicBufferType[] types = DynamicBufferType.decode(ctx.activeBuffers());
        int outputBase = ctx instanceof MinecraftContext minecraft ? minecraft.pipeline().getColorTargetStates().size() : 1;

        Map<String, GlslNode> markers = tree.getMarkers();
        GlslFunctionNode mainFunction = tree.mainFunction().orElseThrow();
        GlslNodeList mainFunctionBody = Objects.requireNonNull(mainFunction.getBody());
        GlslNodeList treeBody = tree.getBody();

        GlslVersionStatement version = tree.getVersionStatement();
        if (version.getVersion() < 330) {
            version.setVersion(330);
        }
        version.setCore(true);

        // Check if there is any lightmap to pull out
        GlslNode sampler = null;
        GlslNode lightmapUV = null;
        boolean blockLightmap = false;
        boolean injectLightmap = !markers.containsKey("veil:" + DynamicBufferType.LIGHT_COLOR.getName()) || !markers.containsKey("veil:" + DynamicBufferType.LIGHT_UV.getName());
        Map<String, Object> data = ctx.customProgramData();

        GlslNodeStringWriter writer = new GlslNodeStringWriter(true);

        // must be a vanilla shader, so attempt to extract data from attributes
        boolean modified = false;
        if (ctx instanceof ShaderPreProcessor.MinecraftContext minecraftContext) {
            VertexFormat vertexFormat = minecraftContext.vertexFormat();
            if (ctx.isVertex() && injectLightmap) {
                Optional<GlslNode> sampleLightmapOptional = mainFunction.stream().filter(node -> {
                    if (!(node instanceof GlslInvokeFunctionNode invokeFunctionNode) || invokeFunctionNode.getParameters().size() != 2) {
                        return false;
                    }
                    return invokeFunctionNode.getHeader() instanceof GlslVariableNode variableNode && Set.of("minecraft_sample_lightmap", "sample_lightmap").contains(variableNode.getName());
                }).findFirst();

                if (sampleLightmapOptional.isPresent()) {
                    List<GlslNode> parameters = ((GlslInvokeFunctionNode) sampleLightmapOptional.get()).getParameters();
                    sampler = parameters.get(0);
                    lightmapUV = parameters.get(1);
                    blockLightmap = true;
                } else if (vertexFormat != null && vertexFormat.contains("UV2")) {
                    Optional<GlslNode> texelFetchOptional = mainFunction.stream().filter(node -> {
                        if (!(node instanceof GlslInvokeFunctionNode invokeFunctionNode) || invokeFunctionNode.getParameters().size() != 3) {
                            return false;
                        }
                        List<GlslNode> parameters = invokeFunctionNode.getParameters();
                        return invokeFunctionNode.getHeader() instanceof GlslVariableNode functionName &&
                                "texelFetch".equals(functionName.getName()) &&
                                parameters.get(1) instanceof GlslOperationNode operation &&
                                operation.getFirst() instanceof GlslVariableNode variableNode &&
                                operation.getSecond() instanceof GlslConstantNode constantNode &&
                                constantNode.intValue() == 16 &&
                                operation.getOperand() == GlslOperationNode.Operand.DIVIDE &&
                                "UV2".equals(variableNode.getName());
                    }).findFirst();

                    if (texelFetchOptional.isPresent()) {
                        List<GlslNode> parameters = ((GlslInvokeFunctionNode) texelFetchOptional.get()).getParameters();
                        sampler = parameters.get(0);
                        lightmapUV = ((GlslOperationNode) parameters.get(1)).getFirst();
                    }
                }
            }

            for (int i = 0; i < types.length; i++) {
                DynamicBufferType type = types[i];
                String output = type.getOutputDeclaration(outputBase + i);

                String shaderName = Objects.requireNonNull(ctx.name()).getPath().replaceFirst("^core/", "");
                if (Set.of("rendertype_lines", "lines").contains(shaderName)) {
                    if (type == DynamicBufferType.NORMAL && ctx.isFragment()) {
                        treeBody.add(GlslInjectionPoint.BEFORE_MAIN, GlslParser.parseExpression(output));
                        mainFunctionBody.add(new GlslAssignmentNode(new GlslVariableNode(type.getSourceName()), GlslParser.parseExpression("vec4(0.0, 0.0, 0.0, 1.0)"), GlslAssignmentNode.Operand.EQUAL));
                    }
                    if (type != DynamicBufferType.ALBEDO) {
                        continue;
                    }
                }

                boolean inVertex = data.containsKey("mask") && ((Integer) data.get("mask") & type.getMask()) != 0;
                if (injectLightmap) {
                    if (type == DynamicBufferType.LIGHT_UV) {
                        if (markers.containsKey("veil:" + DynamicBufferType.LIGHT_UV.getName())) {
                            continue;
                        }

                        if (ctx.isVertex()) {
                            if (lightmapUV != null) {
                                treeBody.add(GlslInjectionPoint.BEFORE_MAIN, GlslParser.parseExpression(varying(ctx, type, "out", "vec2", "Pass" + type.getSourceName(), tree)));
                                if (blockLightmap) {
                                    mainFunctionBody.add(GlslParser.parseExpression("vec2 veilTexCoord2 = " + lightmapCoordinate(lightmapUV, ctx)));
                                    mainFunctionBody.add(new GlslAssignmentNode(new GlslVariableNode("Pass" + type.getSourceName()), new GlslVariableNode("veilTexCoord2"), GlslAssignmentNode.Operand.EQUAL));
                                } else {
                                    mainFunctionBody.add(new GlslAssignmentNode(new GlslVariableNode("Pass" + type.getSourceName()), GlslParser.parseExpression("vec2(" + NativeGlslNodeWriter.expression(lightmapUV) + " / 256.0)"), GlslAssignmentNode.Operand.EQUAL));
                                }
                                modified = true;
                                data.compute("mask", (s, o) -> (o instanceof Integer val ? val : 0) | type.getMask());
                            }
                        } else if (inVertex) {
                            treeBody.add(GlslInjectionPoint.BEFORE_MAIN, GlslParser.parseExpression(varying(ctx, type, "in", "vec2", "Pass" + type.getSourceName(), tree)));
                            treeBody.add(GlslInjectionPoint.BEFORE_MAIN, GlslParser.parseExpression(output));
                            mainFunctionBody.add(new GlslAssignmentNode(new GlslVariableNode(type.getSourceName()), GlslParser.parseExpression("vec4(Pass" + type.getSourceName() + ", 0.0, 1.0)"), GlslAssignmentNode.Operand.EQUAL));
                            modified = true;
                        }
                    } else if (type == DynamicBufferType.LIGHT_COLOR) {
                        if (markers.containsKey("veil:" + DynamicBufferType.LIGHT_COLOR.getName())) {
                            continue;
                        }

                        if (ctx.isVertex()) {
                            if (lightmapUV != null && sampler != null) {
                                treeBody.add(GlslInjectionPoint.BEFORE_MAIN, GlslParser.parseExpression(varying(ctx, type, "out", "vec3", "Pass" + type.getSourceName(), tree)));
                                if (blockLightmap) {
                                    mainFunctionBody.add(new GlslAssignmentNode(new GlslVariableNode("Pass" + type.getSourceName()), GlslParser.parseExpression("texture(" + NativeGlslNodeWriter.expression(sampler) + ", " + lightmapCoordinate(lightmapUV, ctx) + ").rgb"), GlslAssignmentNode.Operand.EQUAL));
                                } else {
                                    mainFunctionBody.add(new GlslAssignmentNode(new GlslVariableNode("Pass" + type.getSourceName()), GlslParser.parseExpression("texelFetch(" + NativeGlslNodeWriter.expression(sampler) + ", " + NativeGlslNodeWriter.expression(lightmapUV) + " / 16, 0).rgb"), GlslAssignmentNode.Operand.EQUAL));
                                }
                                modified = true;
                                data.compute("mask", (s, o) -> (o instanceof Integer val ? val : 0) | type.getMask());
                            }
                        } else if (inVertex) {
                            treeBody.add(GlslInjectionPoint.BEFORE_MAIN, GlslParser.parseExpression(varying(ctx, type, "in", "vec3", "Pass" + type.getSourceName(), tree)));
                            treeBody.add(GlslInjectionPoint.BEFORE_MAIN, GlslParser.parseExpression(output));
                            mainFunctionBody.add(new GlslAssignmentNode(new GlslVariableNode(type.getSourceName()), GlslParser.parseExpression("vec4(Pass" + type.getSourceName() + ", 1.0)"), GlslAssignmentNode.Operand.EQUAL));
                            modified = true;
                        }
                    }
                }

                // Inject Normal passthrough into vertex and fragment shaders
                if (type == DynamicBufferType.NORMAL && !markers.containsKey("veil:" + DynamicBufferType.NORMAL.getName())) {
                    // Inject a normal output into the particle, lead, and text fragment shaders
                    if (ctx.isFragment() && Set.of("particle", "rendertype_leash", "leash", "rendertype_text", "text").contains(shaderName)) {
                        treeBody.add(GlslInjectionPoint.BEFORE_MAIN, GlslParser.parseExpression(output));
                        mainFunctionBody.add(new GlslAssignmentNode(new GlslVariableNode(type.getSourceName()), GlslParser.parseExpression("vec4(0.0, 0.0, 1.0, 1.0)"), GlslAssignmentNode.Operand.EQUAL));
                        modified = true;
                    }

                    if (vertexFormat != null && vertexFormat.contains("Normal")) {
                        if (ctx.isVertex()) {
                            Optional<GlslNewFieldNode> fieldOptional = tree.field("Normal");
                            if (fieldOptional.isPresent()) {
                                treeBody.add(GlslInjectionPoint.BEFORE_MAIN, GlslParser.parseExpression(varying(ctx, type, "out", "vec3", "Pass" + type.getSourceName(), tree)));
                                mainFunctionBody.add(new GlslAssignmentNode(new GlslVariableNode("Pass" + type.getSourceName()), GlslParser.parseExpression("transpose(inverse(mat3(ModelViewMat))) * " + fieldOptional.get().getName()), GlslAssignmentNode.Operand.EQUAL));
                                modified = true;
                                data.compute("mask", (s, o) -> (o instanceof Integer val ? val : 0) | type.getMask());
                            }
                        } else if (ctx.isFragment()) {
                            if (inVertex) {
                                treeBody.add(GlslInjectionPoint.BEFORE_MAIN, GlslParser.parseExpression(varying(ctx, type, "in", "vec3", "Pass" + type.getSourceName(), tree)));
                                treeBody.add(GlslInjectionPoint.BEFORE_MAIN, GlslParser.parseExpression(output));
                                mainFunctionBody.add(new GlslAssignmentNode(new GlslVariableNode(type.getSourceName()), GlslParser.parseExpression("vec4(Pass" + type.getSourceName() + ", 1.0)"), GlslAssignmentNode.Operand.EQUAL));
                                modified = true;
                            }
                        }
                    } else if (vertexFormat != null && vertexFormat.contains("Position")) {
                        // Native terrain/block formats no longer carry normals. Preserve real surface
                        // orientation by differentiating a view-space position varying in the fragment.
                        if (ctx.isVertex()) {
                            String position = mainFunction.stream().filter(node -> node instanceof GlslNewFieldNode field && "pos".equals(field.getName()))
                                    .findAny().isPresent() ? "pos" : "Position";
                            treeBody.add(GlslInjectionPoint.BEFORE_MAIN, GlslParser.parseExpression(varying(ctx, type, "out", "vec3", "Pass" + type.getSourceName(), tree)));
                            mainFunctionBody.add(GlslParser.parseExpression("Pass" + type.getSourceName() + " = (ModelViewMat * vec4(" + position + ", 1.0)).xyz"));
                            data.compute("mask", (s, o) -> (o instanceof Integer val ? val : 0) | type.getMask());
                            data.put("veil:dynamic_derivative_normal", true); modified = true;
                        } else if (inVertex && Boolean.TRUE.equals(data.get("veil:dynamic_derivative_normal"))) {
                            treeBody.add(GlslInjectionPoint.BEFORE_MAIN, GlslParser.parseExpression(varying(ctx, type, "in", "vec3", "Pass" + type.getSourceName(), tree)));
                            treeBody.add(GlslInjectionPoint.BEFORE_MAIN, GlslParser.parseExpression(output));
                            mainFunctionBody.add(GlslParser.parseExpression(type.getSourceName() + " = vec4(normalize(cross(dFdx(Pass" + type.getSourceName()
                                    + "), dFdy(Pass" + type.getSourceName() + "))), 1.0)"));
                            modified = true;
                        }
                    }
                }

                // Inject Color passthrough if necessary
                if (type == DynamicBufferType.ALBEDO && !markers.containsKey("veil:" + DynamicBufferType.ALBEDO.getName())) {
                    if (ctx.isVertex()) {
                        if (BLOCK_SHADERS.contains(shaderName)) {
                            // TODO remove face shading
                        }

                        Optional<GlslNode> mixLightOptional = mainFunction.stream().filter(node -> {
                            if (!(node instanceof GlslInvokeFunctionNode invokeFunctionNode) || invokeFunctionNode.getParameters().size() != 4) {
                                return false;
                            }
                            return invokeFunctionNode.getHeader() instanceof GlslVariableNode variableNode && "minecraft_mix_light".equals(variableNode.getName());
                        }).findFirst();
                        if (mixLightOptional.isPresent()) {
                            GlslNode color = ((GlslInvokeFunctionNode) mixLightOptional.get()).getParameters().get(3);
                            treeBody.add(GlslInjectionPoint.BEFORE_MAIN, GlslParser.parseExpression(varying(ctx, type, "out", "vec4", "Pass" + type.getSourceName(), tree)));
                            mainFunctionBody.add(new GlslAssignmentNode(new GlslVariableNode("Pass" + type.getSourceName()), color, GlslAssignmentNode.Operand.EQUAL));
                            modified = true;
                            data.compute("mask", (s, o) -> (o instanceof Integer val ? val : 0) | type.getMask());
                        } else if (vertexFormat != null && vertexFormat.contains("Color")) {
                            Optional<GlslNewFieldNode> fieldOptional = tree.field("Color");
                            if (fieldOptional.isPresent()) {
                                treeBody.add(GlslInjectionPoint.BEFORE_MAIN, GlslParser.parseExpression(varying(ctx, type, "out", "vec4", "Pass" + type.getSourceName(), tree)));
                                mainFunctionBody.add(new GlslAssignmentNode(new GlslVariableNode("Pass" + type.getSourceName()), new GlslVariableNode(fieldOptional.get().getName()), GlslAssignmentNode.Operand.EQUAL));
                                modified = true;
                                data.compute("mask", (s, o) -> (o instanceof Integer val ? val : 0) | type.getMask());
                            }
                        }
                    } else if (inVertex) {
                        treeBody.add(GlslInjectionPoint.BEFORE_MAIN, GlslParser.parseExpression(varying(ctx, type, "in", "vec4", "Pass" + type.getSourceName(), tree)));
                        treeBody.addFirst(GlslParser.parseExpression(output));

                        boolean hasColorModulator = tree.searchField("ColorModulator").findAny().isPresent()
                                || tree.getBody().stream().filter(io.github.ocelot.glslprocessor.api.node.variable.GlslStructDeclarationNode.class::isInstance)
                                .map(io.github.ocelot.glslprocessor.api.node.variable.GlslStructDeclarationNode.class::cast)
                                .anyMatch(block -> block.getStructSpecifier().getFields().stream().anyMatch(field -> field.getName().equals("ColorModulator")));
                        boolean inserted = false;
                        for (int j = 0; j < mainFunctionBody.size(); j++) {
                            GlslNode body = mainFunctionBody.get(j);
                            Optional<GlslNode> textureOptional = albedoSample(body);

                            if (textureOptional.isPresent()) {
                                if (hasColorModulator) {
                                    mainFunctionBody.add(new GlslAssignmentNode(new GlslVariableNode(type.getSourceName()), GlslParser.parseExpression(NativeGlslNodeWriter.expression(textureOptional.get()) + " * ColorModulator * Pass" + type.getSourceName()), GlslAssignmentNode.Operand.EQUAL));
                                } else {
                                    mainFunctionBody.add(new GlslAssignmentNode(new GlslVariableNode(type.getSourceName()), GlslParser.parseExpression(NativeGlslNodeWriter.expression(textureOptional.get()) + " * Pass" + type.getSourceName()), GlslAssignmentNode.Operand.EQUAL));
                                }
                                inserted = true;
                                break;
                            }
                        }
                        if (!inserted) {
                            if (hasColorModulator) {
                                mainFunctionBody.add(new GlslAssignmentNode(new GlslVariableNode(type.getSourceName()), GlslParser.parseExpression("Pass" + type.getSourceName() + " * ColorModulator"), GlslAssignmentNode.Operand.EQUAL));
                            } else {
                                mainFunctionBody.add(new GlslAssignmentNode(new GlslVariableNode(type.getSourceName()), new GlslVariableNode("Pass" + type.getSourceName()), GlslAssignmentNode.Operand.EQUAL));
                            }
                        }
                        modified = true;
                    }
                }
            }
        }

        for (int i = 0; i < types.length; i++) {
            DynamicBufferType bufferType = types[i];
            String typeName = bufferType.getName();
            GlslTypeSpecifier.BuiltinType outType = bufferType.getType();
            GlslNode node = markers.get("veil:" + typeName);

            boolean vertexPassthrough = data.containsKey("passmask") && ((Integer) data.get("passmask") & bufferType.getMask()) != 0;
            if (node == null) {
                if (vertexPassthrough) {
                    String sourceName = bufferType.getSourceName();
                    writer.clear();
                    writer.visitTypeSpecifier(outType);
                    treeBody.add(GlslInjectionPoint.BEFORE_MAIN, GlslParser.parseExpression(varying(ctx, bufferType, "in", writer.toString(), "Pass" + sourceName, tree)));
                    treeBody.add(GlslInjectionPoint.BEFORE_MAIN, GlslParser.parseExpression("layout(location = " + (outputBase + i) + ") out " + writer + " " + sourceName));
                    mainFunctionBody.add(new GlslAssignmentNode(new GlslVariableNode(sourceName), new GlslVariableNode("Pass" + sourceName), GlslAssignmentNode.Operand.EQUAL));
                }
                continue;
            }

            if (vertexPassthrough) {
                throw new IOException("Node marked '#veil:" + typeName + "' in both vertex and fragment shader");
            }

            GlslSpecifiedType specifiedType = null;
            String copyName = null;
            List<GlslNode> body = null;
            int index = 0;
            if (node instanceof GlslNewFieldNode newNode) {
                Optional<GlslTree.GlslBlock> block = tree.containingBlock(newNode);
                if (block.isPresent()) {
                    copyName = newNode.getName();
                    specifiedType = newNode.getType();
                    GlslTree.GlslBlock pair = block.get();
                    body = pair.body();
                    index = pair.index() + 1;
//                            pair.getFirst().add(pair.getSecond() + 1, GlslParser.parseExpression(copyName + " = " + sourceName));
                }
            } else if (node instanceof GlslAssignmentNode assignmentNode && assignmentNode.getFirst() instanceof GlslVariableNode variableNode) {
                Optional<GlslTree.GlslBlock> block = tree.containingBlock(assignmentNode);
                if (block.isPresent()) {
                    copyName = variableNode.getName();

                    List<GlslNewFieldNode> fields = tree.searchField(copyName).toList();
                    if (fields.size() == 1) {
                        specifiedType = fields.getFirst().getType();
                        GlslTree.GlslBlock pair = block.get();
                        body = pair.body();
                        index = pair.index() + 1;
                    }
                }
            }

            if (copyName == null || specifiedType == null || !(specifiedType.getSpecifier() instanceof GlslTypeSpecifier.BuiltinType nodeType) || (!nodeType.isPrimitive() && !nodeType.isVector()) || (!nodeType.isFloat() && !nodeType.isInteger() && !nodeType.isUnsignedInteger())) {
                LOGGER.warn("Invalid node marked '#veil:{}' in {} shader: {}", typeName, ctx.typeName(), ctx.name());
                continue;
            }

            modified = true;
            String sourceName;
            writer.clear();
            writer.visitTypeSpecifier(outType);
            if (ctx.isVertex()) {
                sourceName = "Pass" + bufferType.getSourceName();
                treeBody.add(GlslInjectionPoint.BEFORE_MAIN, GlslParser.parseExpression(varying(ctx, bufferType, "out", writer.toString(), sourceName, tree)));
                data.compute("passmask", (s, o) -> (o instanceof Integer val ? val : 0) | bufferType.getMask());
            } else {
                sourceName = bufferType.getSourceName();
                treeBody.add(GlslInjectionPoint.BEFORE_MAIN, GlslParser.parseExpression("layout(location = " + (outputBase + i) + ") out " + writer + " " + sourceName));
            }

            String cast = switch (outType) {
                case FLOAT, VEC2, VEC3, VEC4 -> !nodeType.isFloat() ? "float" : null;
                case INT, IVEC2, IVEC3, IVEC4 -> !nodeType.isInteger() ? "int" : null;
                case UINT, UVEC2, UVEC3, UVEC4 -> !nodeType.isUnsignedInteger() ? "uint" : null;
                default -> null;
            };

            GlslNode expression;
            if (nodeType == outType) {
                expression = new GlslVariableNode(copyName);
            } else if (nodeType.getComponents() < outType.getComponents()) {
                // Not enough components, so pad
                StringBuilder builder = new StringBuilder(writer.toString()).append("(");
                String padding = outType.getConstant(0);
                if (nodeType.getComponents() == 1) {
                    builder.append(cast != null ? cast + "(" + copyName + "), " : (copyName + ", "));
                } else {
                    for (int j = 0; j < nodeType.getComponents(); j++) {
                        builder.append(cast != null ? cast + "(" + copyName + VECTOR_ELEMENTS[j] + "), " : (copyName + VECTOR_ELEMENTS[j] + ", "));
                    }
                }
                for (int j = nodeType.getComponents(); j < 3; j++) {
                    builder.append(padding).append(", ");
                }
                builder.append(outType.getConstant(1));
                builder.append(')');
                expression = GlslParser.parseExpression(builder.toString());
            } else {
                expression = GlslParser.parseExpression((cast != null ? writer.toString() : "") + '(' + copyName + ')');
            }

            body.add(index, new GlslAssignmentNode(new GlslVariableNode(sourceName), expression, GlslAssignmentNode.Operand.EQUAL));
        }

        if (modified && ctx.isFragment()) {
            tree.markOutputs();
        }
        if (ctx.isFragment()) {
            this.producedBuffers = 0;
            for (DynamicBufferType type : types) {
                if (tree.field(type.getSourceName()).isPresent()) this.producedBuffers |= type.getMask();
            }
        }
    }
}
