import com.mojang.blaze3d.pipeline.PipelineCache;
import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.commands.CommandEncoder;
import com.mojang.renderpearl.api.device.GpuDevice;
import com.mojang.renderpearl.api.pipeline.*;
import foundry.veil.api.client.render.VeilShaderBufferLayout;
import foundry.veil.api.client.render.dynamicbuffer.DynamicBufferType;
import foundry.veil.api.client.render.gpu.*;
import foundry.veil.api.client.render.shader.*;
import foundry.veil.impl.client.render.LayoutSerializer;
import foundry.veil.impl.client.render.dynamicbuffer.*;
import foundry.veil.impl.client.render.pipeline.VeilShaderBlockState;
import foundry.veil.impl.client.render.shader.injection.*;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.Identifier;
import net.minecraft.util.profiling.InactiveProfiler;
import org.joml.Vector4f;

import java.nio.ByteOrder;
import java.util.*;

/** Real registered layout expansion and named global UBO snapshots across native, managed and dynamic pipelines. */
public final class GpuSourceBlocksAcceptance {
    public static void run(Minecraft client, GpuDevice device, List<String> checks) {
        var layout = VeilShaderBufferLayout.<Vector4f>builder().name("RegisteredNativeTint").vec4("Tint", value -> value).build();
        var layoutId = Identifier.parse("veil_test:registered_tint");
        NativeShaderBlocks.bindRuntime(() -> Map.of(layoutId, layout));
        var definitions = new ShaderPreDefinitions();
        class Managed extends ShaderManager {
            Managed() { super(device, ShaderManager.PROGRAM_SET, definitions); }
            void load() { var resources = client.getResourceManager(); this.apply(this.prepare(resources, InactiveProfiler.INSTANCE), resources, InactiveProfiler.INSTANCE); }
        }
        var blocks = new VeilShaderBlockState();
        var compiler = new VanillaShaderCompiler(device, Runnable::run, Runnable::run);
        var dynamic = new DynamicBufferManager(device, client.getResourceManager(), 2, 2, compiler, ignored -> {});
        var service = new NativeShaderInjectionService(new ShaderInjectionManager(), Map::of, definitions);
        NativePipelineBridge.bindRuntime(definitions, service, List.of()); NativeDynamicBuffers.bindRuntime(dynamic);
        try (compiler; dynamic; service; var managed = new Managed(); var selected = LayoutSerializer.create(layout, device);
             var frames = new NativeShaderInjectionFrames(service, device, ignored -> null, blocks::snapshot, NativeShaderBlocks::layoutNames);
             var source = new GpuShaderSource(client.getResourceManager()); var cache = new PipelineCache(device, source);
             var output = GpuFramebuffer.builder("source block native output", 2, 2).color(GpuFormat.RGBA8_UNORM).build(device);
             var managedOutput = GpuFramebuffer.builder("source block managed output", 2, 2).color(GpuFormat.RGBA8_UNORM).build(device)) {
            NativeShaderInjectionFrames.bindRuntime(frames);
            var state = RenderPipeline.builder().withLocation(Identifier.parse("veil_test:source_blocks"))
                    .withVertexShader(Identifier.parse("veil:core/dynamic_coverage")).withFragmentShader(Identifier.parse("veil:core/gpu_source_blocks"))
                    .withPrimitiveTopology(PrimitiveTopology.TRIANGLES).withCull(false).withColorTargetState(ColorTargetState.DEFAULT).build();
            managed.load(); var program = managed.getShader(Identifier.parse("veil:gpu_source_blocks"), state, Runnable::run);
            check(program.hasUniform(layout.name()) && program.bindingTypes().get(layout.name()) == UniformType.UNIFORM_BUFFER,
                    "A real #veil:buffer include must add its generated named UBO to the actual managed pipeline schema without JSON uniform_blocks");
            var nativePipeline = cache.get(state);
            check(NativePipelineBridge.actualDefinition(nativePipeline).orElseThrow().getBindGroupLayouts().stream()
                            .flatMap(binding -> binding.uniforms().stream()).anyMatch(uniform -> uniform.name().equals(layout.name()) && uniform.type() == UniformType.UNIFORM_BUFFER),
                    "The actual native PipelineCache must receive the generated layout binding");
            selected.set(new Vector4f(1,0,0,.125f)); blocks.bind(layout.name(), selected);
            var encoder = device.createCommandEncoder(); var reads = new ArrayList<GpuBuffer>();
            try (var lease = program.retain()) {
                try (var outer = frames.beginFrame()) {
                    selected.set(new Vector4f(0,0,1,.75f));
                    try (var pass = output.beginPass(encoder)) { pass.setPipeline(nativePipeline); pass.draw(3,1,0,0); }
                    reads.add(read(device, encoder, output.color(0).texture(), 16));
                    try (var pass = managedOutput.beginPass(encoder)) { program.bind(pass); pass.draw(3,1,0,0); }
                    reads.add(read(device, encoder, managedOutput.color(0).texture(), 16));
                    try (var inner = frames.beginFrame()) {
                        selected.free();
                        dynamic.setActiveBuffers(Identifier.parse("veil_test:source_blocks"), DynamicBufferType.DEBUG.getMask());
                        try (var world = NativeDynamicBuffers.beginWorld(managedOutput.color(0).view(), null)) {
                            try (var pass = managedOutput.beginPass(encoder)) { program.bind(pass); pass.draw(3,1,0,0); }
                            reads.add(read(device, encoder, dynamic.getBufferTexture(DynamicBufferType.DEBUG).texture(), 32));
                        }
                    }
                    try (var pass = output.beginPass(encoder)) { pass.setPipeline(nativePipeline); pass.draw(3,1,0,0); }
                    reads.add(read(device, encoder, output.color(0).texture(), 16));
                }
                check(!NativeShaderInjectionFrames.hasNamedBinding(layout.name()), "Closed native source-block frame leaked its named UBO selection");
                frames.close(); service.close(); managed.close(); cache.close(); source.close(); dynamic.close(); compiler.close();
                try (var fence = encoder.createFence()) { encoder.submit(); check(fence.awaitCompletion(10_000_000_000L), "Generated source-block GPU readback timed out"); }
                assertPixels(reads.get(0), false, 1,0,0,.125f); assertPixels(reads.get(1), false, 1,0,0,.125f);
                assertPixels(reads.get(2), true, 0,0,1,.75f); assertPixels(reads.get(3), false, 1,0,0,.125f);
            } finally { reads.forEach(GpuBuffer::close); }
        } finally { blocks.clear(); NativeDynamicBuffers.unbindRuntime(dynamic); NativePipelineBridge.unbindRuntime(); NativeShaderBlocks.unbindRuntime(); }
        checks.add("real registered std140 #veil:buffer include expands without JSON uniform_blocks and adds actual native cache/managed binding layouts; named frame UBOs render frozen red, nested blue dynamic debug and restored red after original block mutation/free, then survive source/cache/service/frame/manager retirement through native submission");
    }
    private static GpuBuffer read(GpuDevice device, CommandEncoder encoder, com.mojang.renderpearl.api.textures.GpuTexture texture, int bytes) {
        var read = device.createBuffer(() -> "registered source block exact pixel readback", GpuBuffer.USAGE_COPY_DST | GpuBuffer.USAGE_MAP_READ, bytes);
        encoder.copyTextureToBuffer(texture, read, 0, () -> {}, 0); return read;
    }
    private static void assertPixels(GpuBuffer read, boolean half, float... values) {
        try (var mapping = read.map(true,false)) {
            var bytes = mapping.data().order(ByteOrder.nativeOrder());
            for (int index = 0; index < bytes.remaining() / (half ? 2 : 1); index++) {
                float actual = half ? Float.float16ToFloat(bytes.getShort(index * 2)) : (bytes.get(index) & 255) / 255f;
                check(Math.abs(actual - values[index % 4]) < .005f, "Generated source block component " + index + " was " + actual + ", expected " + values[index % 4]);
            }
        }
    }
    private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
}
