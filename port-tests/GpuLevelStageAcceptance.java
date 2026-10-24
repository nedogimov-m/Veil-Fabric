import com.google.gson.GsonBuilder;
import com.mojang.blaze3d.systems.RenderSystem;
import foundry.veil.api.event.VeilRenderLevelStageEvent;
import foundry.veil.fabric.FabricRenderTypeStageHandler;
import foundry.veil.fabric.event.FabricVeilRenderLevelStageEvent;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.minecraft.client.renderer.rendertype.RenderTypes;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/** One real vanilla world verifies the new native stage/geometry bridge only. */
public final class GpuLevelStageAcceptance implements FabricClientGameTest {
    @Override public void runTest(ClientGameTestContext test) {
        var report = new LinkedHashMap<String, Object>();
        var completed = new AtomicReference<List<VeilRenderLevelStageEvent.Stage>>();
        var failure = new AtomicReference<Throwable>();
        var draws = new AtomicInteger();
        var frame = new ArrayList<VeilRenderLevelStageEvent.Stage>();
        try {
            test.runOnClient(client -> {
                report.put("backend", RenderSystem.getDevice().getDeviceInfo().backendName());
                FabricRenderTypeStageHandler.registerEvents();
                FabricRenderTypeStageHandler.registerEvents();
                FabricVeilRenderLevelStageEvent.EVENT.register((stage, context) -> {
                    try {
                        if (!context.camera().initialized) return;
                        if (stage == VeilRenderLevelStageEvent.Stage.COLLECT_SUBMITS) frame.clear();
                        frame.add(stage);
                        if (context.mainTarget().getColorTextureView().isClosed()) throw new AssertionError("Native stage target is closed");
                        if (stage == VeilRenderLevelStageEvent.Stage.COLLECT_SUBMITS) {
                            if (context.poseStack() == null) throw new AssertionError("Geometry phase has no pose stack");
                            context.submissions().submitCustomGeometry(context.poseStack(), RenderTypes.debugQuads(), (pose, vertices) -> {
                                vertices.addVertex(pose, -0.2F, -0.2F, -3).setColor(255, 0, 255, 255);
                                vertices.addVertex(pose, 0.2F, -0.2F, -3).setColor(255, 0, 255, 255);
                                vertices.addVertex(pose, 0.2F, 0.2F, -3).setColor(255, 0, 255, 255);
                                vertices.addVertex(pose, -0.2F, 0.2F, -3).setColor(255, 0, 255, 255);
                                draws.incrementAndGet();
                            });
                        } else if (context.submitNodeCollector() != null) throw new AssertionError("Submissions exposed outside the collect phase");
                        if (stage == VeilRenderLevelStageEvent.Stage.END_MAIN) {
                            if (!frame.equals(List.of(VeilRenderLevelStageEvent.Stage.values()))) {
                                throw new AssertionError("Native phases must follow actual frame graph order once per frame: " + frame);
                            }
                            completed.set(List.copyOf(frame));
                        }
                    } catch (Throwable caught) { failure.compareAndSet(null, caught); }
                });
            });
            try (var world = test.worldBuilder().create()) {
                test.waitFor(client -> failure.get() != null || completed.get() != null && draws.get() > 0, 400);
                if (failure.get() != null) throw new AssertionError("Native level bridge failed", failure.get());
                if (completed.get() == null || draws.get() < 1) throw new AssertionError("Native level geometry callback never rendered");
                report.put("phases", completed.get());
                report.put("geometryCallbacks", draws.get());
                report.put("checks", List.of("each native level phase once; explicit geometry phase context; real deferred geometry renderer invoked"));
            }
            report.put("status", "passed");
        } catch (Throwable caught) {
            report.put("status", "failed"); report.put("error", caught.toString());
            throw caught;
        } finally {
            try { Files.writeString(Path.of(System.getProperty("veil.gpu.acceptance.report")), new GsonBuilder().setPrettyPrinting().create().toJson(report)); }
            catch (Exception caught) { throw new RuntimeException(caught); }
        }
    }
}
