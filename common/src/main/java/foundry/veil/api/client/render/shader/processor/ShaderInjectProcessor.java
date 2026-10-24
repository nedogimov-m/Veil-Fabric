package foundry.veil.api.client.render.shader.processor;

import foundry.veil.impl.client.render.shader.injection.NativeShaderInjectionService;
import foundry.veil.impl.client.render.shader.injection.ShaderInjectionManager;
import foundry.veil.impl.client.render.shader.injection.util.ShaderInjection;
import io.github.ocelot.glslprocessor.api.GlslSyntaxException;
import io.github.ocelot.glslprocessor.api.node.GlslTree;
import io.github.ocelot.glslprocessor.lib.anarres.cpp.LexerException;
import net.minecraft.resources.Identifier;

import java.io.IOException;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Injects shader modifications into shader source files.
 *
 * @author Ocelot
 * @author Vowxky
 */
public class ShaderInjectProcessor implements ShaderPreProcessor {

    private final ShaderInjectionManager.Snapshot snapshot;
    private final Set<ShaderInjection> appliedModifications;

    public ShaderInjectProcessor() {
        this(NativeShaderInjectionService.runtimeSnapshot());
    }

    public ShaderInjectProcessor(ShaderInjectionManager manager) { this(manager.snapshot()); }
    public ShaderInjectProcessor(ShaderInjectionManager.Snapshot snapshot) {
        this.snapshot = java.util.Objects.requireNonNull(snapshot);
        this.appliedModifications = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
    }

    @Override
    public void prepare() {
        this.appliedModifications.clear();
    }

    @Override
    public void modify(Context ctx, GlslTree tree) throws IOException, GlslSyntaxException, LexerException {
        Identifier name = ctx.name();
        if (name == null) return;

        boolean applyVersion = ctx.isSourceFile();
        for (Identifier include : ctx.shaderImporter().addedImports()) {
            this.applyModifiers(include, tree, applyVersion);
            String path = include.getPath();
            if (!path.endsWith(".glsl")) path += ".glsl";
            if (!path.startsWith("shaders/include/") && !path.startsWith("pinwheel/shaders/include/")) {
                this.applyModifiers(Identifier.fromNamespaceAndPath(include.getNamespace(), "shaders/include/" + path), tree, applyVersion);
                this.applyModifiers(Identifier.fromNamespaceAndPath(include.getNamespace(), "pinwheel/shaders/include/" + path), tree, applyVersion);
            }
        }
        this.applyModifiers(name, tree, applyVersion);
        if (ctx.isSourceFile()) {
            this.applyModifiers(ctx.stage().idConverter().idToFile(name), tree, applyVersion);
            if (ctx instanceof VeilContext veil && veil.definition() != null)
                this.applyModifiers(Identifier.fromNamespaceAndPath(name.getNamespace(), "pinwheel/shaders/program/" + name.getPath()
                        + (ctx.isVertex() ? ".vsh" : ".fsh")), tree, applyVersion);
        }
    }

    private void applyModifiers(Identifier shaderId, GlslTree tree, boolean applyVersion) throws IOException {
        List<ShaderInjection> modifiers = this.snapshot.getModifiers(shaderId);
        if (modifiers.isEmpty()) {
            return;
        }
        try {
            for (ShaderInjection modifier : modifiers) {
                if (this.appliedModifications.add(modifier)) modifier.inject(tree, applyVersion);
            }
        } catch (Exception e) {
            throw new IOException("Failed to transform shader " + shaderId, e);
        }
    }
}
