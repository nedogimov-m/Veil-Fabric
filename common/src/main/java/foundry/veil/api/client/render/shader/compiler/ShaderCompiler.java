package foundry.veil.api.client.render.shader.compiler;

import com.mojang.renderpearl.api.device.GpuDevice;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import com.mojang.renderpearl.api.pipeline.ShaderSource;
import foundry.veil.impl.client.render.shader.compiler.CachedShaderCompiler;
import foundry.veil.impl.client.render.shader.compiler.DirectShaderCompiler;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.util.concurrent.Executor;

/** Compiles a complete immutable native pipeline, including its actual draw state and typed bindings. */
public interface ShaderCompiler extends AutoCloseable {
    /** The supplied source remains caller-owned and must remain live until this call returns. */
    CompiledShader compile(RenderPipeline pipeline, ShaderSource source, Executor workers) throws ShaderException;

    /** The provider transfers a fresh source owner, closed after native compilation finishes. */
    CompiledShader compile(RenderPipeline pipeline, Executor workers) throws IOException, ShaderException;

    static ShaderCompiler direct(GpuDevice device, @Nullable ShaderProvider provider) {
        return new DirectShaderCompiler(device, provider);
    }
    static ShaderCompiler direct(GpuDevice device) { return direct(device, null); }
    static ShaderCompiler cached(GpuDevice device, @Nullable ShaderProvider provider) {
        return new CachedShaderCompiler(device, provider);
    }
    static ShaderCompiler cached(GpuDevice device) { return cached(device, null); }

    void free();
    @Override default void close() { this.free(); }

    @FunctionalInterface
    interface ShaderProvider {
        ShaderSource getShader(RenderPipeline pipeline) throws IOException;
    }
}
