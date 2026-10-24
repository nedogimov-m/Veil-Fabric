package foundry.veil.fabric.mixin.compat.sodium;

import foundry.veil.ext.sodium.ChunkVertexEncoderVertexExtension;
import net.caffeinemc.mods.sodium.api.util.NormI8;
import net.caffeinemc.mods.sodium.client.model.quad.ModelQuadView;
import net.caffeinemc.mods.sodium.client.model.quad.properties.ModelQuadFacing;
import net.caffeinemc.mods.sodium.client.render.chunk.compile.buffers.ChunkModelBuilder;
import net.caffeinemc.mods.sodium.client.render.chunk.compile.pipeline.DefaultFluidRenderer;
import net.caffeinemc.mods.sodium.client.render.chunk.terrain.material.Material;
import net.caffeinemc.mods.sodium.client.render.chunk.translucent_sorting.TranslucentGeometryCollector;
import net.caffeinemc.mods.sodium.client.render.chunk.vertex.format.ChunkVertexEncoder;
import net.minecraft.core.BlockPos;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value=DefaultFluidRenderer.class,remap=false)
public class DefaultFluidRendererMixin {
    @Shadow @Final private ChunkVertexEncoder.Vertex[] vertices;
    @Inject(method="writeQuad",at=@At("HEAD"))
    private void veil$captureNormals(ChunkModelBuilder builder,TranslucentGeometryCollector collector,Material material,BlockPos offset,
                                    ModelQuadView quad,ModelQuadFacing facing,boolean flip,CallbackInfo ci){
        for(int i=0;i<4;i++){
            int normal=quad.getAccurateNormal(i);if(flip)normal=NormI8.flipPacked(normal);
            ((ChunkVertexEncoderVertexExtension)this.vertices[flip?(4-i)&3:i]).veil$setNormal(normal);
        }
    }
}
