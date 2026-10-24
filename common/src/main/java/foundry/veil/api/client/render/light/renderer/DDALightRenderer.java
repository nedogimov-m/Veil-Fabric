package foundry.veil.api.client.render.light.renderer;

import com.mojang.renderpearl.api.textures.GpuTextureView;
import com.mojang.renderpearl.api.textures.GpuSampler;
import foundry.veil.api.client.render.gpu.GpuShaderProgram;
import foundry.veil.api.client.render.light.DDALightData;
import foundry.veil.api.client.render.light.data.LightData;
import org.joml.Vector3fc;

/** Typed nearest-sampled flattened voxel grid; DDA still traverses the original 64 cubed cells. */
public interface DDALightRenderer<T extends LightData & DDALightData> extends LightTypeRenderer<T> {
    void uploadVoxelGridUniforms(GpuTextureView grid, GpuSampler sampler, Vector3fc origin);
    default boolean hasOccludedLights() {
        if (this.getVisibleLights() == 0) return false;
        for (var light : this.getLights()) if (light.getLightData().isOcclusionEnabled()) return true;
        return false;
    }
    static void uploadVoxelGridUniforms(GpuShaderProgram program, GpuTextureView grid, GpuSampler sampler, Vector3fc origin) {
        if (program.hasUniform("BlockGrid")) program.setTexture("BlockGrid", grid, sampler);
        program.getUniformSafe("GridOrigin").setVector(origin);
    }
}
