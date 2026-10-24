package foundry.veil.api.quasar.emitters;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import foundry.veil.api.quasar.emitters.shape.EmitterShape;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3f;
import org.joml.Vector4f;

import java.util.List;

public class AnchorPoint {
    public static AnchorPoint TEST_POINT = new AnchorPoint(Identifier.fromNamespaceAndPath("quasar", "test_point"));
    private final Identifier id;
    public Vector3f localOffset = new Vector3f(0, 0, 0);
    public Vector3f worldOffset = new Vector3f(0, 0, 0);
    public List<ModelPart> modelParts = null;
    public Vector3f origin = new Vector3f(0, 0, 0);
    public Vector4f transformMatrix = new Vector4f();

    public AnchorPoint(Identifier id) {
        this.id = id;
    }

    public Identifier getId() {
        return id;
    }

    public Vector3f getLocalOffset() {
        return localOffset;
    }

    public Vec3 getWorldOffset(Entity entity) {
        if (entity == null) {
            return Vec3.ZERO;
        }
        Vec3 pos = entity.position();
        pos = pos.add(transformMatrix.x(), transformMatrix.y(), transformMatrix.z());
        return pos;
    }

    public void updatePosition(Entity entity) {
        if (this.modelParts == null) {
            return;
        }
        PoseStack stack = new PoseStack();
        stack.scale(1f, -1f, -1f);
        stack.translate(0, -1.501F, 0);
        for (ModelPart modelPart : modelParts) {
            modelPart.translateAndRotate(stack);
        }
        Vector4f offset = new Vector4f(localOffset.x(), localOffset.y(), localOffset.z(), 1);
//        stack.mulPose(Vector3f.YP.rotationDegrees(entity.getYRot()));
        offset = stack.last().pose().transform(offset);
        transformMatrix = offset;
    }

    public void render(PoseStack stack, SubmitNodeCollector submissions, float scalar) {
        stack.pushPose();
        try {
            stack.translate(transformMatrix.x(), transformMatrix.y(), transformMatrix.z());
            stack.scale(1 / 16f, 1 / 16f, 1 / 16f);
            submissions.submitCustomGeometry(stack, RenderTypes.lines(), (pose, consumer) ->
                    EmitterShape.renderBox(pose, consumer, new AABB(-0.5, -0.5, -0.5, 0.5, 0.5, 0.5), 1, 1, 1, 1));
        } finally { stack.popPose(); }
    }
}
