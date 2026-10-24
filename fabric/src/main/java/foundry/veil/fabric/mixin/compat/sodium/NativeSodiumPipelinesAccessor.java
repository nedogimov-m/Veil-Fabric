package foundry.veil.fabric.mixin.compat.sodium;

import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import net.caffeinemc.mods.sodium.client.render.chunk.ShaderChunkRenderer;
import net.caffeinemc.mods.sodium.client.render.chunk.terrain.TerrainRenderPass;
import net.minecraft.client.renderer.oit.OitPipelineSet;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import java.util.Map;

@Mixin(value=ShaderChunkRenderer.class,remap=false)
public interface NativeSodiumPipelinesAccessor {
    @Accessor("programs") static Map<TerrainRenderPass,RenderPipeline> veil$programs(){throw new AssertionError("Native Sodium accessor not transformed");}
    @Accessor("oitPrograms") static Map<TerrainRenderPass,OitPipelineSet> veil$oitPrograms(){throw new AssertionError("Native Sodium accessor not transformed");}
}
