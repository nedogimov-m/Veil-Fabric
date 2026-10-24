package foundry.veil.api.client.render;

import foundry.veil.api.client.render.gpu.GpuFramebuffer;
import foundry.veil.api.client.render.gpu.NativeLevelPerspective;
import foundry.veil.api.event.VeilRenderLevelStageEvent;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.util.profiling.Profiler;
import net.minecraft.world.entity.Entity;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.Nullable;
import org.joml.Matrix4fc;
import org.joml.Quaternionfc;
import org.joml.Vector3dc;
import java.util.concurrent.atomic.AtomicInteger;

/** Full native LevelRenderer perspectives over the current loaded terrain cache. Caller retains its output target. */
public final class VeilLevelPerspectiveRenderer {
    private static final AtomicInteger ID = new AtomicInteger();
    private static NativeLevelPerspective owner;
    private static ClientLevel level;
    private VeilLevelPerspectiveRenderer() {}
    public static GpuFramebuffer render(GpuFramebuffer framebuffer, Matrix4fc modelView, Matrix4fc projection,
            Vector3dc position, Quaternionfc orientation, float distance, DeltaTracker delta, boolean drawLights) {
        return render(framebuffer, Minecraft.getInstance().getCameraEntity(), modelView, projection, position, orientation, distance, delta, drawLights);
    }
    /** Explicit matrices use the genuine backend clip range and reverse Z. No primary camera/window state is replaced. */
    public static GpuFramebuffer render(GpuFramebuffer framebuffer, @Nullable Entity entity, Matrix4fc modelView, Matrix4fc projection,
            Vector3dc position, Quaternionfc orientation, float distance, DeltaTracker delta, boolean drawLights) {
        if (NativeLevelPerspective.isRendering()) return framebuffer;
        var client = Minecraft.getInstance();
        if (owner == null || level != client.level) { free(); owner = new NativeLevelPerspective(client); level = client.level; }
        ID.incrementAndGet();
        owner.render(framebuffer, entity, modelView, projection, position, orientation, distance, delta, () -> {
            if (drawLights) {
                var profiler = Profiler.get();
                if (VeilRenderSystem.drawLights(profiler, VeilRenderBridge.create(owner.cameraState().cullFrustum))) VeilRenderSystem.compositeLights(profiler);
            }
            VeilRenderSystem.renderPost(VeilRenderLevelStageEvent.Stage.END_MAIN);
            VeilRenderSystem.renderPost(null);
        });
        return framebuffer;
    }
    public static boolean isRenderingPerspective() { return NativeLevelPerspective.isRendering(); }
    @ApiStatus.Internal public static int getID() { return ID.get(); }
    @ApiStatus.Internal public static void free() {
        if (owner != null) { owner.close(); owner = null; } level = null;
    }
}
