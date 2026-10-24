import com.google.gson.GsonBuilder;
import com.mojang.blaze3d.ProjectionType;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.pipeline.PipelineCache;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.systems.ScissorState;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.device.GpuDevice;
import com.mojang.renderpearl.api.pipeline.*;
import foundry.veil.api.client.render.gpu.*;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.rendertype.RenderSetup;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.Identifier;
import org.joml.Matrix4f;
import org.joml.Vector4f;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/** Test-only: actual feature dispatch, target pixels, borrowed defaults and nested native state restoration. */
public final class GpuFeatureSceneAcceptance implements ClientModInitializer {
    private boolean ran;
    private int ticks;
    @Override public void onInitializeClient() {
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (this.ran || ++this.ticks < 5 || !client.isGameLoadFinished()) return;
            this.ran = true; var report = new LinkedHashMap<String, Object>();
            try {
                var device = RenderSystem.getDevice(); report.put("backend", device.getDeviceInfo().backendName()); report.put("device", device.getDeviceInfo().name());
                check(device.getDeviceInfo().backendName().toLowerCase(Locale.ROOT).contains("vulkan"), "Real Vulkan required");
                var checks = new ArrayList<String>(); report.put("checks", checks);
                run(client, device, checks); report.put("cases", checks.size()); report.put("status", "passed");
            } catch (Throwable failure) { failure.printStackTrace(); report.put("status", "failed"); report.put("error", failure.toString()); }
            finally {
                try { Files.writeString(Path.of(System.getProperty("veil.scene.report")), new GsonBuilder().setPrettyPrinting().create().toJson(report) + "\n"); }
                catch (Exception failure) { throw new RuntimeException(failure); }
                client.stop();
            }
        });
    }
    private static void run(Minecraft client, GpuDevice device, List<String> checks) throws Exception {
        Identifier white = Identifier.parse("veil:feature_scene_white");
        var image = NativeImage.read(GpuFeatureSceneAcceptance.class.getResourceAsStream("/fixtures/feature_scene_white.png"));
        client.getTextureManager().register(white, new DynamicTexture(() -> "scene acceptance white", image));
        RenderType type = net.minecraft.client.renderer.rendertype.RenderTypes.entityTranslucent(white);
        final int shade = type.pipeline().getShaderDefines().flags().contains("NO_CARDINAL_LIGHTING") ? 255 : 102;
        final int leftRed = Math.round(shade * .65f * 128 / 255f), rightBlue = Math.round(shade * 128 / 255f);
        var captured = NativeFeatureScene.Inputs.capture(client); captured.options().improvedTransparency = false;
        if (System.getProperty("veil.scene.scope", "base").equals("recovery")) {
            recovery(client, device, type, captured, checks); return;
        }
        try (var scene = new NativeFeatureScene(client, client.getResourceManager());
             var nested = new NativeFeatureScene(client, client.getResourceManager());
             var output = GpuFramebuffer.builder("two composed feature scenes", 4, 2).color(GpuFormat.RGBA8_UNORM).depth(GpuFormat.D32_FLOAT).build(device);
             var nestedOutput = GpuFramebuffer.builder("nested feature scene", 4, 2).color(GpuFormat.RGBA8_UNORM).depth(GpuFormat.D32_FLOAT).build(device);
             var lightA = GpuFramebuffer.builder("borrowed 0.65 lightmap", 1, 1).color(GpuFormat.RGBA8_UNORM).build(device);
             var lightB = GpuFramebuffer.builder("borrowed 1.0 lightmap", 1, 1).color(GpuFormat.RGBA8_UNORM).build(device);
             var source = new GpuShaderSource(client.getResourceManager());
             var callerCache = new PipelineCache(device, source)) {
            var clear = device.createCommandEncoder(); output.clear(clear, new Vector4f(0, .25f, 0, 1), 0); nestedOutput.clear(clear, new Vector4f(), 0);
            lightA.clear(clear, new Vector4f(.65f, .65f, .65f, 1), 0); lightB.clear(clear, new Vector4f(1), 0); clear.submit();
            var a = new NativeFeatureScene.Inputs(captured.options(), lightA.color(0).view(), captured.fog(), captured.lights(), captured.globals());
            var b = new NativeFeatureScene.Inputs(captured.options(), lightB.color(0).view(), captured.fog(), captured.lights(), captured.globals());
            var oldCache = RenderSystem.setCurrentPipelineCache(callerCache);
            try {
                NativeState before = NativeState.capture();
                var left = new Matrix4f().translation(-.5f, 0, 0).scale(.5f, 1, 1);
                scene.render(output, new Matrix4f(), ProjectionType.ORTHOGRAPHIC, left, a, nodes -> {
                    check(type.prepare().textures().stream().anyMatch(texture -> texture.name().equals("Sampler2") && texture.textureView() == a.lightmap()), "Real RenderType.prepare ignored borrowed scene lightmap");
                    quad(nodes, type, 255, 0, 0, 128);
                });
                before.assertRestored(callerCache);
                nested.render(output, new Matrix4f(), ProjectionType.ORTHOGRAPHIC, new Matrix4f().translation(.5f, 0, 0).scale(.5f, 1, 1), b,
                        nodes -> quad(nodes, type, 0, 0, 255, 128));
                before.assertRestored(callerCache);
                assertPixels(device, output, new int[]{leftRed,32,0,255}, new int[]{0,32,rightBlue,255});
                checks.add("real native feature dispatch composes caller target with distinct transforms and borrowed 0.65/1.0 lightmaps; classic alpha produces exact pixels");
                boolean failed = false;
                try {
                    scene.render(output, new Matrix4f(), ProjectionType.ORTHOGRAPHIC, left, a, nodes -> {
                        var outerProjection = RenderSystem.getProjectionMatrixBuffer();
                        nested.render(nestedOutput, new Matrix4f(), ProjectionType.PERSPECTIVE, new Matrix4f(), b,
                                inner -> quad(inner, type, 0, 255, 0, 255));
                        check(RenderSystem.getProjectionMatrixBuffer() == outerProjection && NativeFeatureScene.scopedLightmap() == a.lightmap()
                                && RenderSystem.getModelViewMatrixCopy().equals(left, 0), "Nested scope did not restore outer native view/resources");
                        throw new IllegalStateException("deliberate scene callback failure");
                    });
                } catch (IllegalStateException expected) { failed = expected.getMessage().equals("deliberate scene callback failure"); }
                check(failed, "Expected deliberate nested scene failure"); before.assertRestored(callerCache);
                assertPixels(device, nestedOutput, new int[]{0,shade,0,255}, new int[]{0,shade,0,255});
                checks.add("nested scene and exceptional callback restore exact outer/main projection, modelview, native uniforms, scissor and pipeline-cache owners");
                captured.options().improvedTransparency = true;
                boolean rejected = false;
                try { scene.render(output, new Matrix4f(), ProjectionType.ORTHOGRAPHIC, new Matrix4f(), a, nodes -> { throw new AssertionError("OIT callback must not run"); }); }
                catch (UnsupportedOperationException expected) { rejected = true; }
                check(rejected, "Requested OIT must fail explicitly"); captured.options().improvedTransparency = false;
                before.assertRestored(callerCache);
                scene.close(); nested.close();
                check(!output.isClosed() && !lightA.color(0).view().isClosed() && !lightB.color(0).view().isClosed(), "Scene disposal closed borrowed resources");
                checks.add("requested OIT is explicit capability failure before callbacks; scene retirement preserves borrowed targets/lightmaps");
            } finally { RenderSystem.setCurrentPipelineCache(oldCache); }
        }
    }
    private static void recovery(Minecraft client, GpuDevice device, RenderType type, NativeFeatureScene.Inputs inputs, List<String> checks) {
        try (var scene = new NativeFeatureScene(client, client.getResourceManager());
             var output = GpuFramebuffer.builder("partial feature preparation recovery", 2, 2).color(GpuFormat.RGBA8_UNORM).depth(GpuFormat.D32_FLOAT).build(device);
             var source = new GpuShaderSource(client.getResourceManager());
             var caller = new PipelineCache(device, source)) {
            var encoder = device.createCommandEncoder(); output.clear(encoder, new Vector4f(), 0); encoder.submit();
            var previous = RenderSystem.setCurrentPipelineCache(caller);
            try {
                NativeState before = NativeState.capture(); boolean failed = false;
                try {
                    scene.render(output, new Matrix4f(), ProjectionType.ORTHOGRAPHIC, new Matrix4f(), inputs,
                            nodes -> nodes.submitCustomGeometry(new PoseStack(), type, (pose, vertices) -> {
                                throw new IllegalStateException("deliberate lazy geometry failure");
                            }));
                } catch (RuntimeException expected) {
                    for (Throwable cause = expected; cause != null; cause = cause.getCause())
                        if ("deliberate lazy geometry failure".equals(cause.getMessage())) failed = true;
                }
                check(failed, "Lazy producer did not fail during actual native feature preparation"); before.assertRestored(caller);
                scene.render(output, new Matrix4f(), ProjectionType.ORTHOGRAPHIC, new Matrix4f(), inputs,
                        nodes -> quad(nodes, type, 255, 0, 0, 255));
                before.assertRestored(caller);
                try (var readback = device.createBuffer(() -> "recovered scene pixels", GpuBuffer.USAGE_COPY_DST | GpuBuffer.USAGE_MAP_READ, 16)) {
                    var copy = device.createCommandEncoder(); copy.copyTextureToBuffer(output.color(0).texture(), readback, 0, () -> {}, 0);
                    try (var fence = copy.createFence()) {
                        copy.submit(); check(fence.awaitCompletion(10_000_000_000L), "Recovery readback timed out");
                        try (var mapped = readback.map(true, false)) {
                            check((mapped.data().get(0) & 255) > 0 && (mapped.data().get(1) & 255) == 0 && (mapped.data().get(2) & 255) == 0,
                                    "Next real native frame retained partial geometry state or failed to draw");
                        }
                    }
                }
                checks.add("lazy custom-geometry preparation failure closes actual partial native PreparedFrame; exact settings restored and same scene renders next frame successfully");
            } finally { RenderSystem.setCurrentPipelineCache(previous); }
        }
    }
    private static void quad(SubmitNodeCollector nodes, RenderType type, int r, int g, int b, int a) {
        nodes.submitCustomGeometry(new PoseStack(), type, (pose, vertices) -> {
            for (float[] xy : new float[][]{{-1,-1},{1,-1},{1,1},{-1,1}})
                vertices.addVertex(pose, xy[0], xy[1], .5f).setColor(r,g,b,a).setUv(0,0)
                        .setOverlay(OverlayTexture.NO_OVERLAY).setLight(0xF000F0).setNormal(0,0,0);
        });
    }
    private record NativeState(com.mojang.renderpearl.api.buffers.GpuBufferSlice projection, ProjectionType type,
                               com.mojang.renderpearl.api.buffers.GpuBufferSlice fog, com.mojang.renderpearl.api.buffers.GpuBufferSlice lights,
                               GpuBuffer globals, Matrix4f modelView, ScissorState scissor) {
        static NativeState capture() { return new NativeState(RenderSystem.getProjectionMatrixBuffer(), RenderSystem.getProjectionType(), RenderSystem.getShaderFog(), RenderSystem.getShaderLights(), RenderSystem.getGlobalSettingsUniform(), RenderSystem.getModelViewMatrixCopy(), new ScissorState(RenderSystem.getScissorStateForRenderTypeDraws())); }
        void assertRestored(PipelineCache callerCache) {
            check(RenderSystem.getProjectionMatrixBuffer()==this.projection && RenderSystem.getProjectionType()==this.type
                    && RenderSystem.getShaderFog()==this.fog && RenderSystem.getShaderLights()==this.lights && RenderSystem.getGlobalSettingsUniform()==this.globals
                    && RenderSystem.getModelViewMatrixCopy().equals(this.modelView, 0) && NativeFeatureScene.scopedLightmap()==null, "Main native matrices/defaults changed");
            var current = RenderSystem.getScissorStateForRenderTypeDraws();
            check(current.enabled()==this.scissor.enabled() && current.x()==this.scissor.x() && current.y()==this.scissor.y()
                    && current.width()==this.scissor.width() && current.height()==this.scissor.height(), "Main native scissor changed");
            var observed = RenderSystem.setCurrentPipelineCache(callerCache); check(observed==callerCache,"Main native pipeline cache changed");
        }
    }
    private static void assertPixels(GpuDevice device, GpuFramebuffer output, int[] left, int[] right) {
        try (var buffer = device.createBuffer(() -> "feature scene pixels", GpuBuffer.USAGE_COPY_DST|GpuBuffer.USAGE_MAP_READ, 32)) {
            var encoder=device.createCommandEncoder(); encoder.copyTextureToBuffer(output.color(0).texture(),buffer,0,()->{},0);
            try (var fence=encoder.createFence()) { encoder.submit();check(fence.awaitCompletion(10_000_000_000L),"Scene readback timed out");
                try (var mapped=buffer.map(true,false)) { for(int y=0;y<2;y++)for(int x=0;x<4;x++)for(int c=0;c<4;c++) {
                    int actual=mapped.data().get((y*4+x)*4+c)&255, expected=(x<2?left:right)[c];
                    check(Math.abs(actual-expected)<=2,"Scene pixel "+x+","+y+","+c+" was "+actual+", expected "+expected);
                }}
            }
        }
    }
    private static void check(boolean condition,String message){if(!condition)throw new AssertionError(message);}
}
