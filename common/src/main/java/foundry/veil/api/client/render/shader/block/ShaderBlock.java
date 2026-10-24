package foundry.veil.api.client.render.shader.block;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.commands.CommandEncoder;
import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.api.device.GpuDevice;
import foundry.veil.impl.client.render.shader.block.DynamicShaderBlockImpl;
import foundry.veil.impl.client.render.shader.block.SizedShaderBlockImpl;
import foundry.veil.impl.client.render.shader.block.WrapperShaderBlockImpl;
import io.github.ocelot.glslprocessor.api.grammar.GlslTypeQualifier;
import org.jetbrains.annotations.Nullable;
import org.lwjgl.system.NativeResource;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Locale;
import java.util.Objects;
import java.util.function.BiConsumer;

/** Serialized native buffer block. Upload before opening the pass; bind by its shader name. */
public interface ShaderBlock<T> extends NativeResource {
    static <T> ShaderBlock<T> withSize(BufferBinding binding, int size, BiConsumer<T, ByteBuffer> serializer) {
        return withSize(RenderSystem.getDevice(), binding, size, serializer);
    }
    static <T> ShaderBlock<T> withSize(GpuDevice device, BufferBinding binding, int size, BiConsumer<T, ByteBuffer> serializer) {
        return new SizedShaderBlockImpl<>(device, binding, size, serializer);
    }
    static <T> DynamicShaderBlock<T> dynamic(BufferBinding binding, BiConsumer<T, ByteBuffer> serializer) {
        return dynamic(binding, 256, serializer);
    }
    static <T> DynamicShaderBlock<T> dynamic(BufferBinding binding, int size, BiConsumer<T, ByteBuffer> serializer) {
        return new DynamicShaderBlockImpl<>(RenderSystem.getDevice(), binding, size, serializer);
    }
    static <T> DynamicShaderBlock<T> dynamic(GpuDevice device, BufferBinding binding, int size, BiConsumer<T, ByteBuffer> serializer) {
        return new DynamicShaderBlockImpl<>(device, binding, size, serializer);
    }
    /** The wrapper borrows the typed slice; free never closes its backing buffer. */
    static DynamicShaderBlock<?> wrapper(BufferBinding binding, GpuBufferSlice buffer) {
        return new WrapperShaderBlockImpl(binding, buffer);
    }
    default void update(@Nullable T value) { if (!Objects.equals(this.getValue(), value)) this.set(value); }
    void set(@Nullable T value);
    @Nullable T getValue();
    void prepare(CommandEncoder encoder);
    /** Capture this draw without GPU commands. Owned values are serialized now; borrowed ranges retain their owner contract. */
    Snapshot snapshot();
    GpuBufferSlice slice();
    BufferBinding getBinding();
    default void bind(RenderPass pass, String name) { pass.setUniform(name, this.slice()); }

    /** Immutable serialized bytes or an explicitly borrowed range, independent of later block mutation/disposal. */
    final class Snapshot {
        private final GpuDevice device;
        private final ByteBuffer bytes;
        private final GpuBufferSlice borrowed;
        private Snapshot(GpuDevice device, ByteBuffer bytes, GpuBufferSlice borrowed) {
            this.device = device; this.bytes = bytes; this.borrowed = borrowed;
        }
        public static Snapshot serialized(GpuDevice device, ByteBuffer source) {
            Objects.requireNonNull(device); Objects.requireNonNull(source);
            if (!source.hasRemaining() || source.remaining() % 16 != 0) throw new IllegalArgumentException("Serialized UBO snapshots require positive, 16-byte padded data");
            ByteBuffer copy = ByteBuffer.allocateDirect(source.remaining()).order(ByteOrder.nativeOrder());
            copy.put(source.duplicate()).flip();
            return new Snapshot(device, copy.asReadOnlyBuffer().order(ByteOrder.nativeOrder()), null);
        }
        public static Snapshot borrowed(GpuBufferSlice range) {
            Objects.requireNonNull(range);
            if (range.buffer().isClosed()) throw new IllegalStateException("Borrowed shader block storage is closed");
            return new Snapshot(null, null, range);
        }
        public boolean isBorrowed() { return this.borrowed != null; }
        public ByteBuffer bytes() {
            if (this.bytes == null) throw new IllegalStateException("Borrowed GPU storage has no CPU snapshot");
            return this.bytes.asReadOnlyBuffer().order(ByteOrder.nativeOrder());
        }
        /** Upload into a distinct native transient range before opening the render pass. */
        public GpuBufferSlice prepare(CommandEncoder encoder) {
            if (this.borrowed != null) {
                if (this.borrowed.buffer().isClosed()) throw new IllegalStateException("Borrowed shader block storage expired before draw submission");
                return this.borrowed;
            }
            return encoder.transientMemory().uploadGpu(this.bytes(),
                    this.device.getDeviceInfo().limits().minUniformOffsetAlignment(), GpuBuffer.USAGE_UNIFORM);
        }
    }

    enum BufferBinding { UNIFORM, SHADER_STORAGE }
    enum MemoryLayout {
        PACKED, SHARED, STD140, STD430;
        private final GlslTypeQualifier.LayoutId layoutId = new GlslTypeQualifier.LayoutId(this.name().toLowerCase(Locale.ROOT), null);
        public GlslTypeQualifier.LayoutId getLayoutId() { return this.layoutId; }
    }
}
