package foundry.veil.api.client.render.rendertype;

import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.pipeline.*;
import com.mojang.renderpearl.api.vertex.VertexFormat;
import foundry.veil.api.client.render.rendertype.layer.ColorLogicLayer;
import foundry.veil.api.client.render.rendertype.layer.CullLayer;
import foundry.veil.api.client.render.rendertype.layer.LayeringLayer;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.rendertype.LayeringTransform;
import net.minecraft.client.renderer.rendertype.RenderSetup;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.rendertype.TextureTransform;
import net.minecraft.resources.Identifier;
import org.jetbrains.annotations.Nullable;
import foundry.veil.mixin.rendertype.accessor.RenderSetupAccessor;
import foundry.veil.mixin.rendertype.accessor.TextureBindingAccessor;

import java.util.*;

/** Native immutable-pipeline construction. Layer codecs no longer execute global OpenGL callbacks. */
public final class VeilRenderTypeBuilder {
    public record Texture(Identifier location, boolean blur, boolean mipmap) {}
    public record State(@Nullable Identifier veilShader, @Nullable Identifier vanillaShader,
                        List<Texture> textures, Optional<BlendFunction> blend, CompareOp depthTest,
                        CullLayer.CullFace cull, boolean lightmap, boolean overlay,
                        LayeringLayer.LayeringState layering, @Nullable Identifier output,
                        TextureTransform texturing, boolean writeColor, boolean writeDepth,
                        OptionalDouble lineWidth, @Nullable ColorLogicLayer.Operation colorLogic,
                        int patchVertices, boolean depthClamp, boolean multisample, boolean seamlessCubemap,
                        boolean standardUniforms, @Nullable RenderSetup sourceSetup, Set<String> overrides) {
        public State { textures = List.copyOf(textures); overrides = Set.copyOf(overrides); }
    }
    private Identifier veilShader, vanillaShader;
    private List<Texture> textures = List.of();
    private Optional<BlendFunction> blend = Optional.empty();
    private CompareOp depthTest = DepthStencilState.DEFAULT.depthTest();
    private CullLayer.CullFace cull = CullLayer.CullFace.BACK;
    private boolean lightmap, overlay, writeColor = true, writeDepth = true;
    private LayeringLayer.LayeringState layering = LayeringLayer.LayeringState.NONE;
    private Identifier output;
    private TextureTransform texturing = TextureTransform.DEFAULT_TEXTURING;
    private OptionalDouble lineWidth = OptionalDouble.empty();
    private ColorLogicLayer.Operation logic;
    private int patchVertices;
    private boolean depthClamp, multisample, seamlessCubemap, standardUniforms = true;
    private RenderSetup sourceSetup;
    private final Set<String> overrides = new HashSet<>();

    public VeilRenderTypeBuilder() {}
    public static VeilRenderTypeBuilder from(State state) {
        final var builder = new VeilRenderTypeBuilder();
        builder.veilShader = state.veilShader(); builder.vanillaShader = state.vanillaShader();
        builder.textures = state.textures(); builder.blend = state.blend(); builder.depthTest = state.depthTest();
        builder.cull = state.cull(); builder.lightmap = state.lightmap(); builder.overlay = state.overlay();
        builder.layering = state.layering(); builder.output = state.output(); builder.texturing = state.texturing();
        builder.writeColor = state.writeColor(); builder.writeDepth = state.writeDepth(); builder.lineWidth = state.lineWidth();
        builder.logic = state.colorLogic(); builder.patchVertices = state.patchVertices(); builder.depthClamp = state.depthClamp();
        builder.multisample = state.multisample(); builder.seamlessCubemap = state.seamlessCubemap();
        builder.standardUniforms = state.standardUniforms(); builder.sourceSetup = state.sourceSetup();
        builder.overrides.addAll(state.overrides());
        return builder;
    }
    public static VeilRenderTypeBuilder from(RenderSetup setup) {
        final var builder = new VeilRenderTypeBuilder();
        final var access = (RenderSetupAccessor) (Object) setup;
        final var pipeline = access.veil$getPipeline();
        builder.sourceSetup = setup;
        builder.vanillaShader = pipeline.getShaders().get(ShaderType.VERTEX);
        builder.cull = pipeline.isCull() ? CullLayer.CullFace.BACK : CullLayer.CullFace.NONE;
        final var depth = pipeline.getDepthStencilState();
        builder.depthTest = depth == null ? CompareOp.ALWAYS_PASS : depth.depthTest();
        builder.writeDepth = depth != null && depth.writeDepth();
        if (!pipeline.getColorTargetStates().isEmpty() && pipeline.getColorTargetStates().getFirst() != null) {
            builder.blend = pipeline.getColorTargetStates().getFirst().blendFunction();
            builder.writeColor = pipeline.getColorTargetStates().getFirst().writeMask() != ColorTargetState.WRITE_NONE;
        }
        builder.lightmap = access.veil$useLightmap(); builder.overlay = access.veil$useOverlay();
        builder.texturing = access.veil$getTextureTransform();
        builder.layering = access.veil$getLayeringTransform() == LayeringTransform.VIEW_OFFSET_Z_LAYERING
                ? LayeringLayer.LayeringState.VIEW_OFFSET : LayeringLayer.LayeringState.NONE;
        return builder;
    }

