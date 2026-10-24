package foundry.veil.impl.client.render.perspective;

import foundry.veil.mixin.perspective.accessor.NativePerspectiveCameraAccessor;
import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.world.entity.Entity;
import org.jetbrains.annotations.Nullable;
import org.joml.*;

import java.util.Objects;

/** Actual native camera with an explicit copied projection/view, independent of the player's camera update. */
public final class LevelPerspectiveCamera extends Camera {
    private final Matrix4f projection = new Matrix4f();
    private final Matrix4f view = new Matrix4f();
    private final Vector3f forward = new Vector3f(), up = new Vector3f(), left = new Vector3f();
    private Frustum frustum;
    private float viewDistance;

    public void setup(Vector3dc position, @Nullable Entity entity, ClientLevel level, Quaternionfc orientation, float distance,
            Matrix4fc modelView, Matrix4fc projection, int width, int height) {
        this.setLevel(Objects.requireNonNull(level));
        this.setEntity(entity != null ? entity : Objects.requireNonNull(Minecraft.getInstance().player));
        this.setPosition(position.x(), position.y(), position.z());
        var euler = orientation.getEulerAnglesYXZ(new Vector3f());
        this.setRotation((float) java.lang.Math.toDegrees(-euler.y), (float) java.lang.Math.toDegrees(euler.x));
        this.rotation().set(orientation).normalize();
        this.forward.set(0, 0, -1).rotate(this.rotation()); this.up.set(0, 1, 0).rotate(this.rotation()); this.left.set(-1, 0, 0).rotate(this.rotation());
        this.viewDistance = distance;
        this.projection.set(projection);
        this.view.set(modelView).rotate(new Quaternionf(this.rotation()).conjugate());
        this.frustum = new Frustum(this.view, new Matrix4f(this.projection));
        this.frustum.prepare(position.x(), position.y(), position.z());
        var nativeCamera = (NativePerspectiveCameraAccessor) (Object) this;
        nativeCamera.veil$perspectiveInitialized(true);
        float fov = (float) java.lang.Math.toDegrees(2 * java.lang.Math.atan(1 / java.lang.Math.abs(projection.m11())));
        nativeCamera.veil$perspectiveProjection().setupPerspective(.05f, distance * 16, fov, width, height);
        this.attributeProbe().tick(level, this.position());
    }
    /** Convenience for integrations that supply orientation without a custom projection. */
    public void setup(Vector3dc position, @Nullable Entity entity, ClientLevel level, Quaternionfc orientation, float distance) {
        var client = Minecraft.getInstance(); var nativeProjection = new net.minecraft.client.renderer.Projection();
        nativeProjection.setupPerspective(.05f, distance * 16, 70, client.getWindow().getWidth(), client.getWindow().getHeight());
        this.setup(position, entity, level, orientation, distance, new Matrix4f(), nativeProjection.getMatrix(new Matrix4f()), client.getWindow().getWidth(), client.getWindow().getHeight());
    }
    @Override public void extractRenderState(CameraRenderState state, DeltaTracker delta) {
        super.extractRenderState(state, delta);
        state.initialized = true; state.isFirstPerson = false; state.isFrustumCaptured = false; state.smartCull = false;
        state.depthFar = this.viewDistance * 16; state.projectionMatrix.set(this.projection); state.viewRotationMatrix.set(this.view);
        state.cullFrustum = new Frustum(this.frustum);
    }
    @Override public Matrix4f getViewRotationMatrix(Matrix4f destination) { return destination.set(this.view); }
    @Override public Matrix4f getViewRotationProjectionMatrix(Matrix4f destination) { return this.projection.mul(this.view, destination); }
    @Override public Frustum getCullFrustum() { return this.frustum; }
    @Override public @Nullable Frustum getCapturedFrustum() { return null; }
    @Override public Vector3fc forwardVector() { return this.forward; }
    @Override public Vector3fc upVector() { return this.up; }
    @Override public Vector3fc leftVector() { return this.left; }
    @Override public boolean isDetached() { return true; }
    public float getRenderDistance() { return this.viewDistance; }
}
