package foundry.veil.mixin.pipeline.client;

import com.mojang.blaze3d.vertex.PoseStack;
import foundry.veil.api.client.render.MatrixStack;
import org.joml.*;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;

import java.util.List;

@Mixin(PoseStack.class)
public abstract class PipelinePoseStackMixin implements MatrixStack {

    @Unique
    private static final Matrix3f veil$IDENTITY_NORMAL = new Matrix3f();

    @Shadow
    public abstract void shadow$scale(float x, float y, float z);

    @Shadow
    public abstract void shadow$rotate(Quaternionfc quaternion);

    @Shadow
    public abstract void shadow$rotateAround(Quaternionfc quaternion, float x, float y, float z);

    @Shadow
    public abstract void shadow$pushPose();

    @Shadow
    public abstract void shadow$popPose();

    @Shadow
    @Final
    private List<PoseStack.Pose> poses;

    @Shadow
    private int lastIndex;

    @Shadow
    public abstract PoseStack.Pose last();

    @Unique
    private final Quaternionf veil$castQuat = new Quaternionf();

    @Override
    public void clear() {
        while (this.lastIndex > 0) {
            this.shadow$popPose();
        }
        this.setIdentity();
    }

    @Override
    public void rotate(Quaterniondc rotation) {
        this.shadow$rotate(this.veil$castQuat.set(rotation));
    }

    @Override
    public void rotate(float angle, float x, float y, float z) {
        this.shadow$rotate(this.veil$castQuat.identity().rotateAxis(angle, x, y, z));
    }

    @Override
    public void rotateXYZ(float x, float y, float z) {
        this.shadow$rotate(this.veil$castQuat.identity().rotateXYZ(x, y, z));
    }

    @Override
    public void rotateZYX(float z, float y, float x) {
        this.shadow$rotate(this.veil$castQuat.identity().rotateZYX(z, y, x));
    }

    @Override
    public void rotateAround(Quaterniondc rotation, double x, double y, double z) {
        this.shadow$rotateAround(this.veil$castQuat.set(rotation), (float) x, (float) y, (float) z);
    }

    @Override
    public void applyScale(float x, float y, float z) {
        this.shadow$scale(x, y, z);
    }

    @Override
    public boolean isIdentity() {
        PoseStack.Pose pose = this.pose();
        return (pose.pose().properties() & Matrix4fc.PROPERTY_IDENTITY) != 0 && pose.normal().equals(veil$IDENTITY_NORMAL);
    }

    @Override
    public boolean isEmpty() {
        return this.lastIndex == 0;
    }

    @Override
    public void matrixPush() {
        this.shadow$pushPose();
    }

    @Override
    public void matrixPop() {
        this.shadow$popPose();
    }

    @Override
    public PoseStack.Pose pose() {
        return this.last();
    }

    @Override
    public PoseStack toPoseStack() {
        return (PoseStack) (Object) this;
    }
}