    public VeilRenderTypeBuilder textureState(List<Texture> textures) { this.overrides.add("textures"); this.textures = List.copyOf(textures); return this; }
    public VeilRenderTypeBuilder shaderState(Identifier shader, boolean veil) { this.overrides.add("shader");
        this.veilShader = veil ? Objects.requireNonNull(shader) : null;
        this.vanillaShader = veil ? null : Objects.requireNonNull(shader);
        return this;
    }
    public VeilRenderTypeBuilder transparencyState(Optional<BlendFunction> blend) { this.overrides.add("blend"); this.blend = Objects.requireNonNull(blend); return this; }
    public VeilRenderTypeBuilder depthTestState(CompareOp comparison) { this.overrides.add("depth"); this.depthTest = Objects.requireNonNull(comparison); return this; }
    public VeilRenderTypeBuilder cullState(CullLayer.CullFace cull) { this.overrides.add("cull"); this.cull = Objects.requireNonNull(cull); return this; }
    public VeilRenderTypeBuilder lightmapState(boolean enabled) { this.overrides.add("lightmap"); this.lightmap = enabled; return this; }
    public VeilRenderTypeBuilder overlayState(boolean enabled) { this.overrides.add("overlay"); this.overlay = enabled; return this; }
    public VeilRenderTypeBuilder layeringState(LayeringLayer.LayeringState layering) { this.overrides.add("layering"); this.layering = Objects.requireNonNull(layering); return this; }
    public VeilRenderTypeBuilder outputState(@Nullable Identifier output) { this.overrides.add("output"); this.output = output; return this; }
    public VeilRenderTypeBuilder texturingState(TextureTransform texturing) { this.overrides.add("texturing"); this.texturing = Objects.requireNonNull(texturing); return this; }
    public VeilRenderTypeBuilder writeMaskState(boolean color, boolean depth) { this.overrides.add("writeMask"); this.writeColor = color; this.writeDepth = depth; return this; }
    public VeilRenderTypeBuilder lineState(OptionalDouble width) { this.overrides.add("lineWidth"); this.lineWidth = Objects.requireNonNull(width); return this; }
    public VeilRenderTypeBuilder colorLogicState(ColorLogicLayer.Operation operation) { this.overrides.add("logic"); this.logic = operation; return this; }
    public VeilRenderTypeBuilder patchState(int vertices) { this.overrides.add("patch"); this.patchVertices = vertices; return this; }
    public VeilRenderTypeBuilder depthClampState(boolean enabled) { this.overrides.add("depthClamp"); this.depthClamp = enabled; return this; }
    public VeilRenderTypeBuilder multisampleState(boolean enabled) { this.overrides.add("multisample"); this.multisample = enabled; return this; }
    public VeilRenderTypeBuilder seamlessCubemapState(boolean enabled) { this.overrides.add("seamless"); this.seamlessCubemap = enabled; return this; }
    public VeilRenderTypeBuilder standardUniforms(boolean enabled) { this.overrides.add("uniforms"); this.standardUniforms = enabled; return this; }
    public State create() {
        if (this.sourceSetup == null && this.veilShader == null && this.vanillaShader == null) throw new IllegalStateException("A native render type needs a shader");
        if (this.patchVertices < 0) throw new IllegalArgumentException("Patch vertex count cannot be negative");
        if (this.lineWidth.isPresent() && (!Double.isFinite(this.lineWidth.getAsDouble()) || this.lineWidth.getAsDouble() <= 0))
            throw new IllegalArgumentException("Line width must be finite and positive");
        return new State(this.veilShader, this.vanillaShader, this.textures, this.blend, this.depthTest, this.cull,
                this.lightmap, this.overlay, this.layering, this.output, this.texturing, this.writeColor, this.writeDepth,
                this.lineWidth, this.logic, this.patchVertices, this.depthClamp, this.multisample, this.seamlessCubemap, this.standardUniforms, this.sourceSetup, this.overrides);
    }
    public static RenderPipeline pipeline(Identifier name, State state, VertexFormat format, PrimitiveTopology topology,
                                          List<GpuFormat> colors, @Nullable GpuFormat depth) {
        return pipeline(name, state, Map.of(0, format), topology, colors, depth);
    }
    public static RenderPipeline pipeline(Identifier name, State state, Map<Integer, VertexFormat> formats, PrimitiveTopology topology,
                                          List<GpuFormat> colors, @Nullable GpuFormat depth) {
        validateNativeState(state);
        final var builder = RenderPipeline.builder().withLocation(name).withPrimitiveTopology(topology)
                .withCull(state.cull() == CullLayer.CullFace.BACK);
        formats.forEach(builder::withVertexBinding);
        final Set<String> bindings = new HashSet<>();
        final var source = state.sourceSetup() == null ? null : ((RenderSetupAccessor) (Object) state.sourceSetup()).veil$getPipeline();
        if (source != null && !state.overrides().contains("shader")) {
            bindings.addAll(copyShader(builder, source));
            for (int index = 1; index < source.getVertexFormatBindings().size(); index++) builder.withVertexBinding(index, source.getVertexFormatBinding(index));
            builder.withPushConstantSize(source.pushConstantSize());
        } else if (state.vanillaShader() != null) bindings.addAll(copyVanillaShader(builder, state.vanillaShader()));
        else {
            builder.withVertexShader(state.veilShader()).withFragmentShader(state.veilShader());
            if (state.standardUniforms()) builder.withBindGroupLayout(standardUniformLayout());
        }
        final var samplers = BindGroupLayout.builder();
        int samplerCount = 0;
        for (int index = 0; index < state.textures().size(); index++) {
            if (bindings.add("Sampler" + index)) { samplers.withUniform("Sampler" + index, UniformType.COMBINED_IMAGE_SAMPLER); samplerCount++; }
        }
        if (state.lightmap() && bindings.add("Sampler2")) { samplers.withUniform("Sampler2", UniformType.COMBINED_IMAGE_SAMPLER); samplerCount++; }
        if (state.overlay() && bindings.add("Sampler1")) { samplers.withUniform("Sampler1", UniformType.COMBINED_IMAGE_SAMPLER); samplerCount++; }
        if (samplerCount > 0) builder.withBindGroupLayout(samplers.build());
        final boolean noDraw = state.cull() == CullLayer.CullFace.FRONT_AND_BACK;
        final int mask = !state.writeColor() || noDraw || state.colorLogic() == ColorLogicLayer.Operation.NOOP
                ? ColorTargetState.WRITE_NONE : ColorTargetState.WRITE_ALL;
        final Optional<BlendFunction> blend = state.colorLogic() == ColorLogicLayer.Operation.CLEAR
                ? Optional.of(new BlendFunction(BlendFactor.ZERO, BlendFactor.ZERO)) : state.blend();
        for (int index = 0; index < colors.size(); index++) {
            final var color = colors.get(index);
            if (color == null) { builder.withUnusedColorTargetState(index); continue; }
            final var original = source != null && source.getColorTargetStates().size() > index ? source.getColorTargetStates().get(index) : null;
            builder.withColorTargetState(index, new ColorTargetState(
                    original != null && !state.overrides().contains("blend") && state.colorLogic() == null ? original.blendFunction() : blend,
                    color, original != null && !state.overrides().contains("writeMask") && !noDraw && state.colorLogic() == null ? original.writeMask() : mask));
        }
        final var originalDepth = source == null ? null : source.getDepthStencilState();
        final float biasScale = state.layering() == LayeringLayer.LayeringState.POLYGON_OFFSET ? 1 : 0;
        final float biasConstant = state.layering() == LayeringLayer.LayeringState.POLYGON_OFFSET ? 10 : 0;
        builder.withDepthStencilState(depth == null ? Optional.empty() : Optional.of(new DepthStencilState(
                state.depthTest(), state.writeDepth() && !noDraw,
                originalDepth != null && !state.overrides().contains("layering") ? originalDepth.depthBiasScaleFactor() : biasScale,
                originalDepth != null && !state.overrides().contains("layering") ? originalDepth.depthBiasConstant() : biasConstant)));
        return builder.build();
    }
    private static BindGroupLayout standardUniformLayout() {
        return BindGroupLayout.builder().withUniform("DynamicTransforms", UniformType.UNIFORM_BUFFER)
                .withUniform("Projection", UniformType.UNIFORM_BUFFER).withUniform("Fog", UniformType.UNIFORM_BUFFER)
                .withUniform("Globals", UniformType.UNIFORM_BUFFER).withUniform("Lighting", UniformType.UNIFORM_BUFFER).build();
    }
    private static void validateNativeState(State state) {
        if (state.cull() == CullLayer.CullFace.FRONT) throw new UnsupportedOperationException("Front-face culling needs an explicitly reversed native geometry stream");
        if (state.patchVertices() > 0) throw new UnsupportedOperationException("RenderPearl26.3 exposes no tessellation patch topology");
        if (state.depthClamp()) throw new UnsupportedOperationException("Depth clamp needs a migrated vertex shader that clamps clip-space depth");
        if (state.multisample()) throw new UnsupportedOperationException("RenderPearl26.3 does not expose multisampled render attachments");
        if (state.lineWidth().isPresent() && state.lineWidth().getAsDouble() != 1)
            throw new UnsupportedOperationException("Native wide lines require LineWidth vertex attributes and expanded line geometry");
        if (state.colorLogic() != null && state.colorLogic() != ColorLogicLayer.Operation.COPY
                && state.colorLogic() != ColorLogicLayer.Operation.CLEAR && state.colorLogic() != ColorLogicLayer.Operation.NOOP)
            throw new UnsupportedOperationException("This bitwise framebuffer operation requires an explicit native shader pass: " + state.colorLogic());
    }
    private static Set<String> copyVanillaShader(RenderPipeline.Builder builder, Identifier shader) {
        String name = shader.getPath();
        if (name.startsWith("core/")) name = name.substring(5);
        if (name.startsWith("rendertype_")) name = name.substring(11);
        try {
            final var field = RenderPipelines.class.getField(name.toUpperCase(Locale.ROOT));
            if (field.getType() == RenderPipeline.class) {
                final var pipeline = (RenderPipeline) field.get(null);
                return copyShader(builder, pipeline);
            }
        } catch (ReflectiveOperationException ignored) { }
        final var id = Identifier.fromNamespaceAndPath(shader.getNamespace(), name);
        builder.withVertexShader(id).withFragmentShader(id).withBindGroupLayout(standardUniformLayout());
        return Set.of();
    }
    private static Set<String> copyShader(RenderPipeline.Builder builder, RenderPipeline pipeline) {
        pipeline.getShaders().forEach((type, id) -> {
            if (type == ShaderType.VERTEX) builder.withVertexShader(id);
            else if (type == ShaderType.FRAGMENT) builder.withFragmentShader(id);
        });
        pipeline.getBindGroupLayouts().forEach(builder::withBindGroupLayout);
        builder.withSnippet(new RenderPipeline.Snippet(Map.of(), Optional.of(pipeline.getShaderDefines()), Optional.empty(),
                new ColorTargetState[0], 0, Optional.empty(), Optional.empty(), Optional.empty(), new VertexFormat[0], Optional.empty(), 0));
        return BindGroupLayout.flattenUniforms(pipeline.getBindGroupLayouts()).stream().map(uniform -> uniform.name()).collect(java.util.stream.Collectors.toSet());
    }
    public static RenderSetup setup(RenderPipeline pipeline, State state, boolean affectsCrumbling, boolean sort, boolean outline) {
        final var builder = RenderSetup.builder(pipeline).setTextureTransform(state.texturing());
        final var source = state.sourceSetup() == null ? null : (RenderSetupAccessor) (Object) state.sourceSetup();
        if (source != null && !state.overrides().contains("textures")) source.veil$getTextures().forEach((name, binding) -> {
            final var texture = (TextureBindingAccessor) binding;
            builder.withTexture(name, texture.veil$getLocation(), texture.veil$getSampler());
        });
        for (int index = 0; index < state.textures().size(); index++) {
            final var texture = state.textures().get(index);
            builder.withTexture("Sampler" + index, texture.location(), () ->
                    com.mojang.blaze3d.systems.RenderSystem.getSamplerCache().getRepeat(
                            texture.blur() ? com.mojang.renderpearl.api.textures.FilterMode.LINEAR : com.mojang.renderpearl.api.textures.FilterMode.NEAREST,
                            texture.mipmap()));
        }
        if (state.lightmap()) builder.useLightmap();
        if (state.overlay()) builder.useOverlay();
        if (affectsCrumbling) builder.affectsCrumbling();
        if (sort) builder.sortOnUpload();
        if (source != null) {
            if (state.overrides().contains("textures")) builder.setOutline(source.veil$getOutlineProperty());
            else builder.setOutline(source.veil$getOutlineProperty(), source.veil$getOutlineTextureName());
            if (source.veil$forceSolidModelPhase()) builder.withForcedSolidModelPhase();
            if (!state.overrides().contains("layering")) builder.setLayeringTransform(source.veil$getLayeringTransform());
            if (state.overrides().stream().noneMatch(Set.of("shader", "blend", "depth", "cull", "writeMask", "layering", "output")::contains)
                    && source.veil$getOitPipelineSet() != null) builder.setOitPipelines(source.veil$getOitPipelineSet());
        } else if (outline) builder.setOutline(RenderSetup.OutlineProperty.AFFECTS_OUTLINE);
        if ((source == null || state.overrides().contains("layering")) && state.layering() == LayeringLayer.LayeringState.VIEW_OFFSET)
            builder.setLayeringTransform(LayeringTransform.VIEW_OFFSET_Z_LAYERING);
        return builder.createRenderSetup();
    }
}
