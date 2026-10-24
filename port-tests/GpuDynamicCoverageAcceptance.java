import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.buffers.*;
import com.mojang.renderpearl.api.commands.*;
import com.mojang.renderpearl.api.device.GpuDevice;
import com.mojang.renderpearl.api.pipeline.*;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import foundry.veil.api.client.render.dynamicbuffer.DynamicBufferType;
import foundry.veil.api.client.render.gpu.*;
import foundry.veil.impl.client.render.dynamicbuffer.*;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.Identifier;
import org.joml.Vector4f;

import java.nio.*;
import java.util.*;

/** Actual automatic ordinary passes must blend generic W with geometry alpha, including all five properties. */
public final class GpuDynamicCoverageAcceptance {
    private static RenderPipeline state(String suffix, BlendFunction blend, boolean partial) {
        var builder = RenderPipeline.builder().withLocation(Identifier.parse("veil_test:coverage_" + suffix))
                .withVertexShader(Identifier.parse("veil:core/dynamic_coverage"))
                .withFragmentShader(Identifier.parse("veil:core/gpu_coverage"))
                .withPrimitiveTopology(PrimitiveTopology.TRIANGLES).withCull(false)
                .withBindGroupLayout(BindGroupLayout.builder().withUniform("Properties", UniformType.UNIFORM_BUFFER).build())
                .withColorTargetState(new ColorTargetState(Optional.ofNullable(blend), GpuFormat.RGBA8_UNORM, ColorTargetState.WRITE_ALL));
        if (partial) builder.withShaderDefine("SKIP_ALBEDO"); return builder.build();
    }
    public static void run(Minecraft client, GpuDevice device, List<String> checks) {
        int all = DynamicBufferType.encode(DynamicBufferType.values());
        var compiler = new VanillaShaderCompiler(device, Runnable::run, Runnable::run);
        var manager = new DynamicBufferManager(device, client.getResourceManager(), 2, 2, compiler, ignored -> {});
        NativeDynamicBuffers.bindRuntime(manager);
        try (compiler; manager; var source = new GpuShaderSource(client.getResourceManager());
             var opaque = new GpuShaderProgram(device, state("opaque", null, false), source, Runnable::run);
             var translucent = new GpuShaderProgram(device, state("translucent", BlendFunction.TRANSLUCENT, false), source, Runnable::run);
             var partial = new GpuShaderProgram(device, state("partial", BlendFunction.TRANSLUCENT, true), source, Runnable::run);
             var additive = new GpuShaderProgram(device, state("additive", BlendFunction.LIGHTNING, true), source, Runnable::run);
             var output = GpuFramebuffer.builder("native classic generic property world", 2, 2).color(GpuFormat.RGBA8_UNORM).build(device)) {
            check(NativeDynamicCoverage.extraTargetCount(all) + 1 == 8, "All five ordinary properties must fit native eight attachments");
            source.close();
            manager.setActiveBuffers(Identifier.parse("veil_test:coverage"), all);
            var encoder = device.createCommandEncoder();
            var first = properties(device, encoder, .25f, new Vector4f(.1f,.2f,.3f,.4f), new Vector4f(.5f,.25f,.125f,2));
            var second = properties(device, encoder, .25f, new Vector4f(.8f,.4f,.2f,.9f), new Vector4f(-2,3,.75f,-4));
            var third = properties(device, encoder, .5f, new Vector4f(.3f,.7f,.5f,.2f), new Vector4f(4,-1,2,6));
            var fourth = properties(device, encoder, .25f, new Vector4f(1), new Vector4f(-4,2,-2,-8));
            var fifth = properties(device, encoder, .25f, new Vector4f(1), new Vector4f(-2,1,4,-4));
            var reads = new ArrayList<GpuBuffer>(); var views = new ArrayList<GpuTextureView>();
            try {
                try (var world = NativeDynamicBuffers.beginWorld(output.color(0).view(), null)) {
                    try (var pass = output.beginPass(encoder)) {
                        opaque.bind(pass, Map.of("Properties", first)); pass.draw(3,1,0,0);
                        translucent.bind(pass, Map.of("Properties", second)); pass.draw(3,1,0,0);
                        translucent.bind(pass, Map.of("Properties", third)); pass.draw(3,1,0,0);
                    }
                    reads.add(read(device, encoder, manager.getBufferTexture(DynamicBufferType.DEBUG), 32));
                    try (var pass = output.beginPass(encoder)) { partial.bind(pass, Map.of("Properties", fourth)); pass.draw(3,1,0,0); }
                    reads.add(read(device, encoder, manager.getBufferTexture(DynamicBufferType.DEBUG), 32));
                    reads.add(read(device, encoder, manager.getBufferTexture(DynamicBufferType.ALBEDO), 16));
                    try (var pass = output.beginPass(encoder)) { additive.bind(pass, Map.of("Properties", fifth)); pass.draw(3,1,0,0); }
                    reads.add(read(device, encoder, manager.getBufferTexture(DynamicBufferType.DEBUG), 32));
                    for (var type : DynamicBufferType.values()) views.add(manager.getBufferTexture(type));
                    check(compiler.variantCount() == 4, "Each actual native blend/produced contract must have its own coverage variant");
                    manager.resizeFramebuffers(4,4); manager.close(); compiler.close(); opaque.close(); translucent.close(); partial.close(); additive.close();
                }
                check(views.stream().noneMatch(GpuTextureView::isClosed) && !compiler.retirement().isDone(), "Classic coverage owners retired before genuine submission");
                try (var fence = encoder.createFence()) { encoder.submit(); check(fence.awaitCompletion(10_000_000_000L), "Classic coverage GPU readback timed out"); }
                assertPixels(reads.get(0), true, 1.9375f, -.03125f, 1.140625f, 3.25f);
                assertPixels(reads.get(1), true, .453125f, .4765625f, .35546875f, .4375f);
                assertPixels(reads.get(2), false, .2875f, .475f, .3875f, .3625f);
                assertPixels(reads.get(3), true, -.046875f, .7265625f, 1.35546875f, -.5625f);
                check(views.stream().allMatch(GpuTextureView::isClosed) && compiler.retirement().isDone(), "Native submit did not release retired classic property owners");
                check(!output.color(0).view().isClosed(), "Classic property owner closed caller color target");
            } finally { reads.forEach(GpuBuffer::close); }
        } finally { NativeDynamicBuffers.unbindRuntime(manager); }
        checks.add("actual production ordinary pass interception blends all five properties in eight attachments with separate native geometry alpha and packed generic W; two same-pass translucent layers retain negative/>1 W, partial outputs preserve packed neighbors, source-alpha additive pixels and source/manager/compiler retirement through real submission pass");
    }
    private static GpuBufferSlice properties(GpuDevice device, CommandEncoder encoder, float alpha, Vector4f albedo, Vector4f debug) {
        var bytes = ByteBuffer.allocateDirect(6 * 16).order(ByteOrder.nativeOrder());
        for (var value : List.of(new Vector4f(.25f,.5f,.75f,alpha), albedo, new Vector4f(-.5f,.25f,.5f,-.25f), new Vector4f(.75f,.25f,0,.125f), new Vector4f(.75f,.5f,.25f,.5f), debug))
            bytes.putFloat(value.x).putFloat(value.y).putFloat(value.z).putFloat(value.w);
        bytes.flip(); return encoder.transientMemory().uploadGpu(bytes, device.getDeviceInfo().limits().minUniformOffsetAlignment(), GpuBuffer.USAGE_UNIFORM);
    }
    private static GpuBuffer read(GpuDevice device, CommandEncoder encoder, GpuTextureView view, int size) {
        var buffer = device.createBuffer(() -> "classic generic W exact readback", GpuBuffer.USAGE_COPY_DST | GpuBuffer.USAGE_MAP_READ, size);
        encoder.copyTextureToBuffer(view.texture(), buffer, 0, () -> {}, 0); return buffer;
    }
    private static void assertPixels(GpuBuffer read, boolean half, float... expected) {
        try (var mapping = read.map(true,false)) {
            var bytes = mapping.data().order(ByteOrder.nativeOrder());
            for (int index = 0; index < bytes.remaining() / (half ? 2 : 1); index++) {
                float actual = half ? Float.float16ToFloat(bytes.getShort(index * 2)) : (bytes.get(index) & 255) / 255f;
                check(Math.abs(actual - expected[index % 4]) < .01f, "Native classic property component " + index + " was " + actual + ", expected " + expected[index % 4]);
            }
        }
    }
    private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
}
