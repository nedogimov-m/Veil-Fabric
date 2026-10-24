package foundry.veil.api.client.render.gpu;

import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.api.device.GpuDevice;
import com.mojang.renderpearl.api.pipeline.CompiledRenderPipeline;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import com.mojang.renderpearl.api.pipeline.ShaderSource;

import java.util.Objects;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executor;

/** Owns a real compiled RenderPearl pipeline; reload swaps only successful compilations. */
public final class GpuPipeline implements AutoCloseable {
    private final GpuDevice device;
    private final RenderPipeline definition;
    private CompiledRenderPipeline compiled;
    private boolean closed;

    private GpuPipeline(GpuDevice device, RenderPipeline definition, CompiledRenderPipeline compiled) {
        this.device = device;
        this.definition = definition;
        this.compiled = compiled;
    }

    /** Finish compilation on the caller's render thread, after workers build the shader modules. */
    public static GpuPipeline compile(GpuDevice device, RenderPipeline definition, ShaderSource source, Executor workers) {
        Objects.requireNonNull(device);
        Objects.requireNonNull(definition);
        definition = foundry.veil.api.client.render.shader.NativePipelineBridge.prepareDirectDefinition(definition, source);
        return new GpuPipeline(device, definition, compileNative(device, definition, source, workers));
    }

    private static CompiledRenderPipeline compileNative(GpuDevice device, RenderPipeline definition, ShaderSource source, Executor workers) {
        try {
            CompiledRenderPipeline result = device.compilePipeline(definition, source, workers).join().finishCompile();
            if (result == null || result.isClosed()) throw new IllegalStateException("GPU compiler returned no usable pipeline: " + definition.getLocation());
            try {
                foundry.veil.api.client.render.shader.NativePipelineBridge.directCompiled(definition, result, source);
                foundry.veil.impl.client.render.shader.debug.NativePipelineRegistry.observe(definition, result);
                return result;
            } catch (RuntimeException | Error failure) { result.close(); throw failure; }
        } catch (CompletionException failure) {
            throw new IllegalStateException("GPU pipeline compilation failed: " + definition.getLocation(), failure.getCause());
        }
    }

    /** On failure the previously compiled pipeline remains live. The source remains caller-owned. */
    public void reload(ShaderSource source, Executor workers) {
        this.ensureOpen();
        RenderPipeline prepared = foundry.veil.api.client.render.shader.NativePipelineBridge.prepareDirectDefinition(this.definition, source);
        if (!prepared.getBindGroupLayouts().equals(this.definition.getBindGroupLayouts()))
            throw new IllegalArgumentException("Changed native binding layouts require a new immutable pipeline owner");
        CompiledRenderPipeline replacement = compileNative(this.device, prepared, source, workers);
        CompiledRenderPipeline previous = this.compiled;
        this.compiled = replacement;
        previous.close();
    }

    public RenderPipeline definition() { return this.definition; }
    public void bind(RenderPass pass) { this.ensureOpen(); pass.setPipeline(this.compiled); }
    public boolean isClosed() { return this.closed; }
    private void ensureOpen() {
        if (this.closed || this.compiled.isClosed()) throw new IllegalStateException("GPU pipeline is closed: " + this.definition.getLocation());
    }
    @Override public void close() {
        if (this.closed) return;
        this.closed = true;
        this.compiled.close();
    }
}
