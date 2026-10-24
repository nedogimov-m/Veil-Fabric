package foundry.veil.impl.client.render.shader.compiler;

import com.mojang.renderpearl.api.device.GpuDevice;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import com.mojang.renderpearl.api.pipeline.ShaderSource;
import foundry.veil.api.client.render.gpu.GpuPipeline;
import foundry.veil.api.client.render.gpu.GpuShaderSource;
import foundry.veil.api.client.render.shader.compiler.CompiledShader;
import foundry.veil.api.client.render.shader.compiler.ShaderCompiler;
import foundry.veil.api.client.render.shader.compiler.ShaderException;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.Executor;

/** Native compilation finishes on the owning render thread; workers only prepare native shader modules. */
@ApiStatus.Internal
public class DirectShaderCompiler implements ShaderCompiler {
    private final GpuDevice device;
    private final @Nullable ShaderProvider provider;
    private boolean closed;

    public DirectShaderCompiler(GpuDevice device, @Nullable ShaderProvider provider) {
        this.device = Objects.requireNonNull(device); this.provider = provider;
    }
    protected final void ensureOpen() {
        if (this.closed) throw new IllegalStateException("Native shader compiler is closed");
    }
    @Override public CompiledShader compile(RenderPipeline pipeline, Executor workers) throws IOException, ShaderException {
        this.ensureOpen();
        if (this.provider == null) throw new IOException("No native shader source provider for " + pipeline.getLocation());
        try (ShaderSource source = Objects.requireNonNull(this.provider.getShader(pipeline))) {
            return this.compile(pipeline, source, workers);
        }
    }
    @Override public CompiledShader compile(RenderPipeline pipeline, ShaderSource source, Executor workers) throws ShaderException {
        this.ensureOpen();
        Objects.requireNonNull(pipeline); Objects.requireNonNull(source); Objects.requireNonNull(workers);
        try {
            GpuPipeline compiled = GpuPipeline.compile(this.device, pipeline, source, workers);
            try {
                var dependencies = new HashSet<>(pipeline.getShaderDefines().values().keySet());
                dependencies.addAll(pipeline.getShaderDefines().flags());
                return new CompiledShader(compiled, dependencies,
                        source instanceof GpuShaderSource resources ? resources.includedResources() : Set.of());
            } catch (RuntimeException | Error failure) { compiled.close(); throw failure; }
        } catch (RuntimeException failure) {
            var error = new ShaderException("Failed to compile native shader pipeline " + pipeline.getLocation(), failure.getMessage());
            error.initCause(failure); throw error;
        }
    }
    /** Direct compilation transfers ownership to each returned handle. */
    @Override public void free() { this.closed = true; }
}
