package foundry.veil.mixin.pipeline.client;

import foundry.veil.api.client.render.CullFrustum;
import foundry.veil.api.client.render.VeilRenderBridge;
import foundry.veil.api.client.render.VeilRenderSystem;
import foundry.veil.api.compat.SodiumCompat;
import foundry.veil.ext.LevelRendererExtension;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.state.level.LevelRenderState;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Extracted camera and compiled terrain belong to the native level renderer. */
@Mixin(value = LevelRenderer.class, priority = 800)
public abstract class PipelineLevelRendererMixin implements LevelRendererExtension {
    @Shadow @Final private LevelRenderState levelRenderState;

    @Inject(method = "resetLevelRenderData", at = @At("HEAD"))
    private void veil$clearLevel(CallbackInfo callback) {
        VeilRenderSystem.clearLevel();
    }

    @Override
    public CullFrustum veil$getCullFrustum() {
        return VeilRenderBridge.create(this.levelRenderState.cameraRenderState.cullFrustum);
    }

    @Override
    public void veil$markChunksDirty() {
        if (SodiumCompat.INSTANCE != null) {
            SodiumCompat.INSTANCE.markChunksDirty();
        } else {
            VeilRenderSystem.rebuildChunks();
        }
    }
}
