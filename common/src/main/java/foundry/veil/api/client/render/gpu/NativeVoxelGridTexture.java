package foundry.veil.api.client.render.gpu;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.commands.CommandEncoder;
import com.mojang.renderpearl.api.device.GpuDevice;
import com.mojang.renderpearl.api.textures.*;
import java.nio.ByteBuffer;
import java.util.OptionalDouble;

/** Exact 64 cubed R8 cells, stored linearly in a genuine 512 square native texture. */
public final class NativeVoxelGridTexture implements AutoCloseable {
    public static final int GRID_SIZE = 64;
    public static final int SIDE = 512;
    private final GpuTexture texture;
    private final GpuTextureView view;
    private final GpuSampler sampler;
    public NativeVoxelGridTexture(GpuDevice device) {
        this.texture = device.createTexture(() -> "Veil voxel occupancy", GpuTexture.USAGE_TEXTURE_BINDING | GpuTexture.USAGE_COPY_DST,
                GpuFormat.R8_UNORM, SIDE, SIDE, 1, 1);
        try {
            this.view = device.createTextureView(this.texture);
            try { this.sampler = device.createSampler(AddressMode.CLAMP_TO_EDGE, AddressMode.CLAMP_TO_EDGE,
                    FilterMode.NEAREST, FilterMode.NEAREST, 1, OptionalDouble.empty()); }
            catch (RuntimeException | Error failure) { this.view.close(); throw failure; }
        } catch (RuntimeException | Error failure) { this.texture.close(); throw failure; }
    }
    public GpuTextureView view() { return this.view; }
    public GpuSampler sampler() { return this.sampler; }
    public void upload(CommandEncoder encoder, ByteBuffer cells) {
        if (cells.remaining() != SIDE * SIDE) throw new IllegalArgumentException("Voxel grid must contain exactly 64 cubed cells");
        try (var image = new NativeImage(NativeImage.Format.LUMINANCE, SIDE, SIDE, false)) {
            image.getPixelBytes().put(cells.duplicate());
            encoder.writeToTexture(this.texture, image);
        }
    }
    @Override public void close() { this.sampler.close(); this.view.close(); this.texture.close(); }
}
