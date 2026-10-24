import com.mojang.blaze3d.vertex.*;
import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.commands.RenderPassDescriptor;
import com.mojang.renderpearl.api.device.GpuDevice;
import com.mojang.renderpearl.api.pipeline.*;
import foundry.veil.api.client.render.dynamicbuffer.DynamicBufferType;
import foundry.veil.api.client.render.gpu.*;
import foundry.veil.api.client.render.shader.ShaderPreDefinitions;
import foundry.veil.api.client.render.texture.TextureFilter;
import foundry.veil.api.client.render.vertex.VertexArray;
import foundry.veil.impl.client.render.dynamicbuffer.*;
import foundry.veil.impl.client.render.shader.processor.NativeShaderProcessor;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.RenderPipelines;
import org.joml.Matrix4f;
import org.joml.Vector4f;
import org.joml.Vector4fc;

import java.nio.ByteOrder;
import java.util.*;

/** Native dynamic outputs on the genuine vanilla block shader and typed native MRT pass. */
public final class GpuDynamicBufferAcceptance {
    public static void run(Minecraft client, GpuDevice device, List<String> checks) {
        int all = DynamicBufferType.encode(DynamicBufferType.values());
        int small = DynamicBufferType.encode(DynamicBufferType.ALBEDO, DynamicBufferType.NORMAL);
        var original = RenderPipelines.SOLID_BLOCK;
        var owner = new NativeDynamicBufferTarget(device, "retained dynamic MRT", 2, 2, all);
        try (var first = owner.retain();
             var a = GpuFramebuffer.builder("original caller MRT", 2, 2).color(GpuFormat.RGBA8_UNORM).depth(GpuFormat.D32_FLOAT).build(device);
             var b = GpuFramebuffer.builder("resized caller MRT", 4, 2).color(GpuFormat.RGBA8_UNORM).depth(GpuFormat.D32_FLOAT).build(device);
             var atlas = GpuFramebuffer.builder("dynamic sample atlas", 16, 16).color(GpuFormat.RGBA8_UNORM).build(device);
             var lightmap = GpuFramebuffer.builder("dynamic lightmap", 16, 16).color(GpuFormat.RGBA8_UNORM).build(device);
             var sampler = TextureFilter.CLAMP.createSampler(device);
             var mesh = VertexArray.create(device, "real native block dynamic quad");
             var memory = new ByteBufferBuilder(2048);
             var projection = new GpuUniformBuffer(device, "dynamic projection", GpuUniformBuffer.layoutBuilder().field("ProjMat", GpuUniformBuffer.ValueType.MAT4).build());
             var transforms = new GpuUniformBuffer(device, "dynamic transforms", GpuUniformBuffer.layoutBuilder().field("ModelViewMat", GpuUniformBuffer.ValueType.MAT4)
                     .field("TextureMat", GpuUniformBuffer.ValueType.MAT4).field("ColorModulator", GpuUniformBuffer.ValueType.VEC4).field("ModelOffset", GpuUniformBuffer.ValueType.VEC3).build());
             var fog = new GpuUniformBuffer(device, "dynamic fog", GpuUniformBuffer.layoutBuilder().field("FogColor", GpuUniformBuffer.ValueType.VEC4)
                     .field("FogEnvironmentalStart", GpuUniformBuffer.ValueType.FLOAT).field("FogEnvironmentalEnd", GpuUniformBuffer.ValueType.FLOAT)
                     .field("FogRenderDistanceStart", GpuUniformBuffer.ValueType.FLOAT).field("FogRenderDistanceEnd", GpuUniformBuffer.ValueType.FLOAT)
                     .field("FogSkyEnd", GpuUniformBuffer.ValueType.FLOAT).field("FogCloudsEnd", GpuUniformBuffer.ValueType.FLOAT).build());
             var sourceA = source(client, device, original, all);
             var programA = new GpuShaderProgram(device, first.pipeline(original), sourceA, Runnable::run)) {
            var oldNormal = first.texture(DynamicBufferType.NORMAL);
            owner.configure(4, 2, small);
            try (var second = owner.retain(); var sourceB = source(client, device, original, small);
                 var programB = new GpuShaderProgram(device, second.pipeline(original), sourceB, Runnable::run)) {
                check(!oldNormal.isClosed() && oldNormal != second.texture(DynamicBufferType.NORMAL), "Resizing must retain independently selected dynamic resources");
                try { owner.configure(0, 2, small); throw new AssertionError("Invalid native dynamic size accepted"); }
                catch (IllegalArgumentException expected) { check(owner.mask() == small, "Rejected reconfiguration lost the working generation"); }
                owner.close();
                check(!oldNormal.isClosed() && !second.texture(DynamicBufferType.NORMAL).isClosed(), "Owner disposal must retain both queued native snapshots");
                projection.setMatrix("ProjMat", new Matrix4f()); transforms.setMatrix("ModelViewMat", new Matrix4f()); transforms.setMatrix("TextureMat", new Matrix4f());
                transforms.setVector("ColorModulator", .5f, 1, 1, 1); transforms.setVector("ModelOffset", 0, 0, 0);
                fog.setVector("FogColor", 0, 0, 0, 0);
                for (String name : List.of("FogEnvironmentalStart", "FogRenderDistanceStart")) fog.setFloat(name, 100);
                for (String name : List.of("FogEnvironmentalEnd", "FogRenderDistanceEnd", "FogSkyEnd", "FogCloudsEnd")) fog.setFloat(name, 200);
                var encoder = device.createCommandEncoder(); projection.upload(encoder); transforms.upload(encoder); fog.upload(encoder);
                atlas.clear(encoder, new Vector4f(.5f, 1, 1, 1), 0); lightmap.clear(encoder, new Vector4f(.25f, .5f, .75f, 1), 0);
                var vertices = new BufferBuilder(memory, PrimitiveTopology.QUADS, DefaultVertexFormat.BLOCK);
                vertices.addVertex(-1, -1, .5f).setColor(0xff804020).setUv(.5f, .5f).setUv2(0, 240);
                vertices.addVertex(1, -1, .5f).setColor(0xff804020).setUv(.5f, .5f).setUv2(0, 240);
                vertices.addVertex(1, 1, .5f).setColor(0xff804020).setUv(.5f, .5f).setUv2(0, 240);
                vertices.addVertex(-1, 1, .5f).setColor(0xff804020).setUv(.5f, .5f).setUv2(0, 240);
                mesh.upload(encoder, vertices.buildOrThrow(), VertexArray.DrawUsage.STATIC);
                first.clear(encoder); second.clear(encoder);
                try (var draw = mesh.retain()) {
                    for (var entry : List.of(Map.entry(first, Map.entry(a, programA)), Map.entry(second, Map.entry(b, programB)))) {
                        var target = entry.getValue().getKey(); var program = entry.getValue().getValue();
                        var descriptor = RenderPassDescriptor.builder(() -> "borrowed native world pass")
                                .withColorAttachment(target.color(0).view(), Optional.<Vector4fc>of(new Vector4f(0)))
                                .withDepthAttachment(target.depth().view(), OptionalDouble.of(0)).build();
                        var extended = entry.getKey().extend(descriptor);
                        check(extended.colorAttachments().getFirst() == descriptor.colorAttachments().getFirst()
                                && extended.depthAttachment() == descriptor.depthAttachment() && extended.renderArea() == descriptor.renderArea()
                                && extended.label() == descriptor.label(), "Native MRT extension must preserve exact borrowed caller descriptor data");
                        program.setUniformBlock("Projection", projection.buffer().slice()); program.setUniformBlock("DynamicTransforms", transforms.buffer().slice()); program.setUniformBlock("Fog", fog.buffer().slice());
                        if (program.hasUniform("Globals")) program.setUniformBlock("Globals", com.mojang.blaze3d.systems.RenderSystem.getGlobalSettingsUniform().slice());
                        if (program.hasUniform("Lighting")) program.setUniformBlock("Lighting", com.mojang.blaze3d.systems.RenderSystem.getShaderLights());
                        program.setTexture("Sampler0", atlas.color(0).view(), sampler); program.setTexture("Sampler2", lightmap.color(0).view(), sampler);
                        try (var pass = encoder.createRenderPass(extended)) { program.bind(pass); draw.draw(pass); }
                    }
                    encoder.submit();
                }
                assertBytes(device, a.color(0).texture(), 4, false, 8, 32, 24, 255);
                assertBytes(device, first.texture(DynamicBufferType.ALBEDO).texture(), 4, false, 32, 64, 32, 255);
                assertBytes(device, first.texture(DynamicBufferType.LIGHT_UV).texture(), 2, false, 8, 247);
                assertBytes(device, first.texture(DynamicBufferType.LIGHT_COLOR).texture(), 4, false, 64, 128, 191, 255);
                assertBytes(device, first.texture(DynamicBufferType.NORMAL).texture(), 4, true, 0, 0, 127, 127);
                assertBytes(device, b.color(0).texture(), 4, false, 8, 32, 24, 255);
                assertBytes(device, second.texture(DynamicBufferType.ALBEDO).texture(), 4, false, 32, 64, 32, 255);
                check(!a.color(0).view().isClosed() && !a.depth().view().isClosed(), "Dynamic snapshots must never close caller-owned attachments");
            }
            check(!oldNormal.isClosed(), "An older retained generation must survive releasing a newer one");
        } finally { owner.close(); }
        checks.add("genuine vanilla block AST produces native albedo/lightUV/lightColor/geometric-normal MRT pixels; immutable mask/resize snapshots retain replaced native storage through owner retirement and preserve exact borrowed pass descriptors");
    }
    private static GpuShaderSource source(Minecraft client, GpuDevice device, RenderPipeline original, int mask) {
        var processor = NativeShaderProcessor.forMinecraft(device, client.getResourceManager(), new ShaderPreDefinitions(), original, mask,
                List.of((resources, registry) -> registry.addPreprocessor(new DynamicBufferProcessor(), false)));
        return new GpuShaderSource(client.getResourceManager(), null, "shaders/include", Map.of(), processor);
    }
    static void assertBytes(GpuDevice device, com.mojang.renderpearl.api.textures.GpuTexture texture, int components, boolean signed, int... expected) {
        int size = texture.getWidth(0) * texture.getHeight(0) * components;
        try (var readback = device.createBuffer(() -> "dynamic MRT readback", GpuBuffer.USAGE_COPY_DST | GpuBuffer.USAGE_MAP_READ, size)) {
            var encoder = device.createCommandEncoder(); encoder.copyTextureToBuffer(texture, readback, 0, () -> {}, 0);
            try (var fence = encoder.createFence()) {
                encoder.submit(); check(fence.awaitCompletion(10_000_000_000L), "Dynamic MRT GPU readback timeout");
                try (var mapped = readback.map(true, false)) {
                    for (int i = 0; i < size; i++) {
                        int actual = signed ? mapped.data().get(i) : mapped.data().get(i) & 255;
                        check(Math.abs(actual - expected[i % components]) <= 1, "Dynamic " + texture.getFormat() + " component " + i + " was " + actual + ", expected " + expected[i % components]);
                    }
                }
            }
        }
    }
    private static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
