package foundry.veil.mixin.shader.client;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.mojang.renderpearl.api.commands.CommandEncoder;
import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.api.commands.RenderPassDescriptor;
import com.mojang.renderpearl.frontend.FrontendCommandEncoder;
import foundry.veil.api.client.render.shader.NativePipelineBridge;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Associates real native passes with their actual shared encoder, retaining replacement frame resources until submission. */
@Mixin(FrontendCommandEncoder.class)
public class NativeShaderEncoderMixin {
    @WrapMethod(method = "createRenderPass(Lcom/mojang/renderpearl/api/commands/RenderPassDescriptor;)Lcom/mojang/renderpearl/api/commands/RenderPass;")
    private RenderPass veil$owner(RenderPassDescriptor descriptor, Operation<RenderPass> original) {
        RenderPass pass = original.call(descriptor);
        NativePipelineBridge.opened(pass, (CommandEncoder) (Object) this);
        return pass;
    }
    @Inject(method = "submit", at = @At("RETURN"))
    private void veil$submitted(CallbackInfo ci) { NativePipelineBridge.submitted((CommandEncoder) (Object) this); }
}
