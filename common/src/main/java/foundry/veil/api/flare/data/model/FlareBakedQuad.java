package foundry.veil.api.flare.data.model;

import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.renderpearl.api.vertex.VertexFormat;
import net.minecraft.util.LightCoordsUtil;
import net.minecraft.client.renderer.texture.OverlayTexture;

/**
 * @since 2.5.0
 */
public class FlareBakedQuad {
    private final float[] vertexData;
    
    public FlareBakedQuad(float[] vertexData) {
        if (vertexData.length != 32) throw new IllegalArgumentException("A shell quad requires four position/normal/UV vertices");
        this.vertexData = vertexData.clone();
    }
    
    public void putBakedQuadInto(VertexConsumer buffer) {
        this.putBakedQuadInto(buffer, 1.0F, 1.0F, 1.0F, 1.0F, LightCoordsUtil.FULL_BRIGHT, OverlayTexture.NO_OVERLAY);
    }

    public float[] vertexData() { return this.vertexData.clone(); }

    /** Emit only attributes actually present in the caller's native vertex layout. */
    public void putBakedQuadInto(VertexConsumer buffer, VertexFormat format) {
        for (int i = 0; i < 4; i++) {
            int j = i * 8;
            buffer.addVertex(this.vertexData[j], this.vertexData[j + 1], this.vertexData[j + 2]);
            if (format.contains("Normal")) buffer.setNormal(this.vertexData[j + 3], this.vertexData[j + 4], this.vertexData[j + 5]);
            if (format.contains("UV0")) buffer.setUv(this.vertexData[j + 6], this.vertexData[j + 7]);
            if (format.contains("Color")) buffer.setColor(-1);
            if (format.contains("UV2")) buffer.setLight(LightCoordsUtil.FULL_BRIGHT);
            if (format.contains("UV1")) buffer.setOverlay(OverlayTexture.NO_OVERLAY);
        }
    }
    
    public void putBakedQuadInto(VertexConsumer buffer, float red, float green, float blue, float alpha, int packedLight, int packedOverlay) {
        float[] vertexData = this.vertexData;
        
        for (int i = 0; i < 4; i++) {
            int j = i * 8;
            buffer.addVertex(vertexData[j], vertexData[j + 1], vertexData[j + 2])
                    .setNormal(vertexData[j + 3], vertexData[j + 4], vertexData[j + 5])
                    .setUv(vertexData[j + 6], vertexData[j + 7])
                    .setColor(red, green, blue, alpha)
                    .setLight(packedLight)
                    .setOverlay(packedOverlay);
            
        }
    }
    
    public void putBakedQuadInto(VertexConsumer buffer, float red, float green, float blue, float alpha, int[] lightmap, int packedOverlay) {
        float[] vertexData = this.vertexData;
        
        for (int i = 0; i < 4; i++) {
            int j = i * 8;
            buffer.addVertex(vertexData[j], vertexData[j + 1], vertexData[j + 2])
                    .setNormal(vertexData[j + 3], vertexData[j + 4], vertexData[j + 5])
                    .setUv(vertexData[j + 6], vertexData[j + 7])
                    .setColor(red, green, blue, alpha)
                    .setLight(lightmap[i])
                    .setOverlay(packedOverlay);
        }
    }
    
}
