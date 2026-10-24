package foundry.veil.impl.client.render.shader.block;

import net.minecraft.resources.Identifier;
import com.mojang.renderpearl.api.device.GpuDevice;
import org.jetbrains.annotations.ApiStatus;

import java.nio.ByteBuffer;
import java.util.HashSet;
import java.util.Set;
import java.util.function.BiConsumer;

@ApiStatus.Internal
public class LayoutShaderBlockImpl<T> extends SizedShaderBlockImpl<T> {

    private final Set<Identifier> referencedShaders;

    public LayoutShaderBlockImpl(BufferBinding binding, int size, BiConsumer<T, ByteBuffer> serializer) {
        super(binding, size, serializer);
        this.referencedShaders = new HashSet<>();
    }

    public LayoutShaderBlockImpl(GpuDevice device, BufferBinding binding, int size, BiConsumer<T, ByteBuffer> serializer) {
        super(device, binding, size, serializer);
        this.referencedShaders = new HashSet<>();
    }

    public Set<Identifier> getReferencedShaders() {
        return this.referencedShaders;
    }
}
