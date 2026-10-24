package foundry.veil.mixin.dynamicbuffer.client;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.mojang.blaze3d.resource.GraphicsResourceAllocator;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import foundry.veil.impl.client.render.dynamicbuffer.NativeDynamicBuffers;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.LevelTargetBundle;
import net.minecraft.client.renderer.chunk.ChunkSectionsToRender;
import net.minecraft.client.renderer.feature.FeatureRenderDispatcher;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import org.joml.Vector4f;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(LevelRenderer.class)
public abstract class NativeDynamicLevelRendererMixin {
    @Shadow @Final private GameRenderer gameRenderer;
    @Shadow @Final private LevelTargetBundle targets;
    @WrapMethod(method = "render")
    private void veil$world(GraphicsResourceAllocator allocator, boolean outlines, CameraRenderState camera,
                            GpuBufferSlice fog, Vector4f fogColor, boolean renderSky, boolean renderClouds,
                            Operation<Void> original) {
        var target = this.gameRenderer.mainRenderTarget();
        try (var world = NativeDynamicBuffers.beginWorld(target.getColorTextureView(), target.getDepthTextureView())) {
            original.call(allocator, outlines, camera, fog, fogColor, renderSky, renderClouds);
        }
    }
    @Inject(method = "executeOit", at = @At("RETURN"))
    private void veil$compose(ChunkSectionsToRender chunks, FeatureRenderDispatcher.PreparedFrame features, CallbackInfo ci) {
        NativeDynamicBuffers.composeOit(this.targets.accumulate.get().getColorTextureView(),
                this.targets.depthBounds.get().getColorTextureView(), this.targets.transmittance.stream()
                        .map(handle -> handle.get().getColorTextureView()).toArray(com.mojang.renderpearl.api.textures.GpuTextureView[]::new));
    }
}
