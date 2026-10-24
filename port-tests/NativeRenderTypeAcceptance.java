package foundry.veil.impl.client.render.rendertype;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonParser;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.device.GpuDevice;
import com.mojang.renderpearl.api.pipeline.CompareOp;
import com.mojang.renderpearl.api.pipeline.IndexType;
import com.mojang.renderpearl.api.pipeline.PrimitiveTopology;
import com.mojang.serialization.JsonOps;
import foundry.veil.api.client.render.framebuffer.FramebufferManager;
import foundry.veil.api.client.render.gpu.GpuFramebuffer;
import foundry.veil.api.client.render.rendertype.VeilRenderType;
import foundry.veil.api.client.render.rendertype.VeilRenderTypeBuilder;
import foundry.veil.api.client.render.rendertype.layer.*;
import foundry.veil.api.client.render.shader.ShaderManager;
import foundry.veil.api.client.render.shader.ShaderPreDefinitions;
import foundry.veil.api.client.util.VertexFormatCodec;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.StagedVertexBuffer;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.PreparableReloadListener;
import org.joml.Vector4f;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/** New render-type contracts only. Runs real native draw hooks on Vulkan and reads the actual target. */
public final class NativeRenderTypeAcceptance implements ClientModInitializer {
    private int ticks;
    private boolean ran;
    @Override public void onInitializeClient() {
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (this.ran || ++this.ticks < 5 || !client.isGameLoadFinished()) return;
            this.ran = true;
            final var report = new LinkedHashMap<String, Object>();
            try {
                final var device = RenderSystem.getDevice();
                report.put("backend", device.getDeviceInfo().backendName());
                check(device.getDeviceInfo().backendName().toLowerCase(Locale.ROOT).contains("vulkan"), "Native Vulkan backend required");
                final var checks = new ArrayList<String>(); report.put("checks", checks);
                final var selected = Set.of(System.getProperty("veil.rendertype.selection", "codecs,cache,layers").split(","));
                if (selected.contains("codecs")) codecs(checks);
                if (selected.contains("generic")) generic(checks);
                if (selected.contains("cache") || selected.contains("layers") || selected.contains("blocks")) draw(client, device, checks, selected);
                report.put("status", "passed");
            } catch (Throwable failure) { failure.printStackTrace(); report.put("status", "failed"); report.put("error", failure.toString()); }
            finally {
                try { Files.writeString(Path.of(System.getProperty("veil.rendertype.report")), new GsonBuilder().setPrettyPrinting().create().toJson(report)); }
                catch (Exception exception) { throw new RuntimeException(exception); }
                client.stop();
            }
        });
    }
    private static void codecs(List<String> checks) {
        final var custom = JsonParser.parseString("{\"stride\":20,\"stepRate\":1,\"elements\":[{\"name\":\"Position\",\"offset\":0,\"format\":\"rgb32_float\"},{\"name\":\"BoneIndex\",\"offset\":16,\"format\":\"r16_uint\"}]}");
        final var format = VertexFormatCodec.CODEC.parse(JsonOps.INSTANCE, custom).getOrThrow();
        check(format.getVertexSize() == 20 && format.getStepRate() == 1 && format.getElement("BoneIndex").offset() == 16, "Native custom stride, instance rate and padding must survive");
        final var roundTrip = VertexFormatCodec.CODEC.parse(JsonOps.INSTANCE, VertexFormatCodec.CODEC.encodeStart(JsonOps.INSTANCE, format).getOrThrow()).getOrThrow();
        check(roundTrip.getVertexSize() == 20 && roundTrip.getStepRate() == 1, "Custom format roundtrip must retain step rate");
        check(VertexFormatCodec.MODE_CODEC.parse(JsonOps.INSTANCE, JsonParser.parseString("\"QUADS\"")).getOrThrow() == PrimitiveTopology.QUADS, "Legacy topology spelling");
        final var data = CompositeRenderTypeData.CODEC.parse(JsonOps.INSTANCE, JsonParser.parseString("{\"format\":\"POSITION\",\"mode\":\"TRIANGLES\",\"bufferSize\":\"SMALL\",\"affectsCrumbling\":true,\"sort\":false,\"layers\":[{\"type\":\"veil:shader\",\"name\":\"veil:gpu_rendertype_red\"}]}")).getOrThrow();
        final var encoded = CompositeRenderTypeData.CODEC.encodeStart(JsonOps.INSTANCE, data).getOrThrow().getAsJsonObject();
        check(encoded.get("affectsCrumbling").getAsBoolean() && !data.sort(), "Crumbling must encode independently of sorting");
        final var texture = TextureLayer.CODEC.codec().parse(JsonOps.INSTANCE, JsonParser.parseString("{\"texture\":\"%1$s\",\"blur\":true,\"mipmap\":false}")).getOrThrow();
        final var builder = new VeilRenderTypeBuilder().shaderState(Identifier.parse("veil:gpu_rendertype_red"), true).standardUniforms(false);
        texture.addShard(builder, "example:surface");
        new DepthTestLayer(LayerTemplateValue.raw(DepthTestLayer.DepthState.LEQUAL)).addShard(builder);
        final var state = builder.create();
        check(state.textures().getFirst().blur() && !state.textures().getFirst().mipmap(), "Blur and mipmap flags remain independent");
        final var pipeline = VeilRenderTypeBuilder.pipeline(Identifier.parse("veil:check_state"), state, DefaultVertexFormat.POSITION, PrimitiveTopology.TRIANGLES, List.of(GpuFormat.RGBA8_UNORM), GpuFormat.D32_FLOAT);
        check(pipeline.getDepthStencilState().depthTest() == CompareOp.GREATER_THAN_OR_EQUAL, "Legacy lequal retains near/far semantics under native reversed Z");
        final var an = new DynamicRenderTypeManager.Params(new Object[]{"an"});
        final var c0 = new DynamicRenderTypeManager.Params(new Object[]{"c0"});
        check(an.hashCode() == c0.hashCode() && !an.equals(c0), "Colliding parameter hashes must retain distinct values");
        final var nested = new Object[]{new int[]{1, 2}};
        final var frozen = new DynamicRenderTypeManager.Params(nested);
        ((int[]) nested[0])[0] = 4;
        check(frozen.equals(new DynamicRenderTypeManager.Params(new Object[]{new int[]{1, 2}})), "Nested cache arrays must remain immutable");
        checks.add("native_resource_codecs_and_cache_keys");
    }
    private static void generic(List<String> checks) {
        final var white = Identifier.parse("veil:textures/special/white.png");
        final var setup = net.minecraft.client.renderer.rendertype.RenderSetup.builder(net.minecraft.client.renderer.RenderPipelines.DEBUG_QUADS)
                .withTexture("MaskTex", white).useLightmap().useOverlay().affectsCrumbling().sortOnUpload().withForcedSolidModelPhase()
                .setOutline(net.minecraft.client.renderer.rendertype.RenderSetup.OutlineProperty.AFFECTS_OUTLINE, "MaskTex").createRenderSetup();
        final var nativeType = foundry.veil.mixin.rendertype.accessor.RenderTypeAccessor.veil$create("veil:modifier_native", setup);
        foundry.veil.api.client.registry.RenderTypeShardRegistry.addShard("veil:modifier_native", builder -> builder.depthTestState(CompareOp.NEVER_PASS));
        check(nativeType.pipeline().getDepthStencilState().depthTest() == CompareOp.NEVER_PASS, "Late named modifiers update existing native pipelines");
        final var afterNamed = (foundry.veil.mixin.rendertype.accessor.RenderSetupAccessor) (Object) VeilRenderType.getShards(nativeType).renderSetup();
        final var texture = (foundry.veil.mixin.rendertype.accessor.TextureBindingAccessor) afterNamed.veil$getTextures().get("MaskTex");
        check(texture.veil$getLocation().equals(white), "Arbitrary native sampler names survive setup cloning");
        check(afterNamed.veil$useLightmap() && afterNamed.veil$useOverlay() && afterNamed.veil$forceSolidModelPhase()
                && nativeType.affectsCrumbling() && nativeType.sortOnUpload() && nativeType.outline().isPresent(), "Native flags and named outline texture survive setup cloning");
        foundry.veil.api.client.registry.RenderTypeShardRegistry.addGenericShard(type -> type == nativeType, builder -> builder.writeMaskState(false, true));
        check(nativeType.pipeline().getColorTargetStates().getFirst().writeMask() == 0 && nativeType.pipeline().getDepthStencilState().writeDepth(), "Late filtered modifiers retain native masks");
        foundry.veil.api.client.registry.RenderTypeShardRegistry.addShard(nativeType, builder -> builder.transparencyState(Optional.empty()));
        check(!nativeType.hasBlending(), "Derived native blending flag updates with immutable state replacement");
        final var future = foundry.veil.mixin.rendertype.accessor.RenderTypeAccessor.veil$create("veil:modifier_native", setup);
        check(future.pipeline().getDepthStencilState().depthTest() == CompareOp.NEVER_PASS, "Named modifiers apply exactly once to future native constructor calls");
        final var managed = VeilRenderType.create("veil:modifier_managed", DefaultVertexFormat.POSITION, PrimitiveTopology.TRIANGLES, 256,
                false, false, false, new VeilRenderTypeBuilder().shaderState(Identifier.parse("veil:gpu_rendertype_red"), true).standardUniforms(false).create());
        foundry.veil.api.client.registry.RenderTypeShardRegistry.addShard(managed, builder -> builder.outputState(Identifier.parse("veil:modifier_target"))
                .transparencyState(Optional.of(com.mojang.renderpearl.api.pipeline.BlendFunction.ADDITIVE)));
        check(VeilRenderType.getShards(managed).veilShaderId().equals(Identifier.parse("veil:gpu_rendertype_red"))
                && VeilRenderType.getShards(managed).outputState().equals(Identifier.parse("veil:modifier_target")) && managed.hasBlending(), "Late native edits retain managed shader ownership and output metadata");
        VeilRenderType.release(nativeType); VeilRenderType.release(future); VeilRenderType.release(managed);
        checks.add("existing_future_filtered_native_setup_modifiers");
    }
    private static void draw(Minecraft client, GpuDevice device, List<String> checks, Set<String> selected) {
        final var manager = new FramebufferManager(device, 2, 2);
        try (var shaders = new ShaderManager(device, ShaderManager.PROGRAM_SET, new ShaderPreDefinitions());
             var types = new DynamicRenderTypeManager();
             var output = GpuFramebuffer.builder("render-type output", 2, 2).color(GpuFormat.RGBA8_UNORM).build(device);
             var scratch = GpuFramebuffer.builder("caller active pass", 2, 2).color(GpuFormat.RGBA8_UNORM).build(device);
             var vertices = device.createBuffer(() -> "native render-type vertices", GpuBuffer.USAGE_VERTEX | GpuBuffer.USAGE_COPY_DST, 36);
             var indices = device.createBuffer(() -> "native render-type indices", GpuBuffer.USAGE_INDEX | GpuBuffer.USAGE_COPY_DST, 8)) {
            reload(shaders, client); reload(types, client);
            final var target = Identifier.parse("veil:gpu_rendertype_target");
            manager.setFramebuffer(target, output);
            VeilRenderType.bindRuntime(shaders, manager, Runnable::run);
            if (selected.contains("cache")) {
            final var first = types.get(Identifier.parse("veil:gpu_rendertype_param"), "an");
            final var second = types.get(Identifier.parse("veil:gpu_rendertype_param"), "c0");
            check(first != null && second != null && first != second, "Resource manager must not conflate hash-colliding parameterized types");
            check(first == types.get(Identifier.parse("veil:gpu_rendertype_param"), "an"), "Stable parameters reuse their actual native type");
            checks.add("parameterized_resource_manager");
            }
            if (selected.contains("blocks")) {
                var selectedBlocks = new foundry.veil.impl.client.render.pipeline.VeilShaderBlockState();
                VeilRenderType.bindUniformBlocks(selectedBlocks);
                var block = foundry.veil.api.client.render.shader.block.ShaderBlock.<Vector4f>withSize(device,
                        foundry.veil.api.client.render.shader.block.ShaderBlock.BufferBinding.UNIFORM, 16,
                        (value, bytes) -> value.get(0, bytes));
                var outputB = GpuFramebuffer.builder("second queued block output", 2, 2).color(GpuFormat.RGBA8_UNORM).build(device);
                var targetB = Identifier.parse("veil:gpu_rendertype_target_b");
                manager.setFramebuffer(targetB, outputB);
                var typeA = VeilRenderType.create("veil:block_snapshot_a", DefaultVertexFormat.POSITION, PrimitiveTopology.TRIANGLES, 256,
                        false, false, false, new VeilRenderTypeBuilder().shaderState(Identifier.parse("veil:gpu_rendertype_block"), true)
                                .standardUniforms(false).depthTestState(CompareOp.ALWAYS_PASS).outputState(target).create());
                var typeB = VeilRenderType.create("veil:block_snapshot_b", DefaultVertexFormat.POSITION, PrimitiveTopology.TRIANGLES, 256,
                        false, false, false, new VeilRenderTypeBuilder().shaderState(Identifier.parse("veil:gpu_rendertype_block"), true)
                                .standardUniforms(false).depthTestState(CompareOp.ALWAYS_PASS).outputState(targetB).create());
                try {
                    var positions = ByteBuffer.allocateDirect(36).order(ByteOrder.nativeOrder());
                    for (float value : new float[]{-1, -1, .5f, 3, -1, .5f, -1, 3, .5f}) positions.putFloat(value);
                    positions.flip();
                    var indexed = ByteBuffer.allocateDirect(8).order(ByteOrder.nativeOrder()).putShort((short) 0).putShort((short) 1).putShort((short) 2).putShort((short) 0); indexed.flip();
                    var encoder = device.createCommandEncoder();
                    encoder.writeToBuffer(vertices.slice(), positions); encoder.writeToBuffer(indices.slice(), indexed);
                    output.clear(encoder, new Vector4f(0), 0); outputB.clear(encoder, new Vector4f(0), 0); scratch.clear(encoder, new Vector4f(0), 0);
                    var geometry = new StagedVertexBuffer.ExecuteInfo(vertices, indices, IndexType.SHORT, 0, 0, 3, PrimitiveTopology.TRIANGLES);
                    var preparedA = typeA.prepare(); var preparedB = typeB.prepare();
                    selectedBlocks.bind("SnapshotSettings", block);
                    var mutable = new Vector4f(1, 0, 0, 1); block.set(mutable);
                    try (var pass = scratch.beginPass(encoder)) {
                        preparedA.drawFromBuffer(geometry, pass);
                        mutable.set(0, 0, 1, 1); preparedB.drawFromBuffer(geometry, pass);
                    }
                    selectedBlocks.unbind(block); block.free();
                    try (var submission = NativeRenderTypeDraws.flush(encoder)) { encoder.submit(); }
                    assertColor(device, output, 255, 0, 0, 255); assertColor(device, outputB, 0, 0, 255, 255);
                    assertColor(device, scratch, 0, 0, 0, 0);
                    checks.add("deferred_render_type_captures_serialized_global_blocks");
                } finally {
                    selectedBlocks.clear(); block.free();
                    VeilRenderType.release(typeA); VeilRenderType.release(typeB);
                    manager.removeFramebuffer(targetB); outputB.close(); VeilRenderType.unbindRuntime();
                }
            }
            if (!selected.contains("layers")) return;
            VeilRenderType.bindRuntime(shaders, manager, Runnable::run);
            final var type = types.get(Identifier.parse("veil:gpu_rendertype_ordered"));
            check(type instanceof VeilRenderType.LayeredRenderType && type.affectsCrumbling(), "Real JSON layered type and native crumbling flag");
            final var positions = ByteBuffer.allocateDirect(36).order(ByteOrder.nativeOrder());
            for (float value : new float[]{-1, -1, .5f, 3, -1, .5f, -1, 3, .5f}) positions.putFloat(value);
            positions.flip();
            final var indexed = ByteBuffer.allocateDirect(8).order(ByteOrder.nativeOrder()).putShort((short) 0).putShort((short) 1).putShort((short) 2).putShort((short) 0); indexed.flip();
            final var encoder = device.createCommandEncoder();
            encoder.writeToBuffer(vertices.slice(), positions); encoder.writeToBuffer(indices.slice(), indexed);
            output.clear(encoder, new Vector4f(0, 0, 0, 0), 0); scratch.clear(encoder, new Vector4f(0, 0, 0, 0), 0);
            final var prepared = type.prepare();
            final var geometry = new StagedVertexBuffer.ExecuteInfo(vertices, indices, IndexType.SHORT, 0, 0, 3, PrimitiveTopology.TRIANGLES);
            try (var pass = scratch.beginPass(encoder)) { prepared.drawFromBuffer(geometry, pass); }
            try (var submission = NativeRenderTypeDraws.flush(encoder)) { encoder.submit(); }
            assertColor(device, output, 128, 0, 128, 255);
            assertColor(device, scratch, 0, 0, 0, 0);
            checks.add("same_staged_mesh_ordered_layers_and_explicit_output");
            manager.removeFramebuffer(target);
        } finally { VeilRenderType.unbindRuntime(); manager.free(); }
    }
    private static void reload(PreparableReloadListener listener, Minecraft client) {
        final var state = new PreparableReloadListener.SharedState(client.getResourceManager());
        final var barrier = new PreparableReloadListener.PreparationBarrier() {
            @Override public <T> java.util.concurrent.CompletableFuture<T> wait(T value) { return java.util.concurrent.CompletableFuture.completedFuture(value); }
        };
        listener.reload(state, Runnable::run, barrier, Runnable::run).join();
    }
    private static void assertColor(GpuDevice device, GpuFramebuffer target, int... expected) {
        try (var buffer = device.createBuffer(() -> "native render-type readback", GpuBuffer.USAGE_COPY_DST | GpuBuffer.USAGE_MAP_READ, 16)) {
            final var encoder = device.createCommandEncoder();
            encoder.copyTextureToBuffer(target.color(0).texture(), buffer, 0, () -> {}, 0);
            try (var fence = encoder.createFence()) {
                encoder.submit(); check(fence.awaitCompletion(10_000_000_000L), "GPU readback timed out");
                try (var mapped = buffer.map(true, false)) {
                    for (int index = 0; index < 16; index++) check(Math.abs((mapped.data().get(index) & 255) - expected[index % 4]) <= 1, "Unexpected native layered pixel at byte " + index + ": " + (mapped.data().get(index) & 255));
                }
            }
        }
    }
    private static void check(boolean condition, String reason) { if (!condition) throw new AssertionError(reason); }
}
