package foundry.veil.impl.client.render.light;

import com.mojang.blaze3d.vertex.VertexConsumer;
import foundry.veil.api.client.render.gpu.NativeLightInstances;
import com.mojang.renderpearl.api.vertex.VertexFormat;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import com.mojang.renderpearl.api.textures.GpuSampler;
import foundry.veil.Veil;
import foundry.veil.api.client.render.light.data.PointLightData;
import foundry.veil.api.client.render.light.renderer.*;
import net.minecraft.resources.Identifier;
import org.joml.Vector3fc;

public final class InstancedPointLightRenderer extends InstancedLightRenderer<PointLightData> implements DDALightRenderer<PointLightData> {
    public static final VertexFormat INSTANCES = NativeLightInstances.POINT;
    public InstancedPointLightRenderer() { super(32); }
    @Override protected void createMesh(VertexConsumer builder) { LightTypeRenderer.createInvertedCube(builder); }
    @Override protected VertexFormat instanceFormat() { return INSTANCES; }
    @Override protected Identifier shader() { return Veil.veilPath("light/point"); }
    @Override public void uploadVoxelGridUniforms(GpuTextureView grid, GpuSampler sampler, Vector3fc origin) { this.setVoxelGrid(grid, sampler, origin); }
}
