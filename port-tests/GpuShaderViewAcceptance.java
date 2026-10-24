package foundry.veil.porttest;

import com.google.gson.GsonBuilder;
import com.mojang.blaze3d.pipeline.PipelineCache;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.buffers.*;
import com.mojang.renderpearl.api.device.GpuDevice;
import com.mojang.renderpearl.api.pipeline.*;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import foundry.veil.api.client.render.VeilShaderBufferLayout;
import foundry.veil.api.client.render.gpu.*;
import foundry.veil.api.client.render.shader.*;
import foundry.veil.api.client.render.shader.block.ShaderBlock;
import foundry.veil.api.client.render.shader.texture.ShaderTextureSource;
import foundry.veil.impl.client.render.pipeline.VeilShaderBlockState;
import foundry.veil.impl.client.render.shader.injection.*;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.render.GuiRenderer;
import net.minecraft.client.renderer.Projection;
import net.minecraft.client.renderer.state.level.*;
import net.minecraft.resources.Identifier;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Vector4f;

import java.nio.file.*;
import java.util.*;
import java.util.function.Supplier;

/** Focused real native entrypoint view timing; unrelated world/hand/range content is replaced by test probes. */
public final class GpuShaderViewAcceptance implements ClientModInitializer {
    private boolean ran; private int ticks;
    private static NativeShaderViews.Kind probeKind;
    private static Runnable probeBody;
    public interface GameAccess {
        void veil$viewHand(CameraRenderState camera, PlayerRenderState player, GpuTextureView depth);
        Projection veil$viewHudProjection();
        GuiRenderer veil$viewGuiRenderer();
    }
    public interface GuiAccess {
        Projection veil$viewGuiProjection();
        void veil$viewRange(Supplier<String> name, RenderTarget target, GpuBufferSlice transform, int start, int end);
    }
    public static boolean probe(NativeShaderViews.Kind kind) {
        if (kind != probeKind || probeBody == null) return false;
        probeBody.run(); return true;
    }
    private static void invoke(NativeShaderViews.Kind kind, Runnable body, Runnable entry) {
        var previousKind = probeKind; var previousBody = probeBody;
        probeKind = kind; probeBody = body;
        try { entry.run(); } finally { probeKind = previousKind; probeBody = previousBody; }
    }
    @Override public void onInitializeClient() {
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (this.ran || ++this.ticks < 5 || !client.isGameLoadFinished()) return;
            this.ran = true;
            var report = new LinkedHashMap<String, Object>(); var checks = new ArrayList<String>(); report.put("checks", checks);
            try {
                var device = RenderSystem.getDevice(); report.put("backend", device.getDeviceInfo().backendName()); report.put("device", device.getDeviceInfo().name());
                check(device.getDeviceInfo().backendName().toLowerCase(Locale.ROOT).contains("vulkan"), "Actual Vulkan required");
                run(client, device, checks); report.put("cases", checks.size()); report.put("status", "passed");
            } catch (Throwable failure) { failure.printStackTrace(); report.put("status", "failed"); report.put("error", failure.toString()); }
            finally {
                try { Files.writeString(Path.of(System.getProperty("veil.views.report")), new GsonBuilder().setPrettyPrinting().create().toJson(report) + "\n"); }
                catch (Exception failure) { throw new RuntimeException(failure); }
                client.stop();
            }
        });
    }
    private static void run(Minecraft client, GpuDevice device, List<String> checks) {
        var definitions = new ShaderPreDefinitions();
        var layout = VeilShaderBufferLayout.<Vector4f>builder().name("ViewProbe").vec4("Value", value -> value).build();
        NativeShaderBlocks.bindRuntime(() -> Map.of(Identifier.parse("veil_test:view"), layout));
        try (var source = new GpuShaderSource(client.getResourceManager()); var cache = new PipelineCache(device, source);
             var service = new NativeShaderInjectionService(new ShaderInjectionManager(), Map::of, definitions);
             var output = GpuFramebuffer.builder("native view timing output", 2, 2).color(GpuFormat.RGBA8_UNORM).depth(GpuFormat.D32_FLOAT).build(device);
             var sceneTarget = GpuFramebuffer.builder("native detached scene target", 2, 2).color(GpuFormat.RGBA8_UNORM).depth(GpuFormat.D32_FLOAT).build(device)) {
            NativePipelineBridge.bindRuntime(definitions, service, List.of());
            var pipeline = RenderPipeline.builder().withLocation(Identifier.parse("veil_test:view"))
                    .withVertexShader(Identifier.parse("veil:core/gpu_injection_vertex")).withFragmentShader(Identifier.parse("veil:core/gpu_view"))
                    .withPrimitiveTopology(PrimitiveTopology.TRIANGLES).withCull(false).withDepthStencilState(Optional.empty())
                    .withColorTargetState(ColorTargetState.DEFAULT).build();
            var compiled = cache.get(pipeline);
            var selected = new VeilShaderBlockState(); var value = new Vector4f(.05f, .1f, .15f, 1);
            var block = ShaderBlock.<Vector4f>withSize(device, ShaderBlock.BufferBinding.UNIFORM, 16, (data, bytes) -> { for (int c = 0; c < 4; c++) bytes.putFloat(c * 4, data.get(c)); });
            block.set(value); selected.bind("ViewProbe", block);
            var history = new ArrayList<NativeShaderViews.View>(); int[] captures = {0}; boolean[] inPass = {false};
            NativeShaderViews.Handler handler = view -> {
                check(!inPass[0], "CPU view callback entered an open native pass");
                var previous = new Vector4f(value); history.add(view);
                value.set(view.projection().m00() * .1f, view.view().m11() * .2f,
                        view.kind() == NativeShaderViews.Kind.GUI ? view.guiScale() * .1f : (float) view.x() * .01f, 1);
                block.set(value);
                return () -> { value.set(previous); block.set(value); };
            };
            var textures = new ShaderTextureSource.Context() { @Override public GpuFramebuffer getFramebuffer(Identifier name) { throw new AssertionError("No texture requests expected"); } };
            try (var frames = new NativeShaderInjectionFrames(service, device, textures, names -> {
                check(!inPass[0], "View resources prepared in an open pass"); captures[0]++; return selected.snapshot(names);
            }, NativeShaderBlocks::layoutNames)) {
                NativeShaderInjectionFrames.bindRuntime(frames); NativeShaderViews.bindRuntime(handler);
                Runnable draw = () -> {
                    var encoder = device.createCommandEncoder();
                    try (var pass = output.beginPass(encoder)) { inPass[0] = true; pass.setPipeline(compiled); pass.draw(3, 1, 0, 0); }
                    finally { inPass[0] = false; }
                    encoder.submit();
                };
                var camera = new CameraRenderState(); camera.projectionMatrix.identity().m00(2); camera.viewRotationMatrix.identity().m11(.5f); camera.pos = new Vec3(30, 40, 50);
                Runnable worldEntry = () -> client.levelRenderer.render(null, false, camera, null, new Vector4f(0), false, false);
                var game = (GameAccess) client.gameRenderer; var gui = (GuiAccess) game.veil$viewGuiRenderer();
                game.veil$viewHudProjection().setupPerspective(.05f, 100, 70, 480, 320);
                var handProjection = game.veil$viewHudProjection().getMatrix(new Matrix4f());
                gui.veil$viewGuiProjection().setupOrtho(1000, 11000, 120, 80, true);
                var guiProjection = gui.veil$viewGuiProjection().getMatrix(new Matrix4f());
                int previousScale = client.gameRenderer.gameRenderState().windowRenderState.guiScale;
                client.gameRenderer.gameRenderState().windowRenderState.guiScale = 4;
                try (var outer = frames.beginFrame()) {
                    invoke(NativeShaderViews.Kind.WORLD, () -> {
                        draw.run(); assertPixels(device, output, 51, 26, 77, 255);
                        try (var hand = NativeShaderViews.beginHand(handProjection, camera)) {
                            draw.run(); assertPixels(device, output, Math.round(handProjection.m00() * 25.5f), 26, 77, 255);
                        }
                        check(value.equals(new Vector4f(.2f, .1f, .3f, 1), .0001f), "Hand scope failed to restore world CPU values");
                        draw.run(); assertPixels(device, output, 51, 26, 77, 255);
                        invoke(NativeShaderViews.Kind.GUI, () -> {
                            draw.run(); assertPixels(device, output, Math.round(guiProjection.m00() * 25.5f), 51, 102, 255);
                        }, () -> gui.veil$viewRange(() -> "test GUI range", client.gameRenderer.mainRenderTarget(), null, 0, 1));
                        draw.run(); assertPixels(device, output, 51, 26, 77, 255);
                    }, worldEntry);
                    check(value.equals(new Vector4f(.05f, .1f, .15f, 1), .0001f), "World scope failed to restore outer CPU values");
                    draw.run(); assertPixels(device, output, 13, 26, 38, 255);
                    checks.add("actual LevelRenderer.render and GuiRenderer.executeDrawRange wrappers plus explicit hand view scope using native HUD Projection refresh CPU callback before detached named-block capture; native world/HUD/GUI projection and extracted GUI scale produce exact Vulkan pixels, nested scopes restore parent CPU values and captured bindings");
                    var inputs = NativeFeatureScene.Inputs.capture(client); inputs.options().improvedTransparency = false;
                    try (var scene = new NativeFeatureScene(client, client.getResourceManager())) {
                        var sceneProjection = new Matrix4f().m00(3); var sceneView = new Matrix4f().m11(.75f);
                        scene.render(sceneTarget, sceneProjection, com.mojang.blaze3d.ProjectionType.PERSPECTIVE, sceneView, inputs, nodes -> {
                            sceneProjection.zero(); sceneView.zero(); // production captured its actual values before this callback
                            draw.run(); assertPixels(device, output, 77, 38, 0, 255);
                        });
                        check(value.equals(new Vector4f(.05f, .1f, .15f, 1), .0001f), "Detached scene failed to restore outer values");
                    }
                    draw.run(); assertPixels(device, output, 13, 26, 38, 255);
                    checks.add("actual NativeFeatureScene.render captures copied explicit scene matrices before submit callbacks, keeps fresh named native GPU bindings through scene submission, and restores the enclosing view");
                    boolean failed = false;
                    try { invoke(NativeShaderViews.Kind.WORLD, () -> { throw new IllegalStateException("deliberate view failure"); }, worldEntry); }
                    catch (IllegalStateException expected) { failed = expected.getMessage().equals("deliberate view failure"); }
                    check(failed && value.equals(new Vector4f(.05f, .1f, .15f, 1), .0001f), "Exceptional view did not restore parent state");
                    draw.run(); assertPixels(device, output, 13, 26, 38, 255);
                    invoke(NativeShaderViews.Kind.WORLD, draw, worldEntry); assertPixels(device, output, 51, 26, 77, 255);
                    checks.add("exception from the actual world view body closes the exact nested named-resource frame and restores parent CPU/native bindings; the next actual world invocation succeeds");
                } finally {
                    client.gameRenderer.gameRenderState().windowRenderState.guiScale = previousScale;
                    NativeShaderViews.unbindRuntime(handler); block.free(); selected.clear();
                }
                check(history.size() == 6 && captures[0] == 7, "Unexpected view/snapshot entry counts: " + history.size() + "/" + captures[0]);
            } finally { NativePipelineBridge.flush(); NativePipelineBridge.unbindRuntime(); }
        } finally { NativeShaderBlocks.unbindRuntime(); }
    }
    private static void assertPixels(GpuDevice device, GpuFramebuffer output, int... expected) {
        try (var read = device.createBuffer(() -> "native view pixel readback", GpuBuffer.USAGE_COPY_DST | GpuBuffer.USAGE_MAP_READ, 16)) {
            var encoder = device.createCommandEncoder(); encoder.copyTextureToBuffer(output.color(0).texture(), read, 0, () -> {}, 0);
            try (var fence = encoder.createFence()) {
                encoder.submit(); check(fence.awaitCompletion(10_000_000_000L), "View readback timed out");
                try (var mapped = read.map(true, false)) { for (int i = 0; i < 16; i++) check(Math.abs((mapped.data().get(i) & 255) - expected[i % 4]) <= 2, "View pixel " + i + " was " + (mapped.data().get(i) & 255) + ", expected " + expected[i % 4]); }
            }
        }
    }
    private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
}
