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
import foundry.veil.api.client.render.shader.NativePipelineBridge;
import foundry.veil.api.client.render.texture.TextureFilter;
import foundry.veil.api.client.render.vertex.VertexArray;
import foundry.veil.impl.client.render.dynamicbuffer.*;
import io.github.ocelot.glslprocessor.api.GlslInjectionPoint;
import io.github.ocelot.glslprocessor.api.GlslParser;
import io.github.ocelot.glslprocessor.api.node.variable.GlslNewFieldNode;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.resources.Identifier;
import org.joml.Matrix4f;
import org.joml.Vector4f;
import org.joml.Vector4fc;

import java.nio.ByteOrder;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;

/** New detached native-variant behavior, independent from previously passing bridge/MRT scopes. */
public final class GpuDynamicVariantAcceptance {
    private static final AtomicReference<NativePipelineBridge.Bindings> VALUES = new AtomicReference<>();
    public static void bootstrap() {
        NativePipelineBridge.register(Identifier.parse("veil:variant_binding_probe"), pipeline -> pipeline == RenderPipelines.SOLID_BLOCK,
                Map.of("VariantSettings", UniformType.UNIFORM_BUFFER), () -> (ctx, tree) -> {
                    if (!ctx.isFragment() || !ctx.isSourceFile()) return;
                    tree.getBody().addAll(GlslInjectionPoint.BEFORE_MAIN, GlslParser.parse("layout(std140) uniform VariantSettings { float VariantGain; };").getBody());
                    tree.mainFunction().orElseThrow().getBody().add(GlslParser.parseExpression("fragColor *= VariantGain"));
                }, pipeline -> Objects.requireNonNull(VALUES.get()));
    }
    public static void run(Minecraft client, GpuDevice device, List<String> checks) {
        run(client, device, checks, false);
    }
    public static void runCache(Minecraft client, GpuDevice device, List<String> checks) {
        run(client, device, checks, true);
    }
    private static void run(Minecraft client, GpuDevice device, List<String> checks, boolean cached) {
        int mask = DynamicBufferType.encode(DynamicBufferType.values());
        var workers = new ArrayDeque<Runnable>();
        var renderTasks = new ArrayDeque<Runnable>();
        var owner = new NativeDynamicBufferTarget(device, "variant source retirement MRT", 2, 2, mask);
        try (var selected = owner.retain();
             var source = new GpuShaderSource(client.getResourceManager(), null, "shaders/include", Map.of(), (id, stage, raw) ->
                     stage == ShaderType.FRAGMENT ? raw.replace("vec4 color =", "// #veil:debug\n    vec4 color =") : raw);
             var cache = new PipelineCache(device, source);
             var variants = new VanillaShaderCompiler(device, workers::add, renderTasks::add);
             var abandoned = new VanillaShaderCompiler(device, workers::add, renderTasks::add);
             var gainA = new GpuUniformBuffer(device, "first native variant gain", GpuUniformBuffer.layoutBuilder().field("VariantGain", GpuUniformBuffer.ValueType.FLOAT).build());
             var gainB = new GpuUniformBuffer(device, "second native variant gain", gainA.layout());
             var projection = new GpuUniformBuffer(device, "variant projection", GpuUniformBuffer.layoutBuilder().field("ProjMat", GpuUniformBuffer.ValueType.MAT4).build());
             var transforms = new GpuUniformBuffer(device, "variant transforms", GpuUniformBuffer.layoutBuilder().field("ModelViewMat", GpuUniformBuffer.ValueType.MAT4)
                     .field("TextureMat", GpuUniformBuffer.ValueType.MAT4).field("ColorModulator", GpuUniformBuffer.ValueType.VEC4).field("ModelOffset", GpuUniformBuffer.ValueType.VEC3).build());
             var fog = new GpuUniformBuffer(device, "variant fog", GpuUniformBuffer.layoutBuilder().field("FogColor", GpuUniformBuffer.ValueType.VEC4)
                     .field("FogEnvironmentalStart", GpuUniformBuffer.ValueType.FLOAT).field("FogEnvironmentalEnd", GpuUniformBuffer.ValueType.FLOAT)
                     .field("FogRenderDistanceStart", GpuUniformBuffer.ValueType.FLOAT).field("FogRenderDistanceEnd", GpuUniformBuffer.ValueType.FLOAT)
                     .field("FogSkyEnd", GpuUniformBuffer.ValueType.FLOAT).field("FogCloudsEnd", GpuUniformBuffer.ValueType.FLOAT).build());
             var atlas = GpuFramebuffer.builder("variant white atlas", 16, 16).color(GpuFormat.RGBA8_UNORM).build(device);
             var lightmap = GpuFramebuffer.builder("variant half lightmap", 16, 16).color(GpuFormat.RGBA8_UNORM).build(device);
             var outputA = GpuFramebuffer.builder("first true native variant", 2, 2).color(GpuFormat.RGBA8_UNORM).depth(GpuFormat.D32_FLOAT).build(device);
             var outputB = GpuFramebuffer.builder("second true native variant", 2, 2).color(GpuFormat.RGBA8_UNORM).depth(GpuFormat.D32_FLOAT).build(device);
             var sampler = TextureFilter.CLAMP.createSampler(device);
             var mesh = VertexArray.create(device, "variant real block mesh"); var memory = new ByteBufferBuilder(2048)) {
            var base = cache.get(RenderPipelines.SOLID_BLOCK);
            var original = NativePipelineBridge.originalDefinition(base).orElseThrow();
            check(original == RenderPipelines.SOLID_BLOCK, "The real original definition must be retained separately from typed augmentation");
            var definition = selected.pipeline(NativePipelineBridge.actualDefinition(base).orElseThrow());
            var pending = cached ? null : NativePipelineBridge.compileVariant(device, base, definition, mask,
                    List.of((resources, registry) -> registry.addPreprocessor(new DynamicBufferProcessor(), false)), workers::add);
            java.util.concurrent.CompletableFuture<Void> retirement = null, abandonedRetirement = null;
            if (cached) {
                variants.prepare(base, definition, mask);
                variants.prepare(base, selected.pipeline(NativePipelineBridge.actualDefinition(base).orElseThrow()), mask);
                check(variants.variantCount() == 1 && workers.size() == 1 && variants.isCompilingShaders(), "Equivalent immutable native states must share one real pending variant");
                var plain = cache.get(RenderPipelines.CUTOUT_BLOCK);
                check(NativePipelineBridge.actualDefinition(plain).isEmpty(), "The unextended native source path must remain distinct from typed augmentation");
                abandoned.prepare(plain, selected.pipeline(NativePipelineBridge.originalDefinition(plain).orElseThrow()), mask);
                abandoned.close(); abandonedRetirement = abandoned.retirement();
                check(!abandonedRetirement.isDone(), "Clearing a pending cache must retain its native Pending/source until workers and render-thread disposal finish");
            }
            check((cached ? variants.isCompilingShaders() : !pending.isDone()) && !workers.isEmpty(), "Native variant compilation must use its supplied worker executor");
            cache.clear(); source.close(); owner.close();
            check(base.isClosed() && !selected.texture(DynamicBufferType.ALBEDO).isClosed(), "Base native cache/source retirement must preserve separately retained MRT storage");
            while (!workers.isEmpty()) workers.remove().run();
            VanillaShaderCompiler.Lease lease = cached ? variants.retain(base, definition, mask) : null;
            VanillaShaderCompiler.Lease secondLease = cached ? variants.retain(base, selected.pipeline(NativePipelineBridge.actualDefinition(base).orElseThrow()), mask) : null;
            var variant = cached ? lease.pipeline() : pending.join().finishCompile();
            try {
                if (cached) {
                    check(secondLease.pipeline() == variant && lease.activeBuffers() == mask, "Independent leases must retain the exact real cached native variant and selected mask");
                    variants.removeRetiredBases(); variants.close(); retirement = variants.retirement();
                    check(variants.variantCount() == 0 && !retirement.isDone() && !variant.isClosed(), "Retired native bases/compiler owners must preserve queued variant leases");
                }
                check(variant != null && !variant.isClosed() && NativePipelineBridge.actualDefinition(variant).orElseThrow() == definition,
                        "Detached asynchronous native compilation must finish after the original native source/cache closes");
                projection.setMatrix("ProjMat", new Matrix4f()); transforms.setMatrix("ModelViewMat", new Matrix4f()); transforms.setMatrix("TextureMat", new Matrix4f());
                transforms.setVector("ColorModulator", 1, 1, 1, 1); transforms.setVector("ModelOffset", 0, 0, 0); fog.setVector("FogColor", 0, 0, 0, 0);
                for (String name : List.of("FogEnvironmentalStart", "FogRenderDistanceStart")) fog.setFloat(name, 100);
                for (String name : List.of("FogEnvironmentalEnd", "FogRenderDistanceEnd", "FogSkyEnd", "FogCloudsEnd")) fog.setFloat(name, 200);
                gainA.setFloat("VariantGain", .5f); gainB.setFloat("VariantGain", 1);
                var encoder = device.createCommandEncoder(); projection.upload(encoder); transforms.upload(encoder); fog.upload(encoder); gainA.upload(encoder); gainB.upload(encoder);
                atlas.clear(encoder, new Vector4f(1), 0); lightmap.clear(encoder, new Vector4f(.5f, .5f, .5f, 1), 0); selected.clear(encoder);
                var vertices = new BufferBuilder(memory, PrimitiveTopology.QUADS, DefaultVertexFormat.BLOCK);
                for (float[] xy : new float[][]{{-1, -1}, {1, -1}, {1, 1}, {-1, 1}})
                    vertices.addVertex(xy[0], xy[1], .5f).setColor(0xff804020).setUv(.5f, .5f).setUv2(0, 240);
                mesh.upload(encoder, vertices.buildOrThrow(), VertexArray.DrawUsage.STATIC);
                try (var draw = mesh.retain()) {
                    for (var entry : List.of(Map.entry(outputA, gainA), Map.entry(outputB, gainB))) {
                        VALUES.set(new NativePipelineBridge.Bindings(Map.of("VariantSettings", entry.getValue().buffer().slice()), Map.of()));
                        var descriptor = RenderPassDescriptor.builder(() -> "native variant after source retirement")
                                .withColorAttachment(entry.getKey().color(0).view(), Optional.<Vector4fc>of(new Vector4f(0)))
                                .withDepthAttachment(entry.getKey().depth().view(), OptionalDouble.of(0)).build();
                        try (var pass = encoder.createRenderPass(selected.extend(descriptor))) {
                            pass.setPipeline(variant);
                            pass.setUniform("Projection", projection.buffer().slice()); pass.setUniform("DynamicTransforms", transforms.buffer().slice()); pass.setUniform("Fog", fog.buffer().slice());
                            pass.setUniform("Globals", RenderSystem.getGlobalSettingsUniform().slice()); pass.setUniform("Lighting", RenderSystem.getShaderLights());
                            pass.setUniform("Sampler0", atlas.color(0).view(), sampler); pass.setUniform("Sampler2", lightmap.color(0).view(), sampler); draw.draw(pass);
                        }
                    }
                    encoder.submit();
                }
                GpuDynamicBufferAcceptance.assertBytes(device, outputA.color(0).texture(), 4, false, 32, 16, 8, 128);
                GpuDynamicBufferAcceptance.assertBytes(device, outputB.color(0).texture(), 4, false, 64, 32, 16, 255);
                GpuDynamicBufferAcceptance.assertBytes(device, selected.texture(DynamicBufferType.ALBEDO).texture(), 4, false, 128, 64, 32, 255);
                assertHalf(device, selected.texture(DynamicBufferType.DEBUG).texture(), 64 / 255.0f, 32 / 255.0f, 16 / 255.0f, 1);
            } finally {
                if (cached) {
                    secondLease.close(); check(!variant.isClosed(), "An independent remaining native lease must retain the pipeline");
                    lease.close();
                    while (!renderTasks.isEmpty()) renderTasks.remove().run();
                    check(variant.isClosed() && retirement.isDone() && !retirement.isCompletedExceptionally()
                            && abandonedRetirement.isDone() && !abandonedRetirement.isCompletedExceptionally(), "Final submission leases and abandoned Pending owners must release native resources on the render executor");
                } else variant.close();
            }
            check(!gainA.buffer().isClosed() && !outputA.color(0).view().isClosed(), "Independent native variant disposal must preserve borrowed resource owners");
        } finally { owner.close(); VALUES.set(null); }
        checks.add(cached ? "native vanilla variant cache deduplicates immutable state, survives base/source retirement, independently retains submitted leases, disposes abandoned Pending on render executor and drives exact MRT/extension/debug pixels"
                : "derived genuine native MRT pipeline compiles from detached actual shader/includes after original cache/source retirement; original extension hooks bind distinct borrowed per-pass gain values; marker debug data reaches RGBA16F; native variant owns no caller resources");
    }
    private static void assertHalf(GpuDevice device, com.mojang.renderpearl.api.textures.GpuTexture texture, float... expected) {
        try (var readback = device.createBuffer(() -> "native debug half-float readback", GpuBuffer.USAGE_COPY_DST | GpuBuffer.USAGE_MAP_READ, 32)) {
            var encoder = device.createCommandEncoder(); encoder.copyTextureToBuffer(texture, readback, 0, () -> {}, 0);
            try (var fence = encoder.createFence()) {
                encoder.submit(); check(fence.awaitCompletion(10_000_000_000L), "Native variant debug readback timeout");
                try (var mapped = readback.map(true, false)) {
                    var bytes = mapped.data().order(ByteOrder.nativeOrder());
                    for (int i = 0; i < 16; i++) check(Math.abs(Float.float16ToFloat(bytes.getShort(i * 2)) - expected[i % 4]) < .005f, "Marker debug value differs from its actual source field");
                }
            }
        }
    }
    private static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
