package foundry.veil.mixin.pipeline.client;

import foundry.veil.impl.client.render.light.VoxelShadowGrid;
import net.minecraft.client.renderer.extract.LevelExtractor;
import net.minecraft.core.BlockPos;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Block changes arrive on the extraction side in 26.3; the dirty queue is synchronized. */
@Mixin(LevelExtractor.class)
public abstract class PipelineLevelExtractorMixin {
    @Inject(method = "blockChanged", at = @At("TAIL"))
    private void veil$dirtyVoxelShadow(BlockPos pos, int flags, CallbackInfo callback) {
        VoxelShadowGrid.markBlockDirty(pos);
    }
}
