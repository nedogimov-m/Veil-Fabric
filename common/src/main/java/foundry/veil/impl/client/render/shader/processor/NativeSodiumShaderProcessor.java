package foundry.veil.impl.client.render.shader.processor;

import com.mojang.renderpearl.api.GpuFormat;
import foundry.veil.api.client.render.dynamicbuffer.DynamicBufferType;
import foundry.veil.api.client.render.shader.processor.ShaderPreProcessor;
import io.github.ocelot.glslprocessor.api.GlslInjectionPoint;
import io.github.ocelot.glslprocessor.api.GlslParser;
import io.github.ocelot.glslprocessor.api.grammar.GlslTypeSpecifier;
import io.github.ocelot.glslprocessor.api.node.GlslTree;
import io.github.ocelot.glslprocessor.api.node.variable.GlslStructDeclarationNode;

import java.io.IOException;
import java.util.HashMap;

/** Supplies the real native Sodium normal input to the shared dynamic-buffer marker processor. */
public final class NativeSodiumShaderProcessor {
    public static GlslTree prepare(ShaderPreProcessor.Context context, GlslTree tree) throws IOException,
            io.github.ocelot.glslprocessor.api.GlslSyntaxException,
            io.github.ocelot.glslprocessor.lib.anarres.cpp.LexerException {
        if (!context.isSourceFile() || !context.isVertex() || context.name() == null
                || !context.name().getNamespace().equals("sodium")
                || (context.activeBuffers() & DynamicBufferType.NORMAL.getMask()) == 0
                || !(context instanceof ShaderPreProcessor.MinecraftContext nativeContext)
                || nativeContext.vertexFormat() == null || !nativeContext.vertexFormat().contains("a_VeilNormal")
                || tree.getMarkers().containsKey("veil:normal")) return tree;

        var format = nativeContext.vertexFormat();
        var attribute = format.getElement("a_VeilNormal");
        if (attribute.format() != GpuFormat.RGBA8_SNORM) throw new IOException("Native Sodium a_VeilNormal requires RGBA8_SNORM");
        boolean globals = tree.getBody().stream().filter(GlslStructDeclarationNode.class::isInstance)
                .map(GlslStructDeclarationNode.class::cast)
                .filter(block -> "u_Globals".equals(block.getName()))
                .anyMatch(block -> block.getStructSpecifier().getFields().stream()
                        .anyMatch(field -> field.getName().equals("u_ModelViewMatrix")));
        if (!globals) throw new IOException("Native Sodium normal transform requires its genuine u_Globals.u_ModelViewMatrix block");

        var declared = tree.field("a_VeilNormal");
        if (declared.isPresent() && declared.get().getType().getSpecifier() != GlslTypeSpecifier.BuiltinType.VEC4)
            throw new IOException("Native Sodium a_VeilNormal requires a vec4 shader input");
        if (declared.isEmpty()) tree.getBody().add(GlslInjectionPoint.BEFORE_MAIN,
                GlslParser.parseExpression("layout(location = " + format.getElements().indexOf(attribute) + ") in vec4 a_VeilNormal"));
        var normal = GlslParser.parseExpression("vec3 veilNativeSodiumNormal = transpose(inverse(mat3(u_ModelViewMatrix))) * a_VeilNormal.xyz");
        tree.mainFunction().orElseThrow().getBody().addFirst(normal);
        // The parser deliberately exposes an immutable marker map. Preserve every existing marker
        // while constructing a genuine tree over the same AST nodes before extensible processors run.
        var markers = new HashMap<>(tree.getMarkers()); markers.put("veil:normal", normal);
        var marked = new GlslTree(tree.getVersionStatement(), tree.getBody(), tree.getDirectives(), markers);
        marked.getMacros().putAll(tree.getMacros());
        return marked;
    }
    private NativeSodiumShaderProcessor() { }
}
