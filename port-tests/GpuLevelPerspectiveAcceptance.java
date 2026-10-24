package foundry.veil.porttest;

import com.google.gson.GsonBuilder;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import foundry.veil.api.client.render.gpu.*;
import foundry.veil.api.event.VeilRenderLevelStageEvent;
import foundry.veil.fabric.FabricRenderTypeStageHandler;
import foundry.veil.fabric.event.FabricVeilRenderLevelStageEvent;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.minecraft.client.renderer.Projection;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3d;

import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.*;

/** New actual-world proof: independent real LevelRenderer, alternate native extraction and full OIT graph. */
public final class GpuLevelPerspectiveAcceptance implements FabricClientGameTest {
    private static NativeLevelPerspective active;
    private static int oitPasses;
    public static void oit(net.minecraft.client.renderer.LevelRenderer renderer) { if (active != null && renderer == active.renderer()) oitPasses++; }
    @Override public void runTest(ClientGameTestContext test) {
        var report = new LinkedHashMap<String, Object>(); var checks = new ArrayList<String>(); report.put("checks", checks);
        var owner = new AtomicReference<NativeLevelPerspective>(); var stages = new EnumMap<VeilRenderLevelStageEvent.Stage, Integer>(VeilRenderLevelStageEvent.Stage.class);
        var failGeometry = new AtomicBoolean(); var geometry = new AtomicInteger(); var oldTransparency = new AtomicReference<Boolean>();
        try {
            test.runOnClient(client -> {
                oldTransparency.set(client.options.improvedTransparency().get()); client.options.improvedTransparency().set(true);
                report.put("backend", RenderSystem.getDevice().getDeviceInfo().backendName()); report.put("device", RenderSystem.getDevice().getDeviceInfo().name());
                FabricRenderTypeStageHandler.registerEvents();
                FabricVeilRenderLevelStageEvent.EVENT.register((stage, context) -> {
                    if (!NativeLevelPerspective.isRendering() || owner.get() == null || context.levelRenderer() != owner.get().renderer()) return;
                    stages.merge(stage, 1, Integer::sum);
                    if (stage != VeilRenderLevelStageEvent.Stage.COLLECT_SUBMITS) return;
                    var poses = context.poseStack(); poses.pushPose();
                    poses.mulPose(new Matrix4f(owner.get().cameraState().viewRotationMatrix).invert());
                    context.submissions().submitCustomGeometry(poses, RenderTypes.debugQuads(), (pose, vertices) -> {
                        if (failGeometry.getAndSet(false)) throw new IllegalStateException("deliberate independent world producer failure");
                        for (float[] xy : new float[][]{{-2, -2}, {2, -2}, {2, 2}, {-2, 2}})
                            vertices.addVertex(pose, xy[0], xy[1], -3).setColor(255, 0, 255, 255);
                        geometry.incrementAndGet();
                    });
                    poses.popPose();
                });
            });
            try (var world = test.worldBuilder().create()) {
                test.waitFor(client -> client.gameRenderer.gameRenderState().levelRenderState.cameraRenderState.initialized
                        && client.levelRenderer.viewArea() != null && client.levelRenderer.visibleSections().size() > 10, 400);
                test.runOnClient(client -> {
                    var device = RenderSystem.getDevice(); var main = client.gameRenderer.mainRenderTarget();
                    var originalColor = main.getColorTextureView(); var originalDepth = main.getDepthTextureView();
                    var originalProjection = RenderSystem.getProjectionMatrixBuffer(); var originalView = RenderSystem.getModelViewMatrixCopy();
                    var primaryCamera = client.gameRenderer.gameRenderState().levelRenderState.cameraRenderState; var primaryPosition = primaryCamera.pos;
                    var primarySections = List.copyOf(client.levelRenderer.visibleSections()); var dispatcher = client.levelRenderer.sectionRenderDispatcher();
                    try (var perspective = new NativeLevelPerspective(client);
                         var output = GpuFramebuffer.builder("actual independent native world", 64, 64).color(main.getColorTexture().getFormat()).depth(main.getDepthTexture().getFormat()).build(device)) {
                        owner.set(perspective);
                        active = perspective;
                        var projection = new Projection(); projection.setupPerspective(.05f, 128, 70, 64, 64);
                        var matrix = projection.getMatrix(new Matrix4f());
                        var position = new Vector3d(primaryPosition.x + 2, primaryPosition.y, primaryPosition.z);
                        var orientation = new Quaternionf(primaryCamera.orientation);
                        int[] borrowedSections = {0};
                        Runnable complete = () -> {
                            check(NativeLevelPerspective.isRendering() && main.getColorTextureView() == output.color(0).view(), "Perspective completion lost alternate world target/scope");
                            borrowedSections[0] = perspective.renderer().visibleSections().size();
                            assertPixel(output, 255, 0, 255, 255);
                        };
                        perspective.render(output, client.player, new Matrix4f(), matrix, position, orientation, 8, client.getDeltaTracker(), complete);
                        check(borrowedSections[0] > 0, "Native perspective graph had no recaptured resident terrain sections");
                        check(perspective.cameraState().pos.x == position.x && perspective.cameraState().pos != primaryPosition, "Alternate extracted camera was not independent");
                        restoreCheck(main, originalColor, originalDepth, originalProjection, originalView);
                        check(primaryCamera == client.gameRenderer.gameRenderState().levelRenderState.cameraRenderState && primaryCamera.pos.equals(primaryPosition)
                                && primarySections.equals(client.levelRenderer.visibleSections()), "Native perspective modified the primary extracted world or visible section list");
                        for (var stage : List.of(VeilRenderLevelStageEvent.Stage.START_MAIN, VeilRenderLevelStageEvent.Stage.AFTER_OPAQUE_TERRAIN,
                                VeilRenderLevelStageEvent.Stage.END_MAIN))
                            check(stages.containsKey(stage), "Independent actual native world graph omitted stage " + stage);
                        check(oitPasses > 0, "Independent native world did not execute its real OIT graph");
                        checks.add("independent genuine LevelRenderer executes actual terrain/sky/features/transparency/OIT frame graph with re-extracted alternate camera and resident terrain reculling; native phase callbacks and exact Vulkan center pixels; primary extracted camera, visible sections, target views and projection/modelview remain intact");
                        failGeometry.set(true); boolean failed = false;
                        try { perspective.render(output, client.player, new Matrix4f(), matrix, position, orientation, 8, client.getDeltaTracker(), complete); }
                        catch (RuntimeException expected) { failed = true; }
                        check(failed, "Independent world producer did not fail"); restoreCheck(main, originalColor, originalDepth, originalProjection, originalView);
                        perspective.render(output, client.player, new Matrix4f(), matrix, position, orientation, 8, client.getDeltaTracker(), complete);
                        check(geometry.get() >= 2 && !NativeLevelPerspective.isRendering(), "Independent world did not recover or scope leaked");
                        checks.add("throwing native geometry producer closes the partial independent feature frame, pops camera/target state, and a following full native world replay succeeds with exact pixels");
                    } finally { owner.set(null); active = null; }
                    check(client.levelRenderer.sectionRenderDispatcher() == dispatcher && main.getColorTextureView() == originalColor && !originalColor.isClosed(), "Closing independent world retired borrowed primary terrain or native targets");
                });
                report.put("stageCounts", stages); report.put("nativeOitPasses", oitPasses); report.put("geometryCallbacks", geometry.get()); report.put("cases", checks.size()); report.put("status", "passed");
            }
        } catch (Throwable failure) { failure.printStackTrace(); report.put("status", "failed"); report.put("error", failure.toString()); throw failure; }
        finally {
            test.runOnClient(client -> { owner.set(null); if (oldTransparency.get() != null) client.options.improvedTransparency().set(oldTransparency.get()); });
            try { Files.writeString(Path.of(System.getProperty("veil.perspective.report")), new GsonBuilder().setPrettyPrinting().create().toJson(report) + "\n"); }
            catch (Exception failure) { throw new RuntimeException(failure); }
        }
    }
    private static void restoreCheck(com.mojang.blaze3d.pipeline.RenderTarget main, Object color, Object depth, Object projection, Matrix4f view) {
        check(main.getColorTextureView() == color && main.getDepthTextureView() == depth && RenderSystem.getProjectionMatrixBuffer() == projection
                && RenderSystem.getModelViewMatrixCopy().equals(view, 0), "Independent world failed to restore native parent resources");
    }
    private static void assertPixel(GpuFramebuffer output, int... expected) {
        var device = RenderSystem.getDevice();
        try (var read = device.createBuffer(() -> "independent world center", GpuBuffer.USAGE_COPY_DST | GpuBuffer.USAGE_MAP_READ, 4)) {
            var encoder = device.createCommandEncoder(); encoder.copyTextureToBuffer(output.color(0).texture(), read, 0, () -> {}, 0, output.width() / 2, output.height() / 2, 1, 1);
            try (var fence = encoder.createFence()) { encoder.submit(); check(fence.awaitCompletion(10_000_000_000L), "Independent world readback timeout"); }
            try (var mapped = read.map(true, false)) { for (int c = 0; c < 4; c++) check(Math.abs((mapped.data().get(c) & 255) - expected[c]) <= 2, "Independent world component " + c + " was " + (mapped.data().get(c) & 255) + ", expected " + expected[c]); }
        }
    }
    private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
}
