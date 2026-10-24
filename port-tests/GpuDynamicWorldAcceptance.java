import com.mojang.blaze3d.pipeline.PipelineCache;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.*;
import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.commands.RenderPassDescriptor;
import com.mojang.renderpearl.api.device.GpuDevice;
import com.mojang.renderpearl.api.pipeline.*;
import foundry.veil.api.client.render.dynamicbuffer.DynamicBufferType;
import foundry.veil.api.client.render.gpu.*;
import foundry.veil.api.client.render.texture.TextureFilter;
import foundry.veil.api.client.render.vertex.VertexArray;
import foundry.veil.impl.client.render.dynamicbuffer.*;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.resources.Identifier;
import org.joml.Matrix4f;
import org.joml.Vector4f;
import org.joml.Vector4fc;

import java.nio.ByteOrder;
import java.util.*;

/** Automatic production frontend interception, independent request masks and submission-owned native lifetimes. */
public final class GpuDynamicWorldAcceptance {
    public static void run(Minecraft client, GpuDevice device, List<String> checks) {
        int all = DynamicBufferType.encode(DynamicBufferType.values());
        var changes = new ArrayList<foundry.veil.api.client.render.dynamicbuffer.DynamicBuffersChange>();
        var compiler = new VanillaShaderCompiler(device, Runnable::run, Runnable::run);
        var manager = new DynamicBufferManager(device, client.getResourceManager(), 2, 2, compiler, changes::add);
        var ownerA = Identifier.parse("veil_test:world_a"); var ownerB = Identifier.parse("veil_test:world_b");
        NativeDynamicBuffers.bindRuntime(manager);
        try (compiler; manager;
             var source = new GpuShaderSource(client.getResourceManager());
             var cache = new PipelineCache(device, source);
             var directSource = new GpuShaderSource(client.getResourceManager());
             var direct = new GpuShaderProgram(device, RenderPipeline.builder().withLocation(Identifier.parse("veil_test:direct_dynamic"))
                     .withVertexShader(Identifier.parse("veil:core/gpu_world_dynamic_marker")).withFragmentShader(Identifier.parse("veil:core/gpu_world_dynamic_marker"))
                     .withPrimitiveTopology(PrimitiveTopology.TRIANGLES).withCull(false).withColorTargetState(0, ColorTargetState.DEFAULT).build(), directSource, Runnable::run);
             var output = GpuFramebuffer.builder("ordinary borrowed native world target", 2, 2).color(GpuFormat.RGBA8_UNORM).depth(GpuFormat.D32_FLOAT).build(device);
             var other = GpuFramebuffer.builder("unrelated offscreen pass", 2, 2).color(GpuFormat.RGBA8_UNORM).build(device);
             var atlas = GpuFramebuffer.builder("automatic world atlas", 16, 16).color(GpuFormat.RGBA8_UNORM).build(device);
             var lightmap = GpuFramebuffer.builder("automatic world lightmap", 16, 16).color(GpuFormat.RGBA8_UNORM).build(device);
             var sampler = TextureFilter.CLAMP.createSampler(device);
             var mesh = VertexArray.create(device, "automatic native world mesh"); var memory = new ByteBufferBuilder(2048);
             var projection = new GpuUniformBuffer(device, "automatic world projection", GpuUniformBuffer.layoutBuilder().field("ProjMat", GpuUniformBuffer.ValueType.MAT4).build());
             var transforms = new GpuUniformBuffer(device, "automatic world transforms", GpuUniformBuffer.layoutBuilder().field("ModelViewMat", GpuUniformBuffer.ValueType.MAT4)
                     .field("TextureMat", GpuUniformBuffer.ValueType.MAT4).field("ColorModulator", GpuUniformBuffer.ValueType.VEC4).field("ModelOffset", GpuUniformBuffer.ValueType.VEC3).build());
             var fog = new GpuUniformBuffer(device, "automatic world fog", GpuUniformBuffer.layoutBuilder().field("FogColor", GpuUniformBuffer.ValueType.VEC4)
                     .field("FogEnvironmentalStart", GpuUniformBuffer.ValueType.FLOAT).field("FogEnvironmentalEnd", GpuUniformBuffer.ValueType.FLOAT)
                     .field("FogRenderDistanceStart", GpuUniformBuffer.ValueType.FLOAT).field("FogRenderDistanceEnd", GpuUniformBuffer.ValueType.FLOAT)
                     .field("FogSkyEnd", GpuUniformBuffer.ValueType.FLOAT).field("FogCloudsEnd", GpuUniformBuffer.ValueType.FLOAT).build())) {
            check(manager.setActiveBuffers(ownerA, all), "First native world request was lost");
            check(!manager.setActiveBuffers(ownerB, DynamicBufferType.NORMAL.getMask()), "Redundant requests must not reconfigure native storage");
            check(!manager.setActiveBuffers(ownerA, all & ~DynamicBufferType.NORMAL.getMask()) && manager.getActiveBuffers() == all,
                    "A redundant source must retain its independent requested outputs");
            int beforeRejected = manager.getActiveBuffers();
            try { manager.setActiveBuffers(ownerA, 1 << 16); throw new AssertionError("Unknown native output accepted"); }
            catch (IllegalArgumentException expected) { check(manager.getActiveBuffers() == beforeRejected, "Rejected request lost active storage"); }
            check(changes.size() == 1, "Only a combined-mask change emits the actual buffer event");
            projection.setMatrix("ProjMat", new Matrix4f()); transforms.setMatrix("ModelViewMat", new Matrix4f()); transforms.setMatrix("TextureMat", new Matrix4f());
            transforms.setVector("ColorModulator", 1, 1, 1, 1); transforms.setVector("ModelOffset", 0, 0, 0); fog.setVector("FogColor", 0, 0, 0, 0);
            for (String name : List.of("FogEnvironmentalStart", "FogRenderDistanceStart")) fog.setFloat(name, 100);
            for (String name : List.of("FogEnvironmentalEnd", "FogRenderDistanceEnd", "FogSkyEnd", "FogCloudsEnd")) fog.setFloat(name, 200);
            var base = cache.get(RenderPipelines.SOLID_BLOCK);
            directSource.close(); // the production direct compile must retain CPU text before its source owner retires
            var encoder = device.createCommandEncoder(); projection.upload(encoder); transforms.upload(encoder); fog.upload(encoder);
            atlas.clear(encoder, new Vector4f(1), 0); lightmap.clear(encoder, new Vector4f(.5f, .5f, .5f, 1), 0);
            var vertices = new BufferBuilder(memory, PrimitiveTopology.QUADS, DefaultVertexFormat.BLOCK);
            for (float[] xy : new float[][]{{-1,-1},{1,-1},{1,1},{-1,1}})
                vertices.addVertex(xy[0], xy[1], .5f).setColor(0xff804020).setUv(.5f, .5f).setUv2(0,240);
            mesh.upload(encoder, vertices.buildOrThrow(), VertexArray.DrawUsage.STATIC);
            var readbacks = new ArrayList<GpuBuffer>();
            com.mojang.renderpearl.api.textures.GpuTextureView oldAlbedo;
            try (var draw = mesh.retain()) {
                try (var scope = NativeDynamicBuffers.beginWorld(output.color(0).view(), output.depth().view())) {
                    oldAlbedo = manager.getBufferTexture(DynamicBufferType.ALBEDO);
                    var descriptor = RenderPassDescriptor.builder(() -> "unchanged native world descriptor")
                            .withColorAttachment(output.color(0).view(), Optional.<Vector4fc>of(new Vector4f(0)))
                            .withDepthAttachment(output.depth().view(), OptionalDouble.of(0)).build();
                    // No fixture-side attachment extension or variant selection: real production mixins own both.
                    try (var pass = encoder.createRenderPass(descriptor)) {
                        pass.setPipeline(base);
                        pass.setUniform("Projection", projection.buffer().slice()); pass.setUniform("DynamicTransforms", transforms.buffer().slice()); pass.setUniform("Fog", fog.buffer().slice());
                        pass.setUniform("Globals", RenderSystem.getGlobalSettingsUniform().slice()); pass.setUniform("Lighting", RenderSystem.getShaderLights());
                        pass.setUniform("Sampler0", atlas.color(0).view(), sampler); pass.setUniform("Sampler2", lightmap.color(0).view(), sampler); draw.draw(pass);
                    }
                    // Managed fullscreen marker produces only debug; other channels must retain the opaque world values.
                    try (var pass = encoder.createRenderPass(() -> "managed native world overlay", output.color(0).view(), Optional.empty())) {
                        direct.bind(pass); pass.draw(3,1,0,0);
                    }
                    int variants = compiler.variantCount();
                    try (var pass = encoder.createRenderPass(() -> "independent offscreen owner", other.color(0).view(), Optional.empty())) {
                        direct.bind(pass); pass.draw(3,1,0,0);
                    }
                    check(compiler.variantCount() == variants && variants == 2, "Only physical world attachments may request real dynamic variants");
                    try (var nested = NativeDynamicBuffers.beginWorld(other.color(0).view(), null)) {
                        check(manager.getBufferTexture(DynamicBufferType.ALBEDO) != oldAlbedo,
                                "Nested same-size worlds must own independent dynamic attachments");
                        try (var pass = encoder.createRenderPass(() -> "nested independent world", other.color(0).view(), Optional.empty())) {
                            direct.bind(pass); pass.draw(3,1,0,0);
                        }
                    }
                    check(manager.getBufferTexture(DynamicBufferType.ALBEDO) == oldAlbedo, "Closing a nested world must restore its parent inputs");
                    try (var suspended = NativeDynamicBuffers.suspend()) {
                        check(manager.getBufferTexture(DynamicBufferType.ALBEDO) == oldAlbedo && manager.getAvailableBuffers() == all,
                                "Post capture suspension must retain the selected world input views");
                        int before = compiler.variantCount();
                        try (var pass = encoder.createRenderPass(() -> "explicit post target", output.color(0).view(), Optional.empty())) {
                            direct.bind(pass); pass.draw(3,1,0,0);
                        }
                        check(compiler.variantCount() == before, "Explicit post output descriptors must not acquire world capture variants");
                    }
                    for (DynamicBufferType type : DynamicBufferType.values()) {
                        int bytes = type == DynamicBufferType.DEBUG ? 32 : type == DynamicBufferType.LIGHT_UV ? 8 : 16;
                        var read = device.createBuffer(() -> "automatic native output readback", GpuBuffer.USAGE_COPY_DST | GpuBuffer.USAGE_MAP_READ, bytes);
                        readbacks.add(read); encoder.copyTextureToBuffer(manager.getBufferTexture(type).texture(), read, 0, () -> {}, 0);
                    }
                    manager.setActiveBuffers(ownerA, 0); manager.setActiveBuffers(ownerB, 0); manager.resizeFramebuffers(4, 4); manager.close(); compiler.close();
                    check(!oldAlbedo.isClosed(), "Request changes, resize and owner close must retain the actual queued world attachments");
                }
                check(!oldAlbedo.isClosed() && !compiler.retirement().isDone(), "Pass/world close must retain native resources until encoder submission");
                try (var fence = encoder.createFence()) {
                    encoder.submit(); check(fence.awaitCompletion(10_000_000_000L), "Automatic native world readback timed out");
                }
                check(oldAlbedo.isClosed() && compiler.retirement().isDone(), "Submission must release the exact retired native attachment/pipeline owners");
                assertBytes(readbacks.get(0), false, 128,64,32,255);
                assertBytes(readbacks.get(1), true, 0,0,127,127);
                assertBytes(readbacks.get(2), false, 8,247);
                assertBytes(readbacks.get(3), false, 128,128,128,255);
                try (var mapped = readbacks.get(4).map(true,false)) {
                    var bytes = mapped.data().order(ByteOrder.nativeOrder()); float[] expected={.25f,.5f,.75f,1};
                    for (int i=0;i<16;i++) check(Math.abs(Float.float16ToFloat(bytes.getShort(i*2))-expected[i%4])<.005f, "Direct managed debug marker component "+i+" was "+Float.float16ToFloat(bytes.getShort(i*2))+", expected "+expected[i%4]);
                }
                check(!output.color(0).view().isClosed() && !output.depth().view().isClosed(), "Automatic native world disposal closed caller attachments");
            } finally { readbacks.forEach(GpuBuffer::close); }
        } finally { NativeDynamicBuffers.unbindRuntime(manager); }
        checks.add("production native encoder/pass mixins automatically capture genuine vanilla and source-retired managed pipelines; unsupported outputs preserve earlier pixels; independent source masks and physical target identity are honored; close/resize retain attachments and compiled owners until actual submission");
    }
    private static void assertBytes(GpuBuffer buffer, boolean signed, int... expected) {
        try (var mapped=buffer.map(true,false)) {
            for(int i=0;i<mapped.data().remaining();i++) {
                int value=signed?mapped.data().get(i):mapped.data().get(i)&255;
                check(Math.abs(value-expected[i%expected.length])<=1,"Automatic dynamic byte "+i+" was "+value+", expected "+expected[i%expected.length]);
            }
        }
    }
    private static void check(boolean condition,String message) { if(!condition)throw new AssertionError(message); }
}
