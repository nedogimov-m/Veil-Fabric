package foundry.veil.mixin.dynamicbuffer.client;

import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.api.pipeline.CompiledRenderPipeline;
import com.mojang.renderpearl.frontend.FrontendRenderPass;
import foundry.veil.impl.client.render.dynamicbuffer.NativeDynamicBuffers;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(FrontendRenderPass.class)
public class NativeDynamicPassMixin {
    @ModifyVariable(method = "setPipeline", at = @At("HEAD"), argsOnly = true)
    private CompiledRenderPipeline veil$variant(CompiledRenderPipeline pipeline) {
        return NativeDynamicBuffers.pipeline((RenderPass) (Object) this, pipeline);
    }
    @Inject(method = "close", at = @At("RETURN"))
    private void veil$closed(CallbackInfo ci) { NativeDynamicBuffers.closed((RenderPass) (Object) this); }
}
