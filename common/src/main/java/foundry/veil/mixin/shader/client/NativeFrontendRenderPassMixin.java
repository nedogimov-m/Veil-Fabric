package foundry.veil.mixin.shader.client;

import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.api.pipeline.CompiledRenderPipeline;
import com.mojang.renderpearl.frontend.FrontendRenderPass;
import foundry.veil.api.client.render.shader.NativePipelineBridge;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(FrontendRenderPass.class)
public class NativeFrontendRenderPassMixin {
    @Inject(method = "setPipeline", at = @At("TAIL"))
    private void veil$bindResources(CompiledRenderPipeline pipeline, CallbackInfo ci) {
        NativePipelineBridge.bind((RenderPass) (Object) this, pipeline);
    }
    @Inject(method = "close", at = @At("RETURN"))
    private void veil$closed(CallbackInfo ci) { NativePipelineBridge.closed((RenderPass) (Object) this); }
    @Inject(method = "validateDraw", at = @At("HEAD"))
    private void veil$replacementResources(java.util.Collection<String> uniforms, boolean indexed, boolean indirect, CallbackInfo ci) {
        NativePipelineBridge.beforeDraw((RenderPass) (Object) this);
    }
}
