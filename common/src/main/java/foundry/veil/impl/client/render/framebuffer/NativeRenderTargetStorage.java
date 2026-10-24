package foundry.veil.impl.client.render.framebuffer;

import com.mojang.renderpearl.api.textures.GpuTexture;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import org.jetbrains.annotations.Nullable;

/** Exact native owner state retained while a separately owned framebuffer is installed. */
public record NativeRenderTargetStorage(int width,int height,@Nullable GpuTexture color,
        @Nullable GpuTextureView colorView,@Nullable GpuTexture depth,@Nullable GpuTextureView depthView) { }
