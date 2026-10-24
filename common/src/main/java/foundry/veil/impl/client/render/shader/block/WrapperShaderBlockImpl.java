package foundry.veil.impl.client.render.shader.block;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import com.mojang.renderpearl.api.commands.CommandEncoder;
import foundry.veil.api.client.render.shader.block.DynamicShaderBlock;
import org.jetbrains.annotations.Nullable;

/** Non-owning native buffer slice, suitable for externally uploaded animation data. */
public final class WrapperShaderBlockImpl implements DynamicShaderBlock<Object> {
    private final BufferBinding binding;
    private final GpuBufferSlice backing;
    private int size;
    private boolean closed;
    public WrapperShaderBlockImpl(BufferBinding binding, GpuBufferSlice backing) {
        if (binding != BufferBinding.UNIFORM) throw new UnsupportedOperationException("Writable storage buffers require a native algorithm migration");
        if ((backing.buffer().usage() & com.mojang.renderpearl.api.buffers.GpuBuffer.USAGE_UNIFORM) == 0) throw new IllegalArgumentException("Borrowed buffer is not uniform storage");
        this.binding = binding; this.backing = backing; this.size = Math.toIntExact(backing.length());
    }
    private void ensureOpen() { if (this.closed || this.backing.buffer().isClosed()) throw new IllegalStateException("Borrowed shader block is closed"); }
    @Override public void set(@Nullable Object value) { throw new UnsupportedOperationException("Borrowed buffer data is uploaded by its owner"); }
    @Override public @Nullable Object getValue() { throw new UnsupportedOperationException("Borrowed buffer has no Java object value"); }
    @Override public int getSize() { this.ensureOpen(); return this.size; }
    @Override public void setSize(int size) {
        this.ensureOpen();
        if (size <= 0 || size > this.backing.length()) throw new IllegalArgumentException("Borrowed block size exceeds its backing slice");
        this.size = size;
    }
    @Override public void prepare(CommandEncoder encoder) { this.ensureOpen(); }
    @Override public Snapshot snapshot() { return Snapshot.borrowed(this.slice()); }
    @Override public GpuBufferSlice slice() { this.ensureOpen(); return this.backing.buffer().slice(this.backing.offset(), this.size); }
    @Override public BufferBinding getBinding() { return this.binding; }
    @Override public void free() { this.closed = true; }
}
