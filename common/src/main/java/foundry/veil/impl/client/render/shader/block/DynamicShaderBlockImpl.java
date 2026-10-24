package foundry.veil.impl.client.render.shader.block;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.commands.CommandEncoder;
import com.mojang.renderpearl.api.device.GpuDevice;
import foundry.veil.api.client.render.shader.block.DynamicShaderBlock;
import java.nio.ByteBuffer;
import java.util.function.BiConsumer;

/** Resizable owned native uniform storage; replacement allocates only on prepare. */
public final class DynamicShaderBlockImpl<T> extends ShaderBlockImpl<T> implements DynamicShaderBlock<T> {
    private final BiConsumer<T, ByteBuffer> serializer;
    private int size;
    public DynamicShaderBlockImpl(BufferBinding binding, int size, BiConsumer<T, ByteBuffer> serializer) {
        this(RenderSystem.getDevice(), binding, size, serializer);
    }
    public DynamicShaderBlockImpl(GpuDevice device, BufferBinding binding, int size, BiConsumer<T, ByteBuffer> serializer) {
        super(device, binding); allocationSize(size); this.size = size; this.serializer = java.util.Objects.requireNonNull(serializer);
    }
    @Override public int getSize() { this.ensureOpen(); return this.size; }
    @Override public void setSize(int size) {
        this.ensureOpen(); allocationSize(size);
        if (this.size != size) { this.size = size; this.dirty = true; }
    }
    @Override public void prepare(CommandEncoder encoder) { this.upload(encoder, this.size, this.serializer); }
    @Override public Snapshot snapshot() { return this.snapshot(this.size, this.serializer); }
}
