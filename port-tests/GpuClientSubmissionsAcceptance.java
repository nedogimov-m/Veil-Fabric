package foundry.veil.porttest;

import com.google.gson.GsonBuilder;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import foundry.veil.api.client.render.ext.VeilDebug;
import foundry.veil.api.event.VeilRenderLevelStageEvent;
import foundry.veil.fabric.FabricRenderTypeStageHandler;
import foundry.veil.fabric.event.FabricVeilRegisterBlockLayersEvent;
import foundry.veil.fabric.event.FabricVeilRegisterFixedBuffersEvent;
import foundry.veil.fabric.event.FabricVeilRenderLevelStageEvent;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.minecraft.client.renderer.OrderedSubmitNodeCollector;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.util.profiling.ActiveProfiler;
import org.joml.Matrix4f;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.atomic.*;

/** Actual registration events and ordered geometry in one genuine native world; no full Veil bootstrap. */
public final class GpuClientSubmissionsAcceptance implements FabricClientGameTest {
    @Override public void runTest(ClientGameTestContext test) {
        var report = new LinkedHashMap<String, Object>();
        var checks = new ArrayList<String>(); report.put("checks", checks);
        var failure = new AtomicReference<Throwable>(); var active = new AtomicBoolean();
        var completedFrames = new AtomicInteger(); var fixedCollections = new AtomicInteger(); var blockCollections = new AtomicInteger();
        var fixedDraws = new AtomicInteger(); var blockDraws = new AtomicInteger();
        try {
            test.runOnClient(client -> {
                var info = RenderSystem.getDevice().getDeviceInfo(); report.put("backend", info.backendName()); report.put("device", info.name());
                check(info.backendName().equalsIgnoreCase("vulkan"), "Actual Vulkan required");
                profiler(checks);
                FabricVeilRegisterFixedBuffersEvent.EVENT.register(registry -> registry.registerSubmission(2, (context, collector) -> {
                    if (!active.get() || failure.get() != null || !context.camera().initialized) return;
                    try { submit(context, collector, 2, 1.5f, 255, 0, fixedCollections, fixedDraws); }
                    catch (Throwable caught) { failure.compareAndSet(null, caught); }
                }));
                FabricVeilRegisterBlockLayersEvent.EVENT.register(registry -> registry.registerBlockGeometry(3, (context, collector) -> {
                    if (!active.get() || failure.get() != null || !context.camera().initialized) return;
                    try { submit(context, collector, 3, .75f, 0, 255, blockCollections, blockDraws); }
                    catch (Throwable caught) { failure.compareAndSet(null, caught); }
                }));
                FabricVeilRegisterFixedBuffersEvent.EVENT.invoker().onRegisterFixedBuffers(FabricRenderTypeStageHandler::registerSubmission);
                FabricVeilRegisterBlockLayersEvent.EVENT.invoker().onRegisterBlockLayers(FabricRenderTypeStageHandler::registerSubmission);
                FabricRenderTypeStageHandler.registerEvents(); FabricRenderTypeStageHandler.registerEvents();
                FabricVeilRenderLevelStageEvent.EVENT.register((stage, context) -> {
                    if (!active.get() || failure.get() != null || stage != VeilRenderLevelStageEvent.Stage.END_MAIN || fixedCollections.get() == 0) return;
                    try {
                        int frame = completedFrames.incrementAndGet();
                        check(fixedCollections.get() == frame && blockCollections.get() == frame, "Persistent registration callback did not run exactly once per actual world frame");
                        check(fixedDraws.get() == frame && blockDraws.get() == frame, "Native deferred geometry renderer did not execute once per registered callback");
                        var target = context.mainTarget();
                        pixel(target.getColorTexture(), target.width / 2, target.height / 2, 0, 255, 0, 255);
                        if (frame == 2) active.set(false);
                    } catch (Throwable caught) { failure.compareAndSet(null, caught); }
                });
            });
            try (var world = test.worldBuilder().create()) {
                test.waitFor(client -> client.gameRenderer.gameRenderState().levelRenderState.cameraRenderState.initialized, 400);
                test.runOnClient(client -> active.set(true));
                test.waitFor(client -> failure.get() != null || completedFrames.get() >= 2, 200);
                if (failure.get() != null) throw new AssertionError("Actual native client submission registration failed", failure.get());
                check(completedFrames.get() == 2, "Two actual native registration frames were not observed");
                checks.add("both real Fabric registration events install persistent callbacks; each receives its exact native ordered collector once per world view and executes real custom geometry across two frames");
                checks.add("actual native order 2 red geometry followed by overlapping order 3 green geometry produces exact green Vulkan world pixels");
                report.put("frames", completedFrames.get()); report.put("fixedCallbacks", fixedCollections.get()); report.put("blockCallbacks", blockCollections.get());
                report.put("cases", checks.size()); report.put("status", "passed");
            }
        } catch (Throwable caught) { caught.printStackTrace(); report.put("status", "failed"); report.put("error", caught.toString()); throw caught; }
        finally {
            active.set(false);
            try { Files.writeString(Path.of(System.getProperty("veil.submissions.report")), new GsonBuilder().setPrettyPrinting().create().toJson(report) + "\n"); }
            catch (Exception caught) { throw new RuntimeException(caught); }
        }
    }
    private static void submit(VeilRenderLevelStageEvent.Context context, OrderedSubmitNodeCollector collector, int order,
            float halfSize, int red, int green, AtomicInteger collections, AtomicInteger draws) {
        check(context.stage() == VeilRenderLevelStageEvent.Stage.COLLECT_SUBMITS, "Registration ran outside native collection");
        check(collector == context.submissions().order(order), "Registration did not receive its actual ordered native collector");
        var poses = new PoseStack(); poses.mulPose(new Matrix4f(context.camera().viewRotationMatrix).invert().translate(0, 0, -3));
        collector.submitCustomGeometry(poses, RenderTypes.debugQuads(), (pose, vertices) -> {
            vertices.addVertex(pose, -halfSize, -halfSize, 0).setColor(red, green, 0, 255);
            vertices.addVertex(pose, halfSize, -halfSize, 0).setColor(red, green, 0, 255);
            vertices.addVertex(pose, halfSize, halfSize, 0).setColor(red, green, 0, 255);
            vertices.addVertex(pose, -halfSize, halfSize, 0).setColor(red, green, 0, 255);
            draws.incrementAndGet();
        });
        collections.incrementAndGet();
    }
    private static void profiler(List<String> checks) {
        var debug = VeilDebug.get(); var initial = debug.activeGroups();
        var profiler = new ActiveProfiler(System::nanoTime, () -> 0, () -> true);
        profiler.push("ignored before tick"); profiler.pop(); profiler.endTick();
        check(debug.activeGroups().equals(initial), "Rejected vanilla profiler calls changed native groups");
        profiler.startTick(); profiler.push("accepted child");
        check(debug.activeGroups().size() == initial.size() + 2 && debug.activeGroups().getLast().equals("accepted child"), "Actual accepted profiler paths were not mirrored");
        profiler.pop(); profiler.pop(); profiler.pop(); profiler.endTick();
        check(debug.activeGroups().equals(initial), "Unmatched vanilla profiler pop changed native groups");
        profiler.startTick(); profiler.push("unfinished child"); profiler.endTick();
        check(debug.activeGroups().equals(initial), "Incomplete profiler tick leaked native debug groups");
        checks.add("actual ActiveProfiler mixin mirrors accepted paths, ignores rejected before-tick/extra pops, and closes incomplete tick groups without resetting unrelated groups");
    }
    private static void pixel(com.mojang.renderpearl.api.textures.GpuTexture texture, int x, int y, int... expected) {
        var device = RenderSystem.getDevice();
        try (var read = device.createBuffer(() -> "native client submission pixel", GpuBuffer.USAGE_COPY_DST | GpuBuffer.USAGE_MAP_READ, 4)) {
            var encoder = device.createCommandEncoder(); encoder.copyTextureToBuffer(texture, read, 0, () -> {}, 0, x, y, 1, 1);
            try (var fence = encoder.createFence()) { encoder.submit(); check(fence.awaitCompletion(10_000_000_000L), "Native submission readback timeout"); }
            try (var mapped = read.map(true, false)) { for (int c = 0; c < 4; c++) check(Math.abs((mapped.data().get(c) & 255) - expected[c]) <= 2,
                    "Native submission component " + c + " was " + (mapped.data().get(c) & 255) + ", expected " + expected[c]); }
        }
    }
    private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
}
