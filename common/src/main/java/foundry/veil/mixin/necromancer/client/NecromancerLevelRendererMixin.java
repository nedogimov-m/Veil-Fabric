package foundry.veil.mixin.necromancer.client;

import foundry.veil.api.client.necromancer.SkeletonParent;
import foundry.veil.api.client.necromancer.animation.Animator;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;


@Mixin(ClientLevel.class)
public class NecromancerLevelRendererMixin {

    @SuppressWarnings("rawtypes")
    @Inject(method = "tick", at = @At("TAIL"))
    private void veil$levelRenderTick(CallbackInfo ci) {
        for (Entity entity : ((ClientLevel) (Object) this).entitiesForRendering()) {
            if (entity instanceof SkeletonParent parent) {
                Animator animator = parent.getAnimator();
                if (animator != null) {
                    animator.tick();
                }
            }
        }
    }
}
