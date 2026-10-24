package foundry.veil.impl.client.render.shader.debug;

import com.mojang.blaze3d.pipeline.PipelineCache;
import com.mojang.renderpearl.api.pipeline.CompiledRenderPipeline;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import foundry.veil.mixin.debug.accessor.DebugPipelineCacheAccessor;
import foundry.veil.api.client.render.shader.NativePipelineBridge;
import java.lang.ref.WeakReference;
import java.util.*;

/** Read-only observation of real live cache entries and Veil-owned compiled pipelines; never compiles debug selections. */
public final class NativePipelineRegistry {
    private static final Map<PipelineCache, Boolean> CACHES = new WeakHashMap<>();
    private static final Map<RenderPipeline, WeakReference<CompiledRenderPipeline>> DIRECT = new WeakHashMap<>();
    public static synchronized void cacheCreated(PipelineCache cache) { CACHES.put(cache, Boolean.TRUE); }
    public static synchronized void observe(RenderPipeline definition, CompiledRenderPipeline compiled) {
        if (!compiled.isClosed()) DIRECT.put(definition, new WeakReference<>(compiled));
    }
    /** Call on the render thread, where native cache mutations occur. Snapshot references do not retain GPU resources. */
    public static synchronized List<Entry> snapshot() {
        Map<RenderPipeline, Entry> entries = new IdentityHashMap<>();
        DIRECT.entrySet().removeIf(entry -> { CompiledRenderPipeline compiled = entry.getValue().get(); return compiled == null || compiled.isClosed(); });
        DIRECT.forEach((definition, compiled) -> entries.put(definition, new Entry(definition, compiled)));
        for (PipelineCache cache : CACHES.keySet()) {
            ((DebugPipelineCacheAccessor) cache).veil$getCache().forEach((definition, compiled) -> {
                if (!compiled.isClosed()) {
                    RenderPipeline actual = NativePipelineBridge.actualDefinition(compiled).orElse(definition);
                    entries.put(actual, new Entry(actual, new WeakReference<>(compiled)));
                }
            });
        }
        return entries.values().stream().sorted(Comparator.comparing((Entry entry) -> entry.definition().getLocation().toString())
                .thenComparingInt(entry -> System.identityHashCode(entry.definition()))).toList();
    }
    public record Entry(RenderPipeline definition, WeakReference<CompiledRenderPipeline> compiled) {
        public boolean isLive() { CompiledRenderPipeline pipeline = this.compiled.get(); return pipeline != null && !pipeline.isClosed(); }
    }
    private NativePipelineRegistry() { }
}
