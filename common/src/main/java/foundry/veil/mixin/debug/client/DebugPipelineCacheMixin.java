package foundry.veil.mixin.debug.client;

import com.mojang.blaze3d.pipeline.PipelineCache;
import com.mojang.renderpearl.api.device.GpuDevice;
import com.mojang.renderpearl.api.pipeline.ShaderSource;
import foundry.veil.impl.client.render.shader.debug.NativePipelineRegistry;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(PipelineCache.class)
public class DebugPipelineCacheMixin {
    @Inject(method = "<init>", at = @At("RETURN"))
    private void veil$observeCache(GpuDevice device, ShaderSource source, CallbackInfo ci) {
        NativePipelineRegistry.cacheCreated((PipelineCache) (Object) this);
    }
}
