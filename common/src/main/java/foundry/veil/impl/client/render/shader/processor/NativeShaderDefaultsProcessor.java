package foundry.veil.impl.client.render.shader.processor;

import foundry.veil.api.client.render.shader.NativeShaderDefaults;
import foundry.veil.api.client.render.shader.processor.ShaderPreProcessor;
import io.github.ocelot.glslprocessor.api.GlslParser;
import io.github.ocelot.glslprocessor.api.grammar.GlslTypeQualifier;
import io.github.ocelot.glslprocessor.api.grammar.GlslTypeSpecifier;
import io.github.ocelot.glslprocessor.api.node.GlslTree;
import io.github.ocelot.glslprocessor.api.node.constant.GlslConstantNode;
import io.github.ocelot.glslprocessor.api.node.variable.GlslNewFieldNode;
import io.github.ocelot.glslprocessor.api.node.variable.GlslStructDeclarationNode;
import net.minecraft.resources.Identifier;

import java.io.IOException;
import java.util.ArrayList;
import java.util.function.Consumer;

/** Rehomes the three old automatic defaults into native view and draw owners, before loose-uniform packing. */
public final class NativeShaderDefaultsProcessor implements ShaderPreProcessor {
    private final Consumer<String> viewBlocks;
    private final Consumer<String> nativeBlocks;
    public NativeShaderDefaultsProcessor(Consumer<String> viewBlocks, Consumer<String> nativeBlocks) {
        this.viewBlocks = viewBlocks; this.nativeBlocks = nativeBlocks;
    }
    @Override public void modify(Context context, GlslTree tree) throws IOException, io.github.ocelot.glslprocessor.api.GlslSyntaxException, io.github.ocelot.glslprocessor.lib.anarres.cpp.LexerException {
        if (!context.isSourceFile()) return;
        boolean view = false, normal = false;
        var removed = new ArrayList<GlslNewFieldNode>();
        for (GlslNewFieldNode field : tree.fields().toList()) {
            if (!field.getType().getQualifiers().contains(GlslTypeQualifier.StorageType.UNIFORM)) continue;
            String name = field.getName();
            if (!name.equals("VeilRenderTime") && !name.equals("VeilBlockFaceBrightness") && !name.equals("NormalMat")) continue;
            GlslTypeSpecifier type = field.getType().getSpecifier();
            boolean valid = switch (name) {
                case "VeilRenderTime" -> !(type instanceof GlslTypeSpecifier.Array) && type.getName().equals("float");
                case "NormalMat" -> !(type instanceof GlslTypeSpecifier.Array) && type.getName().equals("mat3");
                default -> type instanceof GlslTypeSpecifier.Array array && array.getSpecifier().getName().equals("float")
                        && array.getSize() instanceof GlslConstantNode size && size.intValue() == 6;
            };
            if (!valid || field.getInitializer() != null) throw new IOException("Native automatic default requires its exact declaration: " + name);
            removed.add(field);
            if (name.equals("NormalMat")) normal = true; else view = true;
        }
        tree.getBody().removeAll(removed);
        if (view) {
            if (tree.getBody().stream().anyMatch(node -> node instanceof GlslStructDeclarationNode block && NativeShaderDefaults.BLOCK.equals(block.getName())))
                throw new IOException("Native automatic default block already declared: " + NativeShaderDefaults.BLOCK);
            tree.getBody().addAll(0, GlslParser.parse(NativeShaderDefaults.LAYOUT.declaration(NativeShaderDefaults.BLOCK)).getBody());
            viewBlocks.accept(NativeShaderDefaults.BLOCK);
        }
        if (normal) {
            boolean declared = tree.getBody().stream().anyMatch(node -> node instanceof GlslStructDeclarationNode block && "DynamicTransforms".equals(block.getName()));
            if (!declared) {
                GlslTree imported = context.shaderImporter().loadImport(context, Identifier.withDefaultNamespace("dynamictransforms.glsl"), true);
                tree.getBody().addAll(0, imported.getBody());
            }
            tree.getMacros().put("NormalMat", "transpose(inverse(mat3(ModelViewMat)))");
            nativeBlocks.accept("DynamicTransforms");
        }
    }
}
