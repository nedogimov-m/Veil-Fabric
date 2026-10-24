package foundry.veil.fabric.mixin.compat.sodium;

import foundry.veil.ext.sodium.ChunkVertexEncoderVertexExtension;
import net.caffeinemc.mods.sodium.client.render.chunk.compile.pipeline.BlockRenderer;
import net.caffeinemc.mods.sodium.client.render.chunk.terrain.material.Material;
import net.caffeinemc.mods.sodium.client.render.chunk.vertex.format.ChunkVertexEncoder;
import net.caffeinemc.mods.sodium.client.render.model.MutableQuadViewImpl;
import net.caffeinemc.mods.sodium.client.model.quad.properties.ModelQuadOrientation;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value=BlockRenderer.class,remap=false)
public class BlockRendererMixin {
    @Shadow @Final private ChunkVertexEncoder.Vertex[] vertices;
    @Inject(method="bufferQuad",at=@At("HEAD"))
    private void veil$captureNormals(MutableQuadViewImpl quad,float[] brightness,Material material,CallbackInfo ci){
        for(int i=0;i<4;i++)((ChunkVertexEncoderVertexExtension)this.vertices[i]).veil$setNormal(quad.getAccurateNormal(ModelQuadOrientation.NORMAL.getVertexIndex(i)));
    }
}
