package foundry.veil.porttest.mixin;

import foundry.veil.porttest.GpuSkeletalAcceptance;
import net.minecraft.client.renderer.entity.EntityRenderers;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import java.util.Map;

@Mixin(EntityRenderers.class)
public class NativeSkeletalContextProbeMixin {
    @Inject(method = "createEntityRenderers", at = @At("HEAD"))
    private static void veil$context(EntityRendererProvider.Context context, CallbackInfoReturnable<Map<?, ?>> result) {
        GpuSkeletalAcceptance.rendererContext(context);
    }
}
