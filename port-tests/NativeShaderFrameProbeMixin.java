package foundry.veil.porttest.mixin;

import foundry.veil.porttest.GpuShaderFrameAcceptance;

import net.minecraft.client.renderer.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Test-only replacement content inside the genuine GameRenderer.render frame scope. */
@Mixin(GameRenderer.class)
public class NativeShaderFrameProbeMixin {
    @Inject(method="render",at=@At("HEAD"),cancellable=true)
    private void veil$probe(CallbackInfo callback) {
        if(GpuShaderFrameAcceptance.probe()){callback.cancel();}
    }
}
