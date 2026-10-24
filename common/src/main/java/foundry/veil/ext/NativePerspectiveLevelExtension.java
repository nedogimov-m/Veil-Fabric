package foundry.veil.ext;

import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.RenderBuffers;
import net.minecraft.client.renderer.feature.FeatureRenderDispatcher;
import net.minecraft.client.renderer.state.GameRenderState;

/** Independent native world renderer borrowing the loaded terrain owner's compiled storage. */
public interface NativePerspectiveLevelExtension {
    /** Optional terrain backends must explicitly advertise an unavailable independent resident graph. */
    default String veil$unsupportedPerspectiveBackend() { return null; }
    void veil$configurePerspective(GameRenderState state, RenderBuffers buffers, FeatureRenderDispatcher features);
    void veil$borrowPerspectiveTerrain(LevelRenderer source);
    void veil$detachPerspectiveTerrain();
}
