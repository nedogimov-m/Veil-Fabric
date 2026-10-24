package foundry.veil.impl.client.render.shader.block;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.commands.CommandEncoder;
import com.mojang.renderpearl.api.device.GpuDevice;
import java.nio.ByteBuffer;
import java.util.function.BiConsumer;

/** Fixed-size owned native uniform storage. */
public class SizedShaderBlockImpl<T> extends ShaderBlockImpl<T> {
    private final int size;
    private final BiConsumer<T, ByteBuffer> serializer;
    public SizedShaderBlockImpl(BufferBinding binding, int size, BiConsumer<T, ByteBuffer> serializer) {
        this(RenderSystem.getDevice(), binding, size, serializer);
    }
    public SizedShaderBlockImpl(GpuDevice device, BufferBinding binding, int size, BiConsumer<T, ByteBuffer> serializer) {
        super(device, binding); allocationSize(size); this.size = size; this.serializer = java.util.Objects.requireNonNull(serializer);
    }
    @Override public void prepare(CommandEncoder encoder) { this.upload(encoder, this.size, this.serializer); }
    @Override public Snapshot snapshot() { return this.snapshot(this.size, this.serializer); }
    public int getSize() { return this.size; }
}
