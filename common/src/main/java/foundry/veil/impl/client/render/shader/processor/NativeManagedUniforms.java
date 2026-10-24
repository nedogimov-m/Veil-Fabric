package foundry.veil.impl.client.render.shader.processor;

import foundry.veil.api.client.render.gpu.GpuShaderSource;
import foundry.veil.api.client.render.gpu.GpuUniformBuffer;
import io.github.ocelot.glslprocessor.api.GlslParser;
import io.github.ocelot.glslprocessor.api.grammar.GlslTypeQualifier;
import io.github.ocelot.glslprocessor.api.grammar.GlslTypeSpecifier;
import io.github.ocelot.glslprocessor.api.node.GlslTree;
import io.github.ocelot.glslprocessor.api.node.constant.GlslConstantNode;
import io.github.ocelot.glslprocessor.api.node.variable.GlslNewFieldNode;
import io.github.ocelot.glslprocessor.api.node.variable.GlslStructDeclarationNode;

import java.io.IOException;
import java.util.*;

/** Merge actual loose numeric declarations into one explicit std140 schema shared by every native stage. */
public final class NativeManagedUniforms {
    public static Set<String> samplers(Collection<GlslTree> stages) throws IOException {
        var names = new HashSet<String>();
        for (GlslTree tree : stages) for (GlslNewFieldNode field : tree.fields().toList()) {
            if (!field.getType().getQualifiers().contains(GlslTypeQualifier.StorageType.UNIFORM)) continue;
            GlslTypeSpecifier type = field.getType().getSpecifier();
            boolean array = type instanceof GlslTypeSpecifier.Array;
            if (array) type = ((GlslTypeSpecifier.Array) type).getSpecifier();
            if (!type.getName().contains("sampler")) continue;
            if (array) throw new IOException("Native sampler arrays require explicit separate sampled-image bindings: " + field.getName());
            names.add(field.getName());
        }
        return Set.copyOf(names);
    }
    public static Map<String, GpuUniformBuffer.Layout> rewrite(Collection<GlslTree> stages) throws IOException {
        var fields = new LinkedHashMap<String, Declaration>();
        var removed = new IdentityHashMap<GlslTree, List<GlslNewFieldNode>>();
        for (GlslTree tree : stages) {
            var declarations = new ArrayList<GlslNewFieldNode>();
            for (GlslNewFieldNode field : tree.fields().toList()) {
                if (!field.getType().getQualifiers().contains(GlslTypeQualifier.StorageType.UNIFORM)) continue;
                GlslTypeSpecifier type = field.getType().getSpecifier(); int count = 0;
                if (type instanceof GlslTypeSpecifier.Array array) {
                    type = array.getSpecifier();
                    if (!(array.getSize() instanceof GlslConstantNode size) || size.intValue() <= 0)
                        throw new IOException("Native managed uniform arrays need a positive constant length: " + field.getName());
                    count = size.intValue();
                }
                // Existing named uniform blocks and sampled images retain their actual explicit binding contracts.
                if (type.isStruct() || type.getName().contains("sampler") || type.getName().contains("image")) continue;
                GpuUniformBuffer.ValueType valueType;
                try { valueType = GpuUniformBuffer.ValueType.fromGlsl(type.getName()); }
                catch (IllegalArgumentException unsupported) { throw new IOException("Native managed uniform type requires an explicit migration: " + type.getName() + " " + field.getName(), unsupported); }
                if (field.getInitializer() != null) throw new IOException("Native managed uniform initializers require an explicit typed value: " + field.getName());
                Declaration declaration = new Declaration(valueType, count);
                Declaration previous = fields.putIfAbsent(field.getName(), declaration);
                if (previous != null && !previous.equals(declaration)) throw new IOException("Uniform declarations differ between native stages: " + field.getName());
                declarations.add(field);
            }
            removed.put(tree, declarations);
        }
        if (fields.isEmpty()) return Map.of();
        var builder = GpuUniformBuffer.layoutBuilder();
        fields.forEach((name, declaration) -> {
            if (declaration.length == 0) builder.field(name, declaration.type); else builder.array(name, declaration.type, declaration.length);
        });
        GpuUniformBuffer.Layout layout = builder.build();
        for (GlslTree tree : stages) {
            if (tree.getBody().stream().anyMatch(node -> node instanceof GlslStructDeclarationNode block
                    && GpuShaderSource.MANAGED_UNIFORMS.equals(block.getName())))
                throw new IOException("Generated managed uniform block name already declared: " + GpuShaderSource.MANAGED_UNIFORMS);
            tree.getBody().removeAll(removed.get(tree));
            try { tree.getBody().addAll(0, GlslParser.parse(layout.declaration(GpuShaderSource.MANAGED_UNIFORMS)).getBody()); }
            catch (Exception failure) { throw new IOException("Cannot declare generated managed uniform block", failure); }
        }
        return Map.of(GpuShaderSource.MANAGED_UNIFORMS, layout);
    }
    private record Declaration(GpuUniformBuffer.ValueType type, int length) { }
    private NativeManagedUniforms() { }
}
