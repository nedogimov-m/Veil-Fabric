package foundry.veil.impl.client.render.shader.compiler;

import com.mojang.renderpearl.api.device.GpuDevice;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import com.mojang.renderpearl.api.pipeline.ShaderSource;
import foundry.veil.api.client.render.shader.compiler.CompiledShader;
import foundry.veil.api.client.render.shader.compiler.ShaderException;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.concurrent.Executor;

/** Caches exact immutable pipeline/source owners, without hashing away source or draw-state differences. */
@ApiStatus.Internal
public class CachedShaderCompiler extends DirectShaderCompiler {
    private final Map<RenderPipeline, Map<ShaderSource, CompiledShader>> suppliedSources = new IdentityHashMap<>();
    private final Map<RenderPipeline, CompiledShader> providerSources = new IdentityHashMap<>();
    private boolean loadingProvider;

    public CachedShaderCompiler(GpuDevice device, @Nullable ShaderProvider provider) { super(device, provider); }

    @Override public CompiledShader compile(RenderPipeline pipeline, Executor workers) throws IOException, ShaderException {
        this.ensureOpen();
        CompiledShader cached = this.providerSources.get(pipeline);
        if (cached != null) return cached.retain();
        this.loadingProvider = true;
        try {
            CompiledShader compiled = super.compile(pipeline, workers);
            this.providerSources.put(pipeline, compiled);
            return compiled.retain();
        } finally { this.loadingProvider = false; }
    }
    @Override public CompiledShader compile(RenderPipeline pipeline, ShaderSource source, Executor workers) throws ShaderException {
        this.ensureOpen();
        // The provider overload owns and closes this temporary source after compilation.
        if (this.loadingProvider) return super.compile(pipeline, source, workers);
        Map<ShaderSource, CompiledShader> sources = this.suppliedSources.computeIfAbsent(pipeline, ignored -> new IdentityHashMap<>());
        CompiledShader cached = sources.get(source);
        if (cached == null) {
            cached = super.compile(pipeline, source, workers);
            sources.put(source, cached);
        }
        return cached.retain();
    }
    /** Call on resource reload; existing caller leases remain live through their last draw. */
    public void clear() {
        this.providerSources.values().forEach(CompiledShader::close); this.providerSources.clear();
        this.suppliedSources.values().forEach(sources -> sources.values().forEach(CompiledShader::close));
        this.suppliedSources.clear();
    }
    @Override public void free() { this.clear(); super.free(); }
}
