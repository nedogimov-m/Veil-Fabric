package foundry.veil.api.flare.data.effect;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import foundry.veil.api.client.property.model.Mat4ModelProperty;
import foundry.veil.api.client.property.model.RotationModelProperty;
import foundry.veil.api.client.property.model.Vec3ModelProperty;
import foundry.veil.api.client.render.MatrixStack;
import foundry.veil.api.client.render.rendertype.VeilRenderType;
import foundry.veil.impl.client.render.rendertype.NativeRenderTypeDraws;
import foundry.veil.api.flare.EffectHost;
import foundry.veil.api.flare.FlareEffectManager;
import foundry.veil.api.flare.model.BakedShell;
import foundry.veil.api.flare.modifier.PropertyModifier;
import foundry.veil.api.util.CodecUtil;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.resources.Identifier;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import org.joml.*;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * @since 2.5.0
 */
public final class FlareModel {

    public static final Codec<FlareModel> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            Identifier.CODEC.fieldOf("path").forGetter(FlareModel::getShell),
            CodecUtil.VECTOR3FC_CODEC.fieldOf("positionOffset").forGetter(FlareModel::getPositionOffset),
            CodecUtil.VECTOR3FC_CODEC.fieldOf("rotationOffset").forGetter(FlareModel::getRotationOffset),
            CodecUtil.VECTOR3FC_CODEC.fieldOf("scaleOffset").forGetter(FlareModel::getScaleOffset),
            CodecUtil.singleOrList(FlareMaterial.CODEC).fieldOf("materials").forGetter(model -> List.of(model.getMaterials()))
    ).apply(instance, FlareModel::new));

    public static final String POSITION_PROPERTY_NAME = "model::position";
    public static final String ROTATION_PROPERTY_NAME = "model::rotation";
    public static final String SCALE_PROPERTY_NAME = "model::scale";

    private final Identifier shell;
    final Vec3ModelProperty positionOffset;
    final RotationModelProperty rotationOffset;
    final Vec3ModelProperty scaleOffset;
    final Mat4ModelProperty modelToWorld;
    private final FlareMaterial[] materials;

    public FlareModel(Identifier shell, Vector3fc position, Vector3fc rotation, Vector3fc scale, Collection<FlareMaterial> materials) {
        this.shell = shell;
        this.positionOffset = new Vec3ModelProperty(new Vector3f(position));
        this.rotationOffset = new RotationModelProperty(new Vector3f(rotation));
        this.scaleOffset = new Vec3ModelProperty(new Vector3f(scale));
        this.modelToWorld = new Mat4ModelProperty(new Matrix4f());
        this.materials = materials.toArray(FlareMaterial[]::new);
    }

    public void render(EffectHost host, MatrixStack matrixStack, Map<String, List<PropertyModifier<?>>> modifiers, @Nullable Map<Identifier, BakedShell> shellOverrides) {
        Vector3fc positionOffset = this.positionOffset.getValue();
        Quaternionfc rotationOffset = this.rotationOffset.getRotation();
        Vector3fc scaleOffset = this.scaleOffset.getValue();

        Matrix4f modelToLocal = new Matrix4f(matrixStack.position())
                .translate(positionOffset).rotate(rotationOffset).scale(scaleOffset);
        BakedShell bakedShell = shellOverrides != null && shellOverrides.containsKey(this.shell)
                ? shellOverrides.get(this.shell) : FlareEffectManager.getInstance().getBakedShell(this.shell);
        if (bakedShell.getQuads().isEmpty()) return;
        Vec3 cameraPos = foundry.veil.impl.client.render.shader.injection.NativeShaderViews.currentView()
                .map(view -> new Vec3(view.x(), view.y(), view.z()))
                .orElseGet(() -> Minecraft.getInstance().gameRenderer.mainCamera().position());
        this.modelToWorld.modify(new Matrix4f().translation((float) cameraPos.x, (float) cameraPos.y, (float) cameraPos.z).mul(modelToLocal),
                PropertyModifier.PropertyModifierMode.REPLACE, Optional.empty());
        Matrix4f modelView = RenderSystem.getModelViewMatrixCopy().mul(modelToLocal);
        for (FlareMaterial material : this.materials) {
            RenderType renderType = VeilRenderType.get(material.renderTypeLocation());
            while (renderType instanceof VeilRenderType.RenderTypeWrapper wrapper) renderType = wrapper.get();
            if (renderType == null) continue;
            if (renderType.primitiveTopology() != com.mojang.renderpearl.api.pipeline.PrimitiveTopology.QUADS)
                throw new IllegalArgumentException("Flare shells require a native quad render type");
            var mesh = bakedShell.getVertexArray(renderType.format());
            NativeRenderTypeDraws.captureMesh(renderType, mesh, modelView, shader -> {
                try { material.applyProperties(host, shader, modifiers); }
                finally { material.resetProperties(host, shader); }
            });
        }
    }

    public Identifier getShell() {
        return this.shell;
    }

    public Vector3fc getPositionOffset() {
        return this.positionOffset.getValue();
    }

    public Vector3fc getRotationOffset() {
        return this.rotationOffset.getValue();
    }

    public Vector3fc getScaleOffset() {
        return this.scaleOffset.getValue();
    }

    public FlareMaterial[] getMaterials() {
        return this.materials;
    }
}
