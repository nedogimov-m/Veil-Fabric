package foundry.veil.api.client.util;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import foundry.veil.api.client.render.gpu.NativeVertexSnapshot;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.joml.Vector3f;

/** Captures an arbitrary view frustum as detached native line geometry. */
public final class DebugFrustumRenderer {
    private DebugFrustumRenderer() { }
    public static void renderFrustum(SubmitNodeCollector collector, PoseStack poses, Matrix4fc view, Matrix4fc projection,
                                     float red, float green, float blue, float alpha) {
        renderFrustum(collector, poses, view, projection, RenderSystem.getDevice().getDeviceInfo().isZZeroToOne(), red, green, blue, alpha);
    }
    public static void renderFrustum(SubmitNodeCollector collector, PoseStack poses, Matrix4fc view, Matrix4fc projection,
                                     boolean zeroToOne, float red, float green, float blue, float alpha) {
        var type = RenderTypes.lines();
        collector.submitCustomGeometry(poses, type, snapshot(view, projection, zeroToOne, red, green, blue, alpha));
    }
    /** Homogeneous division is essential for perspective projections; inputs and corners are copied before return. */
    public static NativeVertexSnapshot snapshot(Matrix4fc view, Matrix4fc projection, boolean zeroToOne,
                                                 float red, float green, float blue, float alpha) {
        Matrix4f inverse = new Matrix4f(view).invert().mul(new Matrix4f(projection).invert());
        if (!inverse.isFinite()) throw new IllegalArgumentException("Frustum matrices must be invertible");
        Vector3f[] points = new Vector3f[8];
        for (int z = 0; z < 2; z++) for (int y = 0; y < 2; y++) for (int x = 0; x < 2; x++) {
            points[(z * 2 + y) * 2 + x] = inverse.transformProject(new Vector3f(x * 2 - 1, y * 2 - 1, z == 0 ? zeroToOne ? 0 : -1 : 1));
        }
        var output = new NativeVertexSnapshot.Capture(RenderTypes.lines().format());
        for (int axis = 0; axis < 3; axis++) for (int side = 0; side < 4; side++) {
            int first = 0, bit = 0;
            for (int other = 0; other < 3; other++) if (other != axis) first |= ((side >> bit++) & 1) << other;
            Vector3f a = points[first], b = points[first | 1 << axis];
            Vector3f tangent = new Vector3f(b).sub(a).normalize();
            output.addVertex(a.x, a.y, a.z).setColor(red, green, blue, alpha).setNormal(tangent.x, tangent.y, tangent.z).setLineWidth(1);
            output.addVertex(b.x, b.y, b.z).setColor(red, green, blue, alpha).setNormal(tangent.x, tangent.y, tangent.z).setLineWidth(1);
        }
        return output.finish();
    }
}
