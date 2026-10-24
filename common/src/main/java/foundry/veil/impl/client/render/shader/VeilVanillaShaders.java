package foundry.veil.impl.client.render.shader;

import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.oit.OitPipelineSet;
import org.jetbrains.annotations.ApiStatus;

/** Actual native world-border definitions; Minecraft owns their compiled cache and disposal. */
@ApiStatus.Internal
public final class VeilVanillaShaders {
    private VeilVanillaShaders() { }
    public static RenderPipeline getWorldborder() { return RenderPipelines.WORLD_BORDER; }
    public static OitPipelineSet getOitWorldborder() { return RenderPipelines.OIT_WORLD_BORDER; }
}
