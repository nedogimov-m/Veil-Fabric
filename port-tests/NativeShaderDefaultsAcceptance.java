import com.google.gson.GsonBuilder;
import com.mojang.blaze3d.pipeline.PipelineCache;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.pipeline.*;
import foundry.veil.api.client.render.gpu.*;
import foundry.veil.api.client.render.shader.*;
import foundry.veil.api.client.render.shader.program.ProgramDefinition;
import foundry.veil.impl.client.render.shader.injection.*;
import foundry.veil.impl.client.render.shader.processor.NativeShaderProcessor;
import foundry.veil.impl.client.render.shader.program.ShaderProgramImpl;
import it.unimi.dsi.fastutil.ints.Int2ObjectArrayMap;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.Identifier;
import org.joml.Matrix4f;

import java.nio.ByteOrder;
import java.nio.file.*;
import java.util.*;
import java.util.function.Supplier;

public final class NativeShaderDefaultsAcceptance implements ClientModInitializer {
    private boolean ran; private int ticks;
    @Override public void onInitializeClient() { ClientTickEvents.END_CLIENT_TICK.register(client -> {
        if (ran || ++ticks < 5 || !client.isGameLoadFinished()) return; ran = true;
        var report = new LinkedHashMap<String, Object>();
        try {
            report.put("backend", RenderSystem.getDevice().getDeviceInfo().backendName());
            report.put("device", RenderSystem.getDevice().getDeviceInfo().name());
            run(client, report); report.put("status", "passed");
        } catch (Throwable failure) { failure.printStackTrace(); report.put("status", "failed"); report.put("error", failure.toString()); }
        finally {
            try { Files.writeString(Path.of(System.getProperty("veil.defaults.report")), new GsonBuilder().setPrettyPrinting().create().toJson(report)); }
            catch (Exception failure) { throw new RuntimeException(failure); }
            client.stop();
        }
    }); }

