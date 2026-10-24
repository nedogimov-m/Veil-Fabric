import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.device.GpuDevice;
import com.mojang.renderpearl.api.pipeline.*;
import foundry.veil.api.client.render.dynamicbuffer.DynamicBufferType;
import foundry.veil.api.client.render.gpu.GpuFramebuffer;
import foundry.veil.api.client.render.shader.ShaderManager;
import foundry.veil.api.client.render.shader.ShaderPreDefinitions;
import foundry.veil.api.client.render.shader.processor.ShaderPreProcessor;
import foundry.veil.api.client.render.shader.program.ProgramDefinition;
import foundry.veil.impl.client.render.dynamicbuffer.*;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.ShaderDefines;
import net.minecraft.resources.Identifier;
import net.minecraft.util.profiling.InactiveProfiler;

import java.nio.ByteOrder;
import java.util.*;

/** Managed raw roots must be replayed before mask conditionals discard either roots or their includes. */
public final class GpuManagedReplayAcceptance {
    public static void run(Minecraft client, GpuDevice device, List<String> checks) {
        var definitions = new ShaderPreDefinitions();
        class Managed extends ShaderManager {
            Managed() { super(device, ShaderManager.PROGRAM_SET, definitions); }
            void load() {
                var resources = client.getResourceManager();
                this.apply(this.prepare(resources, InactiveProfiler.INSTANCE), resources, InactiveProfiler.INSTANCE);
            }
        }
        var shader = Identifier.parse("veil:gpu_managed_replay");
        var defines = new ShaderDefines(Map.of("NATIVE_TINT", "vec4(0.125, 0.25, 0.5, 1.0)"), Set.of("MANAGED_NATIVE_STATE"));
        var snippet = new RenderPipeline.Snippet(Map.of(), Optional.of(defines), Optional.empty(), new ColorTargetState[0], 0,
                Optional.empty(), Optional.empty(), Optional.empty(), new com.mojang.renderpearl.api.vertex.VertexFormat[0], Optional.empty(), 0);
        var state = RenderPipeline.builder(snippet).withLocation(Identifier.parse("veil_test:managed_replay_state"))
                .withVertexShader(shader).withFragmentShader(shader).withPrimitiveTopology(PrimitiveTopology.TRIANGLES)
                .withCull(false).withColorTargetState(ColorTargetState.DEFAULT).build();
        var compiler = new VanillaShaderCompiler(device, Runnable::run, Runnable::run);
        var manager = new DynamicBufferManager(device, client.getResourceManager(), 2, 2, compiler, ignored -> {});
        var visits = new ArrayList<String>();
        ProgramDefinition[] expected = new ProgramDefinition[1];
        NativeDynamicBuffers.bindRuntime(manager);
        try (compiler; manager; var shaders = new Managed();
             var baseOutput = GpuFramebuffer.builder("managed replay native base", 2, 2).color(GpuFormat.RGBA8_UNORM).build(device);
             var first = GpuFramebuffer.builder("managed replay debug world", 2, 2).color(GpuFormat.RGBA8_UNORM).build(device);
             var second = GpuFramebuffer.builder("managed replay material world", 2, 2).color(GpuFormat.RGBA8_UNORM).build(device)) {
            shaders.addPreprocessorRegistry((resources, registry) -> registry.addPreprocessor((context, tree) -> {
                check(context instanceof ShaderPreProcessor.VeilContext veil && veil.definition() == expected[0],
                        "Every replay must preserve the actual managed ProgramDefinition");
                if (context.activeBuffers() != 0) {
                    check(context instanceof ShaderPreProcessor.MinecraftContext nativeContext
                                    && nativeContext.pipeline().getShaderDefines().equals(defines),
                            "Managed dynamic replay must also expose the actual native pipeline and its string defines");
                }
                if (context.isSourceFile() && context.isVertex()) context.customProgramData().put("managed_mask", context.activeBuffers());
                if (context.isSourceFile() && context.isFragment())
                    check(Objects.equals(context.customProgramData().get("managed_mask"), context.activeBuffers()),
                            "Each replay needs fresh program data shared between its actual vertex and fragment stages");
                visits.add(context.activeBuffers() + ":" + context.isSourceFile() + ":" + context.name());
            }, true));
            shaders.load(); expected[0] = shaders.getDefinitions().get(shader);
            shaders.setActiveBuffers(DynamicBufferType.encode(DynamicBufferType.values()));
            check(expected[0] != null, "Managed replay JSON was not loaded by the real ShaderManager");
            var program = shaders.getShader(shader, state, Runnable::run);
            check(program.definition().getShaderDefines().equals(defines), "Managed native pipeline lost source state shader defines");
            check(visits.size() == 2 && visits.stream().allMatch(visit -> visit.startsWith("0:true:")),
                    "Mask-zero base processing must eliminate both dynamic branches and the conditional import");
            var encoder = device.createCommandEncoder();
            try (var pass = baseOutput.beginPass(encoder)) { program.bind(pass); pass.draw(3, 1, 0, 0); }
            encoder.submit();
            assertPixels(device, baseOutput.color(0).texture(), false, .125f, .25f, .5f, 1);
            int debugMask = DynamicBufferType.DEBUG.getMask();
            int materialMask = debugMask | DynamicBufferType.ALBEDO.getMask();
            var request = Identifier.parse("veil_test:managed_replay");
            manager.setActiveBuffers(request, debugMask);
            encoder = device.createCommandEncoder();
            com.mojang.renderpearl.api.textures.GpuTextureView debugA, debugB, albedo;
            var reads = new ArrayList<GpuBuffer>();
            try {
                try (var world = NativeDynamicBuffers.beginWorld(first.color(0).view(), null)) {
                    debugA = manager.getBufferTexture(DynamicBufferType.DEBUG);
                    try (var pass = first.beginPass(encoder)) { program.bind(pass); pass.draw(3, 1, 0, 0); }
                    reads.add(read(device, encoder, debugA.texture(), 32));
                }
                manager.setActiveBuffers(request, materialMask);
                try (var world = NativeDynamicBuffers.beginWorld(second.color(0).view(), null)) {
                    debugB = manager.getBufferTexture(DynamicBufferType.DEBUG); albedo = manager.getBufferTexture(DynamicBufferType.ALBEDO);
                    try (var pass = second.beginPass(encoder)) { program.bind(pass); pass.draw(3, 1, 0, 0); }
                    reads.add(read(device, encoder, debugB.texture(), 32)); reads.add(read(device, encoder, albedo.texture(), 16));
                }
                check(compiler.variantCount() == 2 && debugA != debugB,
                        "Two queued managed masks must use distinct native variants and retained attachment storage");
                check(visits.stream().anyMatch(visit -> visit.startsWith(materialMask + ":false:") && visit.contains("gpu_managed_replay_material"))
                                && visits.stream().noneMatch(visit -> !visit.startsWith(materialMask + ":") && visit.contains("gpu_managed_replay_material")),
                        "The albedo-only conditional import must be restored from raw text only for its selected mask");
                shaders.close(); manager.close(); compiler.close();
                check(!debugA.isClosed() && !debugB.isClosed() && !albedo.isClosed() && !compiler.retirement().isDone(),
                        "Managed source/base/storage owner retirement must preserve both queued masks through submission");
                try (var fence = encoder.createFence()) {
                    encoder.submit(); check(fence.awaitCompletion(10_000_000_000L), "Managed replay submission timed out");
                }
                assertPixels(reads.get(0), true, .25f, .5f, .75f, .125f);
                assertPixels(reads.get(1), true, .25f, .5f, .75f, .125f);
                assertPixels(reads.get(2), false, .2f, .4f, .6f, .8f);
                check(debugA.isClosed() && debugB.isClosed() && albedo.isClosed() && compiler.retirement().isDone(),
                        "Actual submission must release both retired managed variants and their attachment owners");
            } finally { reads.forEach(GpuBuffer::close); }
        } finally { NativeDynamicBuffers.unbindRuntime(manager); }
        checks.add("real ShaderManager JSON preserves native flags and arbitrary string defines; mask-zero base removes conditional roots/includes; two automatically selected native world masks replay raw managed roots and conditional material import with Veil+Minecraft contexts, exact RGBA16F debug W and independent submission-owned resources");
    }
    private static GpuBuffer read(GpuDevice device, com.mojang.renderpearl.api.commands.CommandEncoder encoder,
            com.mojang.renderpearl.api.textures.GpuTexture texture, int size) {
        var read = device.createBuffer(() -> "managed replay exact pixel readback", GpuBuffer.USAGE_COPY_DST | GpuBuffer.USAGE_MAP_READ, size);
        encoder.copyTextureToBuffer(texture, read, 0, () -> {}, 0); return read;
    }
    private static void assertPixels(GpuDevice device, com.mojang.renderpearl.api.textures.GpuTexture texture, boolean half, float... values) {
        var encoder = device.createCommandEncoder();
        try (var read = read(device, encoder, texture, half ? 32 : 16); var fence = encoder.createFence()) {
            encoder.submit(); check(fence.awaitCompletion(10_000_000_000L), "Managed replay base readback timed out"); assertPixels(read, half, values);
        }
    }
    private static void assertPixels(GpuBuffer buffer, boolean half, float... expected) {
        try (var mapped = buffer.map(true, false)) {
            var bytes = mapped.data().order(ByteOrder.nativeOrder());
            int count = bytes.remaining() / (half ? 2 : 1);
            for (int index = 0; index < count; index++) {
                float actual = half ? Float.float16ToFloat(bytes.getShort(index * 2)) : (bytes.get(index) & 255) / 255f;
                check(Math.abs(actual - expected[index % 4]) < .005f,
                        "Managed raw replay pixel component " + index + " was " + actual + ", expected " + expected[index % 4]);
            }
        }
    }
    private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
}
