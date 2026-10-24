package foundry.veil.api.client.render.rendertype;

import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import net.minecraft.client.renderer.rendertype.RenderSetup;
import net.minecraft.resources.Identifier;
import org.jetbrains.annotations.Nullable;

/** Native setup and resource ownership, without opaque global-state callbacks. */
public interface VeilRenderTypeAccessor {
    RenderSetup renderSetup();
    RenderPipeline pipeline();
    @Nullable Identifier veilShaderId();
    @Nullable Identifier outputState();
}
