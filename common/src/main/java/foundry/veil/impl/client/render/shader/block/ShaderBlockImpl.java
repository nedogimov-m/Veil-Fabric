package foundry.veil.impl.client.render.shader.block;

import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import com.mojang.renderpearl.api.commands.CommandEncoder;
import com.mojang.renderpearl.api.device.GpuDevice;
import foundry.veil.api.client.render.shader.block.ShaderBlock;
import org.jetbrains.annotations.Nullable;
import org.lwjgl.system.MemoryUtil;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Objects;
import java.util.function.BiConsumer;

/** Owns native uniform storage and CPU staging memory; all GPU uploads are explicit. */
public abstract class ShaderBlockImpl<T> implements ShaderBlock<T> {
    protected final GpuDevice device;
    protected final BufferBinding binding;
    @Nullable protected GpuBuffer buffer;
    @Nullable protected T value;
    protected boolean dirty = true;
    private ByteBuffer staging;
    private boolean closed;

    protected ShaderBlockImpl(GpuDevice device, BufferBinding binding) {
        this.device = Objects.requireNonNull(device); this.binding = Objects.requireNonNull(binding);
        if (binding != BufferBinding.UNIFORM) throw new UnsupportedOperationException("RenderPearl exposes UBOs and typed texel buffers; writable shader storage requires a native algorithm migration");
    }
    protected static int allocationSize(int size) {
        if (size <= 0) throw new IllegalArgumentException("Shader block size must be positive");
        return Math.multiplyExact(Math.floorDiv(Math.addExact(size, 15), 16), 16);
    }
    @Override public void set(@Nullable T value) { this.ensureOpen(); this.value = value; this.dirty = true; }
    @Override public @Nullable T getValue() { this.ensureOpen(); return this.value; }
    @Override public BufferBinding getBinding() { return this.binding; }
    protected final void upload(CommandEncoder encoder, int requestedSize, BiConsumer<T, ByteBuffer> serializer) {
        this.ensureOpen();
        int size = allocationSize(requestedSize);
        if (!this.dirty && this.buffer != null && this.buffer.size() == size) return;
        this.serialize(requestedSize, serializer);
        GpuBuffer replacement = this.buffer;
        boolean allocated = replacement == null || replacement.size() != size;
        if (allocated) replacement = this.device.createBuffer(() -> "Veil serialized uniform block", GpuBuffer.USAGE_UNIFORM | GpuBuffer.USAGE_COPY_DST | GpuBuffer.USAGE_COPY_SRC, size);
        try { encoder.writeToBuffer(replacement.slice(), this.staging.duplicate().clear()); }
        catch (RuntimeException | Error failure) { if (allocated) replacement.close(); throw failure; }
        if (allocated) {
            GpuBuffer previous = this.buffer; this.buffer = replacement;
            if (previous != null) previous.close();
        }
        this.dirty = false;
    }
    protected final Snapshot snapshot(int requestedSize, BiConsumer<T, ByteBuffer> serializer) {
        this.ensureOpen();
        this.serialize(requestedSize, serializer);
        return Snapshot.serialized(this.device, this.staging.duplicate().clear());
    }
    private void serialize(int requestedSize, BiConsumer<T, ByteBuffer> serializer) {
        int size = allocationSize(requestedSize);
        if (this.staging == null || this.staging.capacity() != size) {
            ByteBuffer replacement = MemoryUtil.memAlloc(size).order(ByteOrder.nativeOrder());
            if (this.staging != null) MemoryUtil.memFree(this.staging);
            this.staging = replacement;
        }
        this.staging.clear(); MemoryUtil.memSet(this.staging, 0);
        if (this.value != null) serializer.accept(this.value, this.staging.limit(requestedSize));
        this.staging.clear();
    }
    @Override public GpuBufferSlice slice() {
        this.ensureOpen();
        if (this.dirty || this.buffer == null) throw new IllegalStateException("Prepare shader block before opening its render pass");
        if (this.buffer.isClosed()) throw new IllegalStateException("Shader block storage was closed externally");
        return this.buffer.slice();
    }
    protected final void ensureOpen() { if (this.closed) throw new IllegalStateException("Shader block is closed"); }
    @Override public void free() {
        if (this.closed) return;
        this.closed = true;
        if (this.buffer != null) this.buffer.close();
        this.buffer = null;
        if (this.staging != null) MemoryUtil.memFree(this.staging);
        this.staging = null; this.value = null;
    }
}
