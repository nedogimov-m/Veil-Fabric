package foundry.veil.mixin.scene.client;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import foundry.veil.api.client.render.gpu.NativeFeatureScene;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.rendertype.RenderType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** Resolves the explicit scene lightmap while preserving the native texture/sampler preparation path. */
@Mixin(RenderType.class)
public class NativeFeatureLightmapMixin {
    @WrapOperation(method = "prepare", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/GameRenderer;lightmap()Lcom/mojang/renderpearl/api/textures/GpuTextureView;"))
    private GpuTextureView veil$sceneLightmap(GameRenderer renderer, Operation<GpuTextureView> original) {
        GpuTextureView scoped = NativeFeatureScene.scopedLightmap();
        return scoped == null ? original.call(renderer) : scoped;
    }
}
