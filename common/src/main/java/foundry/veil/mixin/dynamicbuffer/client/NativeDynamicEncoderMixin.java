package foundry.veil.mixin.dynamicbuffer.client;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.mojang.renderpearl.api.commands.CommandEncoder;
import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.api.commands.RenderPassDescriptor;
import com.mojang.renderpearl.frontend.FrontendCommandEncoder;
import foundry.veil.impl.client.render.dynamicbuffer.NativeDynamicBuffers;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(FrontendCommandEncoder.class)
public class NativeDynamicEncoderMixin {
    @WrapMethod(method = "createRenderPass(Lcom/mojang/renderpearl/api/commands/RenderPassDescriptor;)Lcom/mojang/renderpearl/api/commands/RenderPass;")
    private RenderPass veil$attachments(RenderPassDescriptor descriptor, Operation<RenderPass> original) {
        var selection = NativeDynamicBuffers.select((CommandEncoder) (Object) this, descriptor);
        RenderPass pass = original.call(selection == null ? descriptor : selection.descriptor());
        NativeDynamicBuffers.opened(pass, selection);
        return pass;
    }
    @Inject(method = "submit", at = @At("RETURN"))
    private void veil$submitted(CallbackInfo ci) { NativeDynamicBuffers.submitted((CommandEncoder) (Object) this); }
}
