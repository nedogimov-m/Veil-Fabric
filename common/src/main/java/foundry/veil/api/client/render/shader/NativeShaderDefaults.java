package foundry.veil.api.client.render.shader;

import com.mojang.renderpearl.api.device.GpuDevice;
import foundry.veil.api.client.render.gpu.GpuUniformBuffer;
import foundry.veil.api.client.render.shader.block.ShaderBlock;
import net.minecraft.core.Direction;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;
import java.util.function.ToDoubleFunction;

/** Immutable native view defaults. NormalMat is evaluated from each draw's native DynamicTransforms. */
public final class NativeShaderDefaults {
    public static final String BLOCK = "VeilStandardDefaults";
    public static final GpuUniformBuffer.Layout LAYOUT = GpuUniformBuffer.layoutBuilder()
            .field("VeilRenderTime", GpuUniformBuffer.ValueType.FLOAT)
            .array("VeilBlockFaceBrightness", GpuUniformBuffer.ValueType.FLOAT, 6).build();
    private static Supplier<Values> runtime;
    private static final ThreadLocal<Scope> CURRENT = new ThreadLocal<>();

    public record Values(float renderTime, float[] faceBrightness) {
        public Values {
            if (!Float.isFinite(renderTime)) throw new IllegalArgumentException("Finite render time required");
            if (faceBrightness.length != 6) throw new IllegalArgumentException("Six face shades in Direction.get3DDataValue order required");
            faceBrightness = faceBrightness.clone();
            for (float shade : faceBrightness) if (!Float.isFinite(shade)) throw new IllegalArgumentException("Finite face shade required");
        }
        @Override public float[] faceBrightness() { return faceBrightness.clone(); }
        public static Values capture(long milliseconds, ToDoubleFunction<Direction> shade) {
            float[] faces = new float[6];
            for (Direction direction : Direction.values()) faces[direction.get3DDataValue()] = (float) shade.applyAsDouble(direction);
            return new Values(Math.floorMod(milliseconds, 3_600_000) / 1000.0f, faces);
        }
        public ShaderBlock.Snapshot snapshot(GpuDevice device) {
            ByteBuffer bytes = ByteBuffer.allocateDirect(LAYOUT.size()).order(ByteOrder.nativeOrder());
            bytes.putFloat(LAYOUT.fields().get("VeilRenderTime").offset(0), renderTime);
            var field = LAYOUT.fields().get("VeilBlockFaceBrightness");
            for (int i = 0; i < 6; i++) bytes.putFloat(field.offset(i), faceBrightness[i]);
            return ShaderBlock.Snapshot.serialized(device, bytes);
        }
    }

    public static synchronized void bindRuntime(Supplier<Values> provider) {
        if (runtime != null && runtime != provider) throw new IllegalStateException("Native shader defaults runtime already bound");
        runtime = Objects.requireNonNull(provider);
    }
    public static synchronized void unbindRuntime(Supplier<Values> provider) { if (runtime == provider) runtime = null; }
    public static boolean isAvailable() {
        synchronized (NativeShaderDefaults.class) { return runtime != null || CURRENT.get() != null; }
    }
    /** Custom world/view owners may override the provider before entering their native resource frame. */
    public static Scope begin(Values values) {
        Scope scope = new Scope(CURRENT.get(), Objects.requireNonNull(values)); CURRENT.set(scope); return scope;
    }
    public static Optional<ShaderBlock.Snapshot> snapshot(GpuDevice device) {
        Scope current = CURRENT.get(); Supplier<Values> provider;
        synchronized (NativeShaderDefaults.class) { provider = runtime; }
        Values value = current != null ? current.values : provider == null ? null : Objects.requireNonNull(provider.get());
        return value == null ? Optional.empty() : Optional.of(value.snapshot(device));
    }
    public static final class Scope implements AutoCloseable {
        private final Scope previous;
        private final Values values;
        private boolean closed;
        private Scope(Scope previous, Values values) { this.previous = previous; this.values = values; }
        @Override public void close() {
            if (closed) return;
            if (CURRENT.get() != this) throw new IllegalStateException("Native shader defaults scopes must close in stack order");
            if (previous == null) CURRENT.remove(); else CURRENT.set(previous);
            closed = true;
        }
    }
    private NativeShaderDefaults() { }
}
