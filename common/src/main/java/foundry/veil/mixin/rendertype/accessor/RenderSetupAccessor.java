package foundry.veil.mixin.rendertype.accessor;

import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import net.minecraft.client.renderer.oit.OitPipelineSet;
import net.minecraft.client.renderer.rendertype.*;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import java.util.Map;

@Mixin(RenderSetup.class)
public interface RenderSetupAccessor {
    @Accessor("pipeline") RenderPipeline veil$getPipeline();
    @Accessor("oitPipelineSet") OitPipelineSet veil$getOitPipelineSet();
    @Accessor("textures") Map<String, Object> veil$getTextures();
    @Accessor("textureTransform") TextureTransform veil$getTextureTransform();
    @Accessor("layeringTransform") LayeringTransform veil$getLayeringTransform();
    @Accessor("outlineProperty") RenderSetup.OutlineProperty veil$getOutlineProperty();
    @Accessor("outlineTextureName") String veil$getOutlineTextureName();
    @Accessor("useLightmap") boolean veil$useLightmap();
    @Accessor("useOverlay") boolean veil$useOverlay();
    @Accessor("affectsCrumbling") boolean veil$affectsCrumbling();
    @Accessor("sortOnUpload") boolean veil$sortOnUpload();
    @Accessor("forceSolidModelPhase") boolean veil$forceSolidModelPhase();
}
