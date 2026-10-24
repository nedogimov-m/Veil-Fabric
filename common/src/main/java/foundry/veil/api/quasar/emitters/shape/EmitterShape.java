package foundry.veil.api.quasar.emitters.shape;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.serialization.Codec;
import foundry.veil.api.quasar.registry.EmitterShapeRegistry;
import foundry.veil.api.util.CodecUtil;
import net.minecraft.util.RandomSource;
import org.joml.Vector3d;
import org.joml.Vector3dc;
import org.joml.Vector3fc;
import net.minecraft.world.phys.AABB;

public interface EmitterShape {

    Vector3d getPoint(RandomSource randomSource, Vector3fc dimensions, Vector3fc rotation, Vector3dc position, boolean fromSurface);

    void renderShape(PoseStack stack, VertexConsumer consumer, Vector3fc dimensions, Vector3fc rotation);

    /** Emit the twelve actual native line edges, including the current line-width attribute. */
    static void renderBox(PoseStack.Pose pose, VertexConsumer consumer, AABB box, float red, float green, float blue, float alpha) {
        for (int axis = 0; axis < 3; axis++) {
            for (int side = 0; side < 4; side++) {
                float[] first = {(float) box.minX, (float) box.minY, (float) box.minZ};
                float[] last = {(float) box.maxX, (float) box.maxY, (float) box.maxZ};
                float[] start = first.clone(), end = first.clone(); end[axis] = last[axis];
                int bit = 0;
                for (int other = 0; other < 3; other++) if (other != axis) {
                    float value = (side & 1 << bit++) == 0 ? first[other] : last[other]; start[other] = value; end[other] = value;
                }
                float nx = axis == 0 ? 1 : 0, ny = axis == 1 ? 1 : 0, nz = axis == 2 ? 1 : 0;
                consumer.addVertex(pose, start[0], start[1], start[2]).setColor(red, green, blue, alpha).setNormal(pose, nx, ny, nz).setLineWidth(1);
                consumer.addVertex(pose, end[0], end[1], end[2]).setColor(red, green, blue, alpha).setNormal(pose, nx, ny, nz).setLineWidth(1);
            }
        }
    }

    Codec<EmitterShape> CODEC = CodecUtil.registryOrLegacyCodec(EmitterShapeRegistry.REGISTRY);
}
