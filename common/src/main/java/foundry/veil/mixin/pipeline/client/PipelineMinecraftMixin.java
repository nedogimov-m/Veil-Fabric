package foundry.veil.mixin.pipeline.client;

import foundry.veil.api.client.render.VeilRenderSystem;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Bind native frame resources before rendering; retire frame-owned resources only after native submit. */
@Mixin(Minecraft.class)
public class PipelineMinecraftMixin {
    @Inject(method = "renderFrame", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/GameRenderer;render()V"))
    private void veil$beginNativeFrame(CallbackInfo ci) { VeilRenderSystem.beginFrame(); }
    @Inject(method = "renderFrame", at = @At(value = "INVOKE", target = "Lcom/mojang/renderpearl/api/commands/CommandEncoder;submit()V", shift = At.Shift.AFTER))
    private void veil$endSubmittedNativeFrame(CallbackInfo ci) { VeilRenderSystem.endFrame(); }
}
