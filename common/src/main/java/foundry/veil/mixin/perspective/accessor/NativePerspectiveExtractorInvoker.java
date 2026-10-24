package foundry.veil.mixin.perspective.accessor;

import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.client.renderer.extract.LevelExtractor;
import net.minecraft.client.renderer.state.level.LevelRenderState;
import net.minecraft.client.renderer.state.level.PlayerRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;

/** Reuses native view extraction without consuming the main world's section/chunk update queues. */
@Mixin(LevelExtractor.class)
public interface NativePerspectiveExtractorInvoker {
    @Accessor("level") void veil$perspectiveLevel(ClientLevel level);
    @Invoker("extractVisibleEntities") void veil$perspectiveEntities(Camera camera, Frustum frustum, DeltaTracker delta, LevelRenderState state);
    @Invoker("extractVisibleBlockEntities") void veil$perspectiveBlockEntities(Camera camera, float partial, LevelRenderState state);
    @Invoker("extractBlockDestroyAnimation") void veil$perspectiveDestruction(Camera camera, LevelRenderState state);
    @Invoker("extractPlayerState") void veil$perspectivePlayer(Camera camera, DeltaTracker delta, float partial, PlayerRenderState state);
}
