package foundry.veil.impl.client.render.light;

import com.mojang.blaze3d.vertex.VertexConsumer;
import foundry.veil.api.client.render.gpu.NativeLightInstances;
import com.mojang.renderpearl.api.vertex.VertexFormat;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import com.mojang.renderpearl.api.textures.GpuSampler;
import foundry.veil.Veil;
import foundry.veil.api.client.render.light.data.AreaLightData;
import foundry.veil.api.client.render.light.renderer.*;
import net.minecraft.resources.Identifier;
import org.joml.Vector3fc;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

public final class AreaLightRenderer extends InstancedLightRenderer<AreaLightData> implements DDALightRenderer<AreaLightData> {
    public static final VertexFormat INSTANCES = NativeLightInstances.AREA;
    private final ByteBuffer packed = ByteBuffer.allocateDirect(94).order(ByteOrder.nativeOrder());
    public AreaLightRenderer() { super(96); }
    @Override protected void createMesh(VertexConsumer builder) { LightTypeRenderer.createInvertedCube(builder); }
    @Override protected VertexFormat instanceFormat() { return INSTANCES; }
    @Override protected Identifier shader() { return Veil.veilPath("light/area"); }
    @Override protected void storeInstance(AreaLightData light, ByteBuffer output) {
        this.packed.clear(); light.store(this.packed);
        this.packed.flip(); NativeLightInstances.expandArea(this.packed, output);
    }
    @Override public void uploadVoxelGridUniforms(GpuTextureView grid, GpuSampler sampler, Vector3fc origin) { this.setVoxelGrid(grid, sampler, origin); }
}