    private static void run(Minecraft client, Map<String, Object> report) {
        var device = RenderSystem.getDevice(); var definitions = new ShaderPreDefinitions();
        var id = Identifier.parse("veil_test:native_defaults");
        var definition = new ProgramDefinition(id, null, null, null, id, null, new String[0], Map.of(), Map.of(), Set.of(), new Int2ObjectArrayMap<>(), new ShaderFeature[0], null);
        var processor = new NativeShaderProcessor(client.getResourceManager(), definitions, definition, Map.of("MANAGED_DEFAULTS", "1"), new HashSet<>(), 0, List.of());
        var pipeline = RenderPipeline.builder().withLocation(id).withVertexShader(id).withFragmentShader(id)
                .withPrimitiveTopology(PrimitiveTopology.TRIANGLES).withCull(false).withColorTargetState(new ColorTargetState(Optional.empty(), GpuFormat.RGBA16_FLOAT, ColorTargetState.WRITE_ALL)).build();
        var transformsLayout = GpuUniformBuffer.layoutBuilder().field("ModelViewMat", GpuUniformBuffer.ValueType.MAT4)
                .field("TextureMat", GpuUniformBuffer.ValueType.MAT4).field("ColorModulator", GpuUniformBuffer.ValueType.VEC4)
                .field("ModelOffset", GpuUniformBuffer.ValueType.VEC3).build();
        float[] faces = {.1f, .2f, .6f, .4f, .5f, .7f};
        var value = new NativeShaderDefaults.Values[]{NativeShaderDefaults.Values.capture(900_000, direction -> faces[direction.get3DDataValue()])};
        Supplier<NativeShaderDefaults.Values> provider = () -> value[0]; NativeShaderDefaults.bindRuntime(provider);
        try (var service = new NativeShaderInjectionService(new ShaderInjectionManager(), Map::of, definitions);
             var frames = new NativeShaderInjectionFrames(service, device, ignored -> null, ignored -> Map.of());
             var managedSource = new GpuShaderSource(client.getResourceManager(), null, "shaders/include", Map.of(), processor);
             var managed = new ShaderProgramImpl(device, pipeline, managedSource, Runnable::run, definition, Set.of());
             var nativeSource = new GpuShaderSource(client.getResourceManager()); var nativeCache = new PipelineCache(device, nativeSource);
             var outer = GpuFramebuffer.builder("native defaults outer", 2, 2).color(GpuFormat.RGBA16_FLOAT).build(device);
             var inner = GpuFramebuffer.builder("native defaults child", 2, 2).color(GpuFormat.RGBA16_FLOAT).build(device);
             var restored = GpuFramebuffer.builder("native defaults restored", 2, 2).color(GpuFormat.RGBA16_FLOAT).build(device);
             var first = new GpuUniformBuffer(device, "native default actual draw transform A", transformsLayout);
             var second = new GpuUniformBuffer(device, "native default actual draw transform B", transformsLayout)) {
            NativePipelineBridge.bindRuntime(definitions, service, List.of()); NativeShaderInjectionFrames.bindRuntime(frames);
            check(managed.bindingTypes().get(NativeShaderDefaults.BLOCK) == UniformType.UNIFORM_BUFFER && managed.bindingTypes().get("DynamicTransforms") == UniformType.UNIFORM_BUFFER, "Actual typed defaults/native draw bindings generated from source");
            check(managed.getUniform("UserGain") != null && managed.getUniform("UserGain").getFloat() == 0, "Ordinary managed numeric fields retain actual zeroed owned storage");
            managed.getUniform("UserGain").setFloat(1);
            check(managed.getUniform("VeilRenderTime") == null && managed.getUniform("NormalMat") == null, "Automatic defaults are native view/draw data, not stale owned numeric fields");
            first.setMatrix("ModelViewMat", new Matrix4f().scale(2, 1, 1));
            second.setMatrix("ModelViewMat", new Matrix4f().scale(1, 2, 1));
            var encoder = device.createCommandEncoder(); first.upload(encoder); second.upload(encoder); managed.prepare(encoder);
            var compiled = nativeCache.get(pipeline);
            try (var parent = frames.beginFrame()) {
                // Changing the live provider and source array after entry cannot mutate the captured parent UBO.
                faces[2] = 0;
                value[0] = NativeShaderDefaults.Values.capture(1_800_000, direction -> .3);
                managed.setUniformBlock("DynamicTransforms", first.buffer().slice());
                try (var pass = outer.beginPass(encoder)) { managed.bind(pass); pass.draw(3, 1, 0, 0); }
                try (var childOverride = NativeShaderDefaults.begin(value[0]); var child = frames.beginFrame()) {
                    try (var pass = inner.beginPass(encoder)) { pass.setPipeline(compiled); pass.setUniform("DynamicTransforms", second.buffer().slice()); pass.draw(3, 1, 0, 0); }
                    encoder.submit();
                }
                encoder = device.createCommandEncoder();
                try (var pass = restored.beginPass(encoder)) { pass.setPipeline(compiled); pass.setUniform("DynamicTransforms", first.buffer().slice()); pass.draw(3, 1, 0, 0); }
            }
            // Retire frame owners before the real final submit: pass/submission leases retain their native UBOs.
            frames.close();
            try (var fence = encoder.createFence()) { encoder.submit(); check(fence.awaitCompletion(10_000_000_000L), "Native default submission timed out"); }
            pixels(outer, .7236068f, .9472136f, .6f, .25f);
            pixels(inner, .9472136f, .7236068f, .3f, .5f);
            pixels(restored, .7236068f, .9472136f, .6f, .25f);
            var invalid = new NativeShaderProcessor(client.getResourceManager(), definitions, definition, Map.of(), new HashSet<>(), 0, List.of());
            boolean rejected = false;
            try { invalid.process(id, ShaderType.VERTEX, "#version 330\nuniform vec4 VeilRenderTime;void main(){gl_Position=VeilRenderTime;}"); }
            catch (IllegalArgumentException expected) { rejected = true; }
            check(rejected, "Mismatched reserved automatic default types must fail before native install");
            report.put("checks", List.of("exact authored default declarations become real generated native bindings while ordinary numeric fields stay owned", "managed and actual native cache shaders evaluate inverse-transpose of each nonuniform ModelViewMat", "time modulo3600 and Direction-indexed shades captured per view survive mutation, child submit, parent restore, frame retirement and final submit", "wrong automatic default declaration rejected transactionally"));
        } finally { NativePipelineBridge.flush(); NativePipelineBridge.unbindRuntime(); NativeShaderDefaults.unbindRuntime(provider); }
    }

    private static void pixels(GpuFramebuffer target, float... expected) {
        var device = RenderSystem.getDevice(); var encoder = device.createCommandEncoder();
        try (var read = device.createBuffer(() -> "native defaults exact half-float pixels", GpuBuffer.USAGE_COPY_DST | GpuBuffer.USAGE_MAP_READ, 32)) {
            encoder.copyTextureToBuffer(target.color(0).texture(), read, 0, () -> {}, 0);
            try (var fence = encoder.createFence()) { encoder.submit(); check(fence.awaitCompletion(10_000_000_000L), "Default readback timed out"); }
            try (var mapped = read.map(true, false)) {
                var bytes = mapped.data().order(ByteOrder.nativeOrder());
                for (int i = 0; i < 16; i++) check(Math.abs(Float.float16ToFloat(bytes.getShort(i * 2)) - expected[i % 4]) < .003f, "Incorrect native default pixel component " + i + ": " + Float.float16ToFloat(bytes.getShort(i * 2)) + " expected " + expected[i % 4]);
            }
        }
    }
    private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
}
