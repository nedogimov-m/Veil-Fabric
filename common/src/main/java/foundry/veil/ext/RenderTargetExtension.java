package foundry.veil.ext;

import com.mojang.renderpearl.api.textures.GpuTextureView;
import foundry.veil.api.client.render.gpu.GpuFramebuffer;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.Nullable;

/** The installed framebuffer remains caller-owned; native storage is restored when it is removed. */
@ApiStatus.Internal
public interface RenderTargetExtension {
    void veil$setWrapper(@Nullable GpuFramebuffer framebuffer);
    @Nullable GpuFramebuffer veil$getWrapper();
    @Nullable GpuTextureView veil$getTexture(int index);
}
