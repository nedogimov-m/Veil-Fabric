package foundry.veil.api.client.render.profiler;

/** Counters exposed by Minecraft26.3's native GPU API. Values use the device timestamp period. */
public enum RenderProfilerCounter {
    GPU_TIME_NANOS;

    public static final RenderProfilerCounter[] ALL = values();
    public static final RenderProfilerCounter[] STANDARD_GEOMETRY = {GPU_TIME_NANOS};
}
