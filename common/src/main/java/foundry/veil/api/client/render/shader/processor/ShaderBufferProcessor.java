package foundry.veil.api.client.render.shader.processor;

import foundry.veil.api.client.render.shader.NativeShaderBlocks;
import foundry.veil.api.client.render.shader.block.ShaderBlock;
import foundry.veil.api.client.render.VeilShaderBufferLayout;
import foundry.veil.api.client.render.shader.ShaderFeature;
import io.github.ocelot.glslprocessor.api.GlslSyntaxException;
import io.github.ocelot.glslprocessor.api.node.GlslTree;
import io.github.ocelot.glslprocessor.lib.anarres.cpp.LexerException;
import net.minecraft.IdentifierException;
import net.minecraft.resources.Identifier;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Processes a shader to add buffer bindings.
 *
 * @author Ocelot
 */
public class ShaderBufferProcessor implements ShaderPreProcessor {

    private static final String BUFFER_KEY = "#veil:buffer ";
    private final Map<Identifier, VeilShaderBufferLayout<?>> layouts;
    private final Consumer<String> requiredBlocks;

    public ShaderBufferProcessor() {
        this(NativeShaderBlocks.snapshot(), ignored -> {});
    }
    public ShaderBufferProcessor(Map<Identifier, VeilShaderBufferLayout<?>> layouts, Consumer<String> requiredBlocks) {
        this.layouts = Map.copyOf(layouts); this.requiredBlocks = java.util.Objects.requireNonNull(requiredBlocks);
    }

    @Override
    public void modify(Context ctx, GlslTree tree) throws IOException, GlslSyntaxException, LexerException {
        List<String> buffers = new ArrayList<>();
        List<String> directives = tree.getDirectives();
        for (String directive : directives) {
            if (directive.startsWith(ShaderBufferProcessor.BUFFER_KEY)) {
                buffers.add(directive);
            }
        }
        for (String directive : buffers) {
            String[] parts = directive.substring(ShaderBufferProcessor.BUFFER_KEY.length()).trim().split(" +", 2);
            String bufferId = parts[0].trim();
            String interfaceName = parts.length > 1 ? parts[1].trim() : null;

            try {
                Identifier name = Identifier.parse(bufferId);
                VeilShaderBufferLayout<?> layout = this.layouts.get(name);
                if (layout == null) {
                    throw new IOException("Unknown buffer: " + name);
                }
                if (layout.binding() != ShaderBlock.BufferBinding.UNIFORM || layout.memoryLayout() != ShaderBlock.MemoryLayout.STD140)
                    throw new IOException("Native source blocks require an explicit std140 uniform layout: " + name);

                GlslTree loadedImport = new GlslTree();
                loadedImport.getBody().add(layout.createNode(ctx.hasFeatures(ShaderFeature.SHADER_STORAGE), interfaceName));
                ctx.include(tree, "#buffer " + name, loadedImport, IncludeOverloadStrategy.INCLUDE);
                this.requiredBlocks.accept(layout.name());
            } catch (IdentifierException e) {
                throw new IOException("Invalid buffer: " + bufferId, e);
            }
        }
        directives.removeAll(buffers);
    }
}
