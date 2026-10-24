package foundry.veil.porttest.mixin;

import foundry.veil.porttest.GpuLevelPerspectiveAcceptance;
import net.minecraft.client.renderer.LevelRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(LevelRenderer.class)
public class NativePerspectiveOitProbeMixin {
    @Inject(method = "executeOit", at = @At("HEAD"))
    private void veil$oit(CallbackInfo ci) { GpuLevelPerspectiveAcceptance.oit((LevelRenderer) (Object) this); }
}
