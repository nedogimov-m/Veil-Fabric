import com.google.gson.GsonBuilder;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.device.GpuDevice;
import com.mojang.renderpearl.api.textures.FilterMode;
import com.mojang.renderpearl.api.textures.GpuTexture;
import foundry.veil.api.client.render.gpu.GpuFramebuffer;
import foundry.veil.api.client.render.framebuffer.FramebufferDefinition;
import foundry.veil.api.client.render.framebuffer.FramebufferManager;
import foundry.veil.api.client.render.texture.TextureFilter;
import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;
import net.minecraft.resources.Identifier;
import foundry.veil.api.client.render.gpu.GpuFullscreenPass;
import foundry.veil.api.client.render.gpu.GpuUniformBuffer;
import foundry.veil.api.client.render.gpu.GpuShaderProgram;
import foundry.veil.api.client.render.gpu.GpuShaderSource;
import foundry.veil.api.client.render.shader.ShaderManager;
import foundry.veil.api.client.render.shader.ShaderPreDefinitions;
import net.minecraft.util.profiling.InactiveProfiler;
import com.mojang.renderpearl.api.pipeline.*;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import org.joml.Vector4f;
import org.joml.Vector4fc;

import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalDouble;

/** Test-only entrypoint. Runs actual commands and reads GPU memory, then exits. */
public final class GpuClientAcceptance implements ClientModInitializer {
    private int ticks;
    private boolean ran;

    @Override public void onInitializeClient() {
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (this.ran || ++this.ticks < 5 || !client.isGameLoadFinished()) return;
            this.ran = true;
            Map<String, Object> report = new LinkedHashMap<>();
            try {
                check(RenderSystem.isOnRenderThread(), "Acceptance must run on the render thread");
                GpuDevice device = RenderSystem.getDevice();
                report.put("backend", device.getDeviceInfo().backendName());
                report.put("device", device.getDeviceInfo().name());
                check(device.getDeviceInfo().backendName().toLowerCase(java.util.Locale.ROOT).contains("vulkan"), "Expected actual Vulkan backend");
                var checked = new ArrayList<String>();
                report.put("checks", checked);
                run(client, device, checked);
                report.put("status", "passed");
            } catch (Throwable failure) {
                failure.printStackTrace();
                report.put("status", "failed");
                report.put("error", failure.toString());
            } finally {
                try {
                    Files.writeString(Path.of(System.getProperty("veil.gpu.acceptance.report")),
                            new GsonBuilder().setPrettyPrinting().create().toJson(report) + "\n");
                } catch (Exception failure) { throw new RuntimeException(failure); }
                client.stop();
            }
        });
    }

    private static void run(Minecraft client, GpuDevice device, List<String> checked) throws Exception {
        var selected = java.util.Set.of(System.getProperty("veil.gpu.acceptance.selection", "attachments,copies,blit,resize,borrow,definitions").split(","));
        if (selected.contains("meshes")) GpuMeshAcceptance.run(client, device, checked);
        if (selected.contains("bridge")) GpuWorldBridgeAcceptance.run(client, device, checked);
        if (selected.contains("dynamic")) GpuDynamicBufferAcceptance.run(client, device, checked);
        if (selected.contains("dynamicvariants")) GpuDynamicVariantAcceptance.run(client, device, checked);
        if (selected.contains("dynamiccache")) GpuDynamicVariantAcceptance.runCache(client, device, checked);
        if (selected.contains("dynamicworld")) GpuDynamicWorldAcceptance.run(client, device, checked);
        if (selected.contains("managedreplay")) GpuManagedReplayAcceptance.run(client, device, checked);
        if (selected.contains("classiccoverage")) GpuDynamicCoverageAcceptance.run(client, device, checked);
        if (selected.contains("sourceblocks")) GpuSourceBlocksAcceptance.run(client, device, checked);
        if (selected.contains("manageduniforms")) GpuManagedUniformAcceptance.run(client, device, checked);
        if (selected.contains("manageduniformreload")) GpuManagedUniformAcceptance.runReload(client, device, checked);
        if (selected.contains("managedsamplers")) GpuManagedSamplerAcceptance.run(client, device, checked);
        if (selected.contains("debug")) {
            var first = RenderPipeline.builder().withLocation(Identifier.parse("veil:gpu_debug_first"))
                    .withVertexShader(Identifier.parse("veil:core/gpu_blit")).withFragmentShader(Identifier.parse("veil:core/gpu_debug"))
                    .withPrimitiveTopology(PrimitiveTopology.TRIANGLES).withCull(false).withDepthStencilState(Optional.empty())
                    .withColorTargetState(ColorTargetState.DEFAULT).build();
            var second = RenderPipeline.builder().withLocation(Identifier.parse("veil:gpu_debug_second"))
                    .withVertexShader(Identifier.parse("veil:core/gpu_blit")).withFragmentShader(Identifier.parse("veil:core/gpu_debug"))
                    .withPrimitiveTopology(PrimitiveTopology.TRIANGLES).withCull(false).withDepthStencilState(Optional.empty())
                    .withColorTargetState(ColorTargetState.DEFAULT).build();
            try (var source = new GpuShaderSource(client.getResourceManager());
                 var cache = new com.mojang.blaze3d.pipeline.PipelineCache(device, source);
                 var target = GpuFramebuffer.builder("native debug cache", 2, 2).color(GpuFormat.RGBA8_UNORM).build(device)) {
                var live = ((foundry.veil.mixin.debug.accessor.DebugPipelineCacheAccessor) cache).veil$getCache();
                check(live.isEmpty(), "Actual new native cache should be empty");
                foundry.veil.impl.client.render.shader.debug.NativePipelineRegistry.snapshot();
                check(live.isEmpty(), "Debug enumeration must not compile unused native definitions");
                var compiled = cache.get(first);
                var entries = foundry.veil.impl.client.render.shader.debug.NativePipelineRegistry.snapshot();
                check(live.size() == 1 && entries.stream().anyMatch(entry -> entry.definition() == first && entry.compiled().get() == compiled && entry.isLive()),
                        "Actual cache get path must appear without relying on insert hooks");
                var inserted = device.compilePipeline(second, source, Runnable::run).join().finishCompile(); cache.insert(second, inserted);
                entries = foundry.veil.impl.client.render.shader.debug.NativePipelineRegistry.snapshot();
                check(live.size() == 2 && entries.stream().anyMatch(entry -> entry.definition() == second && entry.compiled().get() == inserted), "Actual cache insert path must appear in native inspection");
                var encoder = device.createCommandEncoder();
                try (var pass = target.beginPass(encoder)) { pass.setPipeline(compiled); pass.draw(3, 1, 0, 0); }
                encoder.submit(); assertColor(device, target.color(0).texture(), 64, 128, 191, 255);
                try (var values = new GpuUniformBuffer(device, "inspector uint values", GpuUniformBuffer.layoutBuilder().field("Value", GpuUniformBuffer.ValueType.UINT).build())) {
                    values.setInt("Value", -1);
                    check(foundry.veil.impl.client.editor.ShaderInspector.formatField(values, values.layout().fields().get("Value"), 0).equals("[4294967295]"), "Inspector must retain unsigned native field precision");
                }
                cache.clear();
                entries = foundry.veil.impl.client.render.shader.debug.NativePipelineRegistry.snapshot();
                check(compiled.isClosed() && inserted.isClosed() && entries.stream().noneMatch(entry -> entry.definition() == first || entry.definition() == second),
                        "Retired native cache entries must leave inspection without retaining GPU resources");
            }
            record(checked, "real native cache mixins observe get/insert/retirement without compiling debug selections, pipeline draws exact pixels, typed inspector formatting preserves uint precision; UI backend remains separate");
        }
        if (selected.contains("processors")) {
            var definitions = new ShaderPreDefinitions(); definitions.set("processor_green", "0.5");
            var state = RenderPipeline.builder().withLocation(Identifier.parse("veil:gpu_processor_acceptance"))
                    .withVertexShader(Identifier.parse("veil:core/gpu_blit")).withFragmentShader(Identifier.parse("veil:core/gpu_processor"))
                    .withPrimitiveTopology(PrimitiveTopology.TRIANGLES).withCull(false).withDepthStencilState(Optional.empty())
                    .withColorTargetState(ColorTargetState.DEFAULT).build();
            var visits = new ArrayList<String>(); var invalid = new java.util.concurrent.atomic.AtomicBoolean();
            try (var shaders = new ShaderManager(device, ShaderManager.PROGRAM_SET, definitions);
                 var output = GpuFramebuffer.builder("AST processor output", 2, 2).color(GpuFormat.RGBA8_UNORM).build(device)) {
                shaders.addPreprocessorRegistry((resources, registry) -> registry.addPreprocessor((ctx, tree) -> {
                    check(ctx instanceof foundry.veil.api.client.render.shader.processor.ShaderPreProcessor.VeilContext, "Managed native shader requires the real Veil context");
                    visits.add(ctx.typeName() + ":" + ctx.isSourceFile() + ":" + ctx.name());
                    if (!ctx.isSourceFile() || !ctx.isFragment()) return;
                    if (invalid.get()) throw new java.io.IOException("Deliberate native processor failure");
                    var veil = (foundry.veil.api.client.render.shader.processor.ShaderPreProcessor.VeilContext) ctx;
                    check(!veil.isDynamic() && veil.definition() != null, "Resource native context must retain its actual definition");
                    veil.addDefinitionDependency("processor_green");
                    ctx.macros().put("PROCESSOR_GREEN", ctx.preDefinitions().getDefinition("processor_green"));
                    ctx.include(tree, Identifier.parse("veil:gpu_processor_tint"), foundry.veil.api.client.render.shader.processor.ShaderPreProcessor.IncludeOverloadStrategy.SOURCE);
                }, true));
                reload(shaders, client.getResourceManager()); var id = Identifier.parse("veil:gpu_processor");
                var program = shaders.getShader(id, state, Runnable::run); drawProgram(device, program, output);
                assertColor(device, output.color(0).texture(), 64, 128, 191, 255);
                check(program.includedResources().contains(Identifier.parse("veil:gpu_processor_base.glsl"))
                                && program.includedResources().contains(Identifier.parse("veil:gpu_processor_tint"))
                                && program.getDefinitionDependencies().contains("processor_green"),
                        "Actual AST imports and processor-observed definitions must drive native reload dependencies");
                check(visits.stream().anyMatch(visit -> visit.contains("false:veil:gpu_processor_base.glsl"))
                                && visits.stream().anyMatch(visit -> visit.contains("false:veil:gpu_processor_tint")),
                        "Import processors must run with genuine include contexts");
                definitions.set("processor_green", "0.25"); check(shaders.hasPendingRecompile(), "Processor-added definition dependency did not invalidate native program");
                var changed = shaders.getShader(id, state, Runnable::run); drawProgram(device, changed, output);
                check(program.isClosed() && changed != program, "Changed processor dependency must replace native owner");
                assertColor(device, output.color(0).texture(), 64, 64, 191, 255);
                shaders.scheduleRecompileForInclude(Identifier.parse("veil:gpu_processor_base"));
                check(shaders.hasPendingRecompile(), "Native .glsl imports must match resource edit IDs");
                program = shaders.getShader(id, state, Runnable::run); drawProgram(device, program, output);
                invalid.set(true); shaders.scheduleRecompileForInclude(Identifier.parse("veil:gpu_processor_tint"));
                try { shaders.getShader(id, state, Runnable::run); throw new AssertionError("Failing source processor installed a native program"); }
                catch (RuntimeException expected) { check(!program.isClosed(), "Source processor failure destroyed the last working native variant"); }
                check(shaders.getShader(id, state, Runnable::run) == program, "Failed processing must preserve cached native owner until another edit");
                drawProgram(device, program, output); assertColor(device, output.color(0).texture(), 64, 64, 191, 255);
            }
            record(checked, "registered native GLSL AST processors and native/custom includes drive real pixels, include contexts, dynamic macro dependencies and transactional failure preservation");
        }
        try (var source = GpuFramebuffer.builder("veil-test/source", 2, 2)
                .color(GpuFormat.RGBA8_UNORM).color(GpuFormat.RGBA8_UNORM).depth(GpuFormat.D32_FLOAT).build(device);
             var copied = GpuFramebuffer.builder("veil-test/copied", 2, 2)
                     .color(GpuFormat.RGBA8_UNORM).depth(GpuFormat.D32_FLOAT).build(device);
             var scaled = GpuFramebuffer.builder("veil-test/scaled", 4, 4).color(GpuFormat.RGBA8_UNORM).build(device);
             var blit = new GpuFullscreenPass(device, client.getResourceManager(), Runnable::run, FilterMode.NEAREST, null)) {
            var encoder = device.createCommandEncoder();
            try (var pass = source.beginPass(encoder, List.of(
                    Optional.<Vector4fc>of(new Vector4f(1, 0.25F, 0.5F, 1)),
                    Optional.<Vector4fc>of(new Vector4f(0, 1, 0, 1))), OptionalDouble.of(0.375))) {
                // Closing the real pass executes its native load/clear operations.
            }
            encoder.submit();
            if (selected.contains("attachments")) {
            assertColor(device, source.color(0).texture(), 255, 64, 128, 255);
            assertColor(device, source.color(1).texture(), 0, 255, 0, 255);
            assertDepth(device, source.depth().texture(), 0.375F);
            record(checked, "MRT color+depth pass clear and pixel/depth readback");
            }

            if (selected.contains("copies")) {
            encoder = device.createCommandEncoder();
            source.copyColorTo(encoder, 0, copied, 0);
            source.copyDepthTo(encoder, copied);
            encoder.submit();
            assertColor(device, copied.color(0).texture(), 255, 64, 128, 255);
            assertDepth(device, copied.depth().texture(), 0.375F);
            record(checked, "color/depth texture copies retain pixels");
            }

            if (selected.contains("blit")) {
            encoder = device.createCommandEncoder();
            scaled.clear(encoder, new Vector4f(), 1);
            blit.blit(encoder, source.color(0).view(), scaled.color(0).view());
            encoder.submit();
            assertColor(device, scaled.color(0).texture(), 255, 64, 128, 255);
            record(checked, "native shader compile, sampler, scaled fullscreen draw and pixel readback");

            blit.invalidatePipelines();
            encoder = device.createCommandEncoder();
            blit.blit(encoder, source.color(1).view(), scaled.color(0).view());
            encoder.submit();
            assertColor(device, scaled.color(0).texture(), 0, 255, 0, 255);
            record(checked, "pipeline invalidation recompiles and draws new source");
            }

            if (selected.contains("resize")) {
            var oldTexture = source.color(0).texture();
            var oldView = source.color(0).view();
            source.resize(3, 3);
            check(source.width() == 3 && source.height() == 3, "Resize must update dimensions");
            check(oldTexture.isClosed() && oldView.isClosed(), "Resize must close old texture and view");
            var current = source.color(0).texture();
            try { source.resize(0, 3); throw new AssertionError("Invalid resize was accepted"); }
            catch (IllegalArgumentException expected) {
                check(source.width() == 3 && source.color(0).texture() == current && !current.isClosed(), "Failed resize must preserve old resources");
            }
            record(checked, "resize closes old resources; failed resize retains live set");
            }
        }
        if (selected.contains("borrow")) {
        var owner = new TextureTarget("veil-test/borrow-owner", 2, 2, GpuFormat.RGBA8_UNORM, GpuFormat.D32_FLOAT);
        try {
            var borrowedColor = owner.getColorTexture();
            try (var borrowed = GpuFramebuffer.borrow(device, "veil-test/borrowed", owner)) {
                var encoder = device.createCommandEncoder();
                borrowed.clear(encoder, new Vector4f(0, 0, 1, 1), 0.5);
                encoder.submit();
            }
            check(!borrowedColor.isClosed() && !owner.getColorTextureView().isClosed(), "Borrow close must not close Minecraft resources");
            assertColor(device, borrowedColor, 0, 0, 255, 255);
            record(checked, "borrowed RenderTarget views retain external ownership");
        } finally { owner.destroyBuffers(); }
        }
        if (selected.contains("uniforms")) {
            var layout = GpuUniformBuffer.layoutBuilder()
                    .field("BaseColor", GpuUniformBuffer.ValueType.VEC3)
                    .field("Scale", GpuUniformBuffer.ValueType.FLOAT)
                    .field("Transform", GpuUniformBuffer.ValueType.MAT3)
                    .array("Weights", GpuUniformBuffer.ValueType.FLOAT, 2)
                    .field("Flags", GpuUniformBuffer.ValueType.IVEC2)
                    .field("Project", GpuUniformBuffer.ValueType.MAT4).build();
            check(layout.size() == 176 && layout.fields().get("Scale").baseOffset() == 12
                    && layout.fields().get("Weights").stride() == 16 && layout.fields().get("Project").baseOffset() == 112,
                    "std140 scalar/vector/matrix/array offsets must match GLSL");
            var shader = RenderPipeline.builder().withLocation(Identifier.parse("veil:gpu_uniform_acceptance"))
                    .withVertexShader(Identifier.parse("veil:core/gpu_blit"))
                    .withFragmentShader(Identifier.parse("veil:core/gpu_uniform_test"))
                    .withPrimitiveTopology(PrimitiveTopology.TRIANGLES).withCull(false)
                    .withDepthStencilState(Optional.empty()).withColorTargetState(ColorTargetState.DEFAULT)
                    .withBindGroupLayout(BindGroupLayout.builder().withUniform("VeilUniforms", UniformType.UNIFORM_BUFFER).build()).build();
            try (var source = new GpuShaderSource(client.getResourceManager());
                 var program = new GpuShaderProgram(device, shader, source, Runnable::run);
                 var target = GpuFramebuffer.builder("uniform acceptance", 2, 2).color(GpuFormat.RGBA8_UNORM).build(device)) {
                var uniforms = program.createUniformBlock("VeilUniforms", layout);
                uniforms.setVector("BaseColor", 0.2F, 0.4F, 0.6F);
                uniforms.setFloat("Scale", 0.5F);
                uniforms.setMatrix("Transform", new org.joml.Matrix3f().scaling(1, 0.5F, 1));
                uniforms.setFloats("Weights", 0, 0.25F);
                uniforms.setFloats("Weights", 1, 0.75F);
                uniforms.setVectorI("Flags", 1, 2);
                uniforms.setMatrix("Project", new org.joml.Matrix4f().m33(0.75F));
                var encoder = device.createCommandEncoder();
                program.prepare(encoder);
                try (var pass = target.beginPass(encoder)) {
                    program.bind(pass);
                    pass.draw(3, 1, 0, 0);
                }
                encoder.submit();
                assertColor(device, target.color(0).texture(), 26, 26, 153, 191);
            }
            record(checked, "typed shader program std140 vectors/scalars/mat3/mat4/arrays/integers drive actual GPU pixels");
        }
        if (selected.contains("manager")) {
            var preDefinitions = new ShaderPreDefinitions();
            class ManagedShaders extends ShaderManager {
                ManagedShaders() { super(device, ShaderManager.PROGRAM_SET, preDefinitions); }
                void load() {
                    var resources = client.getResourceManager();
                    apply(prepare(resources, InactiveProfiler.INSTANCE), resources, InactiveProfiler.INSTANCE);
                }
            }
            var state = RenderPipeline.builder().withLocation(Identifier.parse("veil_test:managed_state"))
                    .withVertexShader(Identifier.parse("veil:core/gpu_blit"))
                    .withFragmentShader(Identifier.parse("veil:core/gpu_managed"))
                    .withPrimitiveTopology(PrimitiveTopology.TRIANGLES).withCull(false)
                    .withDepthStencilState(Optional.empty()).withColorTargetState(ColorTargetState.DEFAULT)
                    .withBindGroupLayout(BindGroupLayout.builder().withUniform("TintSettings", UniformType.UNIFORM_BUFFER).build()).build();
            var name = Identifier.parse("veil:gpu_managed");
            try (var shaders = new ManagedShaders();
                 var input = GpuFramebuffer.builder("managed input", 2, 2).color(GpuFormat.RGBA8_UNORM).build(device);
                 var target = GpuFramebuffer.builder("managed output", 2, 2).color(GpuFormat.RGBA8_UNORM).build(device)) {
                shaders.load();
                var program = shaders.getShader(name, state, Runnable::run);
                check(shaders.getShader(name, state, Runnable::run) == program, "Unchanged draw pipeline must reuse its compiled shader variant");
                var blockLayout = GpuUniformBuffer.layoutBuilder().field("TintColor", GpuUniformBuffer.ValueType.VEC4).build();
                var uniforms = program.createUniformBlock("TintSettings", blockLayout);
                uniforms.setVector("TintColor", 1, 1, 1, 0.5F);
                shaders.bindTextures(name, program, id -> id.equals(Identifier.parse("veil_test:input")) ? input : null);
                var encoder = device.createCommandEncoder();
                input.clear(encoder, new Vector4f(1, 0.5F, 0.25F, 1), 1);
                target.clear(encoder, new Vector4f(), 1);
                program.prepare(encoder);
                try (var pass = target.beginPass(encoder)) { program.bind(pass); pass.draw(3, 1, 0, 0); }
                encoder.submit();
                assertColor(device, target.color(0).texture(), 128, 64, 32, 128);
                preDefinitions.set("veil_test_gain", "0.5");
                var replacement = shaders.getShader(name, state, Runnable::run);
                check(replacement != program && program.isClosed(), "Dirty definition must replace its native program and owned blocks");
                replacement.createUniformBlock("TintSettings", blockLayout).setVector("TintColor", 1, 1, 1, 0.5F);
                shaders.bindTextures(name, replacement, id -> input);
                encoder = device.createCommandEncoder();
                target.clear(encoder, new Vector4f(), 1);
                replacement.prepare(encoder);
                try (var pass = target.beginPass(encoder)) { replacement.bind(pass); pass.draw(3, 1, 0, 0); }
                encoder.submit();
                assertColor(device, target.color(0).texture(), 32, 16, 8, 64);
            }
            record(checked, "normal ShaderManager definitions, native blend equations, dynamic framebuffer samplers, pipeline cache and predefinition recompile drive GPU pixels");
        }
        if (selected.contains("depth")) {
            var manager = new FramebufferManager(device, 2, 2);
            var main = new TextureTarget("legacy depth input", 2, 2, GpuFormat.RGBA8_UNORM, GpuFormat.D32_FLOAT);
            try (var shaders = new ShaderManager(device, ShaderManager.PROGRAM_SET, new ShaderPreDefinitions())) {
                var resources = client.getResourceManager(); reload(shaders, resources);
                manager.setMainFramebuffer(main);
                manager.setDefinition(foundry.veil.api.client.render.framebuffer.VeilFramebuffers.POST,
                        FramebufferDefinition.CODEC.parse(JsonOps.INSTANCE, JsonParser.parseString("{\"depth\":true,\"autoClear\":false}")).getOrThrow());
                var post = new foundry.veil.api.client.render.post.PostProcessingManager(device, resources, shaders, manager,
                        Runnable::run, type -> { throw new AssertionError("Depth test requests no dynamic buffers"); }, () -> 0, mask -> { });
                var near = depthPipeline(foundry.veil.api.client.render.post.stage.DepthFunctionPostStage.DepthFunc.LESS, new float[]{1, 0, 0, 1});
                var far = depthPipeline(foundry.veil.api.client.render.post.stage.DepthFunctionPostStage.DepthFunc.GREATER, new float[]{0, 0, 1, 1});
                try {
                    var output = manager.getFramebuffer(foundry.veil.api.client.render.framebuffer.VeilFramebuffers.POST);
                    var encoder = device.createCommandEncoder();
                    manager.getFramebuffer(Identifier.parse("minecraft:main")).clear(encoder, new Vector4f(1), 0);
                    output.clear(encoder, new Vector4f(), 0.25); encoder.submit();
                    post.runPipeline(near, false); assertColor(device, output.color(0).texture(), 255, 0, 0, 255);
                    post.runPipeline(far, false); assertColor(device, output.color(0).texture(), 255, 0, 0, 255);
                    assertDepth(device, output.depth().texture(), 0.25F);
                    encoder = device.createCommandEncoder(); output.clear(encoder, new Vector4f(1, 0, 0, 1), 0.75); encoder.submit();
                    post.runPipeline(far, false); assertColor(device, output.color(0).texture(), 0, 0, 255, 255);
                    assertDepth(device, output.depth().texture(), 0.75F);
                    record(checked, "legacy closer/farther post depth keywords preserve meaning under native reversed Z; actual fragments pass/reject against depth and leave unwritten depth intact");
                } finally { near.free(); far.free(); post.free(); }
            } finally { manager.free(); main.destroyBuffers(); }
        }
        if (selected.contains("reload")) {
            var resources = client.getResourceManager();
            var editedFile = Identifier.parse("veil:shaders/core/gpu_post.fsh");
            String original = resources.getResourceOrThrow(editedFile).readAllAsString();
            var edited = new java.util.concurrent.atomic.AtomicReference<>(original);
            var editable = (net.minecraft.server.packs.resources.ResourceManager) java.lang.reflect.Proxy.newProxyInstance(
                    net.minecraft.server.packs.resources.ResourceManager.class.getClassLoader(),
                    new Class<?>[]{net.minecraft.server.packs.resources.ResourceManager.class}, (proxy, method, arguments) -> {
                        if ((method.getName().equals("getResource") || method.getName().equals("getResourceOrThrow"))
                                && editedFile.equals(arguments[0])) {
                            var resource = new net.minecraft.server.packs.resources.Resource(resources.getResourceOrThrow(editedFile).source(),
                                    () -> new java.io.ByteArrayInputStream(edited.get().getBytes(java.nio.charset.StandardCharsets.UTF_8)));
                            return method.getName().equals("getResource") ? Optional.of(resource) : resource;
                        }
                        try { return method.invoke(resources, arguments); }
                        catch (java.lang.reflect.InvocationTargetException failure) { throw failure.getCause(); }
                    });
            var state = RenderPipeline.builder().withLocation(Identifier.parse("veil:gpu_reload_acceptance"))
                    .withVertexShader(Identifier.parse("veil:core/gpu_blit")).withFragmentShader(Identifier.parse("veil:core/gpu_post"))
                    .withPrimitiveTopology(PrimitiveTopology.TRIANGLES).withCull(false).withDepthStencilState(Optional.empty())
                    .withColorTargetState(ColorTargetState.DEFAULT)
                    .withBindGroupLayout(BindGroupLayout.builder().withUniform("PostUniforms", UniformType.UNIFORM_BUFFER)
                            .withUniform("DiffuseSampler0", UniformType.COMBINED_IMAGE_SAMPLER).build()).build();
            try (var shaders = new ShaderManager(device, ShaderManager.PROGRAM_SET, new ShaderPreDefinitions());
                 var input = GpuFramebuffer.builder("reload source", 2, 2).color(GpuFormat.RGBA8_UNORM).build(device);
                 var output = GpuFramebuffer.builder("reload output", 2, 2).color(GpuFormat.RGBA8_UNORM).build(device)) {
                reload(shaders, editable); var id = Identifier.parse("veil:gpu_program");
                var layout = GpuUniformBuffer.layoutBuilder().field("InSize", GpuUniformBuffer.ValueType.VEC2)
                        .field("OutSize", GpuUniformBuffer.ValueType.VEC2).field("TintColor", GpuUniformBuffer.ValueType.VEC4).build();
                var first = shaders.getShader(id, state, Runnable::run);
                first.createUniformBlock("PostUniforms", layout).setVector("TintColor", 1, 1, 1, 1); first.setFramebufferSamplers(input);
                var encoder = device.createCommandEncoder(); input.clear(encoder, new Vector4f(1, 0.5F, 0.25F, 1), 1); encoder.submit();
                drawProgram(device, first, output); assertColor(device, output.color(0).texture(), 255, 128, 64, 255);
                check(first.includedResources().contains(Identifier.parse("veil:gpu_fullscreen.glsl")), "Native compiler must record its real include dependency");
                edited.set("#version 330\nthis shader edit is deliberately invalid\n");
                shaders.scheduleRecompileForInclude(Identifier.parse("veil:gpu_fullscreen"));
                check(shaders.hasPendingRecompile(), "Include edits must schedule the programs using native compiler-observed dependencies");
                try { shaders.getShader(id, state, Runnable::run); throw new AssertionError("Invalid shader replacement was accepted"); }
                catch (RuntimeException expected) { check(!first.isClosed(), "Invalid replacement destroyed the working program"); }
                check(shaders.getShader(id, state, Runnable::run) == first && !shaders.hasPendingRecompile(), "A failed edit must preserve the live variant without recompiling every frame");
                drawProgram(device, first, output); assertColor(device, output.color(0).texture(), 255, 128, 64, 255);
                edited.set(original.replace("texture(DiffuseSampler0, texCoord) * TintColor", "texture(DiffuseSampler0, texCoord) * TintColor * vec4(0, 1, 0, 1)"));
                shaders.scheduleRecompileForSource(foundry.veil.api.client.render.shader.ShaderSourceSet.getShaderType(editedFile), Identifier.parse("veil:core/gpu_post"));
                var replacement = shaders.getShader(id, state, Runnable::run);
                check(replacement != first && first.isClosed(), "Successful source edit must commit and retire the previous native program");
                replacement.createUniformBlock("PostUniforms", layout).setVector("TintColor", 1, 1, 1, 1); replacement.setFramebufferSamplers(input);
                drawProgram(device, replacement, output); assertColor(device, output.color(0).texture(), 0, 128, 0, 255);
                record(checked, "native include/source edit tracking, failed GPU compilation retains working pixels, later successful edit swaps actual native program pixels");
            }
        }
        if (selected.contains("program")) {
            var resources = client.getResourceManager();
            var state = RenderPipeline.builder().withLocation(Identifier.parse("veil:gpu_program_acceptance"))
                    .withVertexShader(Identifier.parse("veil:core/gpu_blit")).withFragmentShader(Identifier.parse("veil:core/gpu_post"))
                    .withPrimitiveTopology(PrimitiveTopology.TRIANGLES).withCull(false).withDepthStencilState(Optional.empty())
                    .withColorTargetState(ColorTargetState.DEFAULT)
                    .withBindGroupLayout(BindGroupLayout.builder().withUniform("PostUniforms", UniformType.UNIFORM_BUFFER)
                            .withUniform("DiffuseSampler0", UniformType.COMBINED_IMAGE_SAMPLER).build()).build();
            try (var shaders = new ShaderManager(device, ShaderManager.PROGRAM_SET, new ShaderPreDefinitions());
                 var input = GpuFramebuffer.builder("native program source", 2, 2).color(GpuFormat.RGBA8_UNORM).build(device);
                 var target = GpuFramebuffer.builder("native program output", 2, 2).color(GpuFormat.RGBA8_UNORM).build(device)) {
                reload(shaders, resources);
                var id = Identifier.parse("veil:gpu_program");
                foundry.veil.api.client.render.shader.program.ShaderProgram program = shaders.getShader(id, state, Runnable::run);
                check(program.getDefinition() == shaders.getDefinitions().get(id) && program.getDefinitionDependencies().contains("veil_program_gain"),
                        "Native resource program must retain its actual definition and predefinition dependencies");
                var layout = GpuUniformBuffer.layoutBuilder().field("InSize", GpuUniformBuffer.ValueType.VEC2)
                        .field("OutSize", GpuUniformBuffer.ValueType.VEC2).field("TintColor", GpuUniformBuffer.ValueType.VEC4).build();
                var block = program.createUniformBlock("PostUniforms", layout);
                block.setVector("InSize", 2, 2); block.setVector("OutSize", 2, 2);
                program.getUniform("PostUniforms", "TintColor").setVector(0.5F, 1, 1, 1);
                program.setFramebufferSamplers(input);
                var encoder = device.createCommandEncoder(); input.clear(encoder, new Vector4f(1, 0.5F, 0.25F, 1), 1);
                program.prepare(encoder);
                try (var pass = target.beginPass(encoder)) { program.bind(pass); pass.draw(3, 1, 0, 0); }
                encoder.submit(); assertColor(device, target.color(0).texture(), 128, 128, 64, 255);
                var metadata = new foundry.veil.api.client.render.shader.program.ShaderUniformCache((GpuShaderProgram) program);
                check(metadata.hasSampler("DiffuseSampler0") && metadata.getUniformBlock("PostUniforms").fields().orElseThrow().size() == 3,
                        "Native resource bindings and field metadata must retain their schemas");
                try (var external = device.createBuffer(() -> "external native block", GpuBuffer.USAGE_UNIFORM, layout.size())) {
                    program.setUniformBlock("PostUniforms", external.slice());
                    check(metadata.getUniformBlock("PostUniforms").size().orElseThrow() == layout.size()
                            && metadata.getUniformBlock("PostUniforms").fields().isEmpty(), "Borrowed UBO metadata must expose its real byte range and explicitly unknown field schema");
                    shaders.releaseShader(id, state);
                    check(program.isClosed() && !external.isClosed(), "Native program retirement must preserve borrowed storage");
                }
            }
            record(checked, "normal native ShaderProgram/UniformAccess/TextureUniformAccess contracts, resource provenance, framebuffer samplers, borrowed metadata and retirement drive GPU pixels");
        }
        if (selected.contains("snapshots")) {
            var state = RenderPipeline.builder().withLocation(Identifier.parse("veil:gpu_snapshot_acceptance"))
                    .withVertexShader(Identifier.parse("veil:core/gpu_blit")).withFragmentShader(Identifier.parse("veil:core/gpu_snapshot"))
                    .withPrimitiveTopology(PrimitiveTopology.TRIANGLES).withCull(false).withDepthStencilState(Optional.empty())
                    .withColorTargetState(ColorTargetState.DEFAULT)
                    .withBindGroupLayout(BindGroupLayout.builder().withUniform("SnapshotSettings", UniformType.UNIFORM_BUFFER)
                            .withUniform("InSampler", UniformType.COMBINED_IMAGE_SAMPLER).build()).build();
            try (var source = new GpuShaderSource(client.getResourceManager()); var program = new GpuShaderProgram(device, state, source, Runnable::run);
                 var inputA = GpuFramebuffer.builder("snapshot input A", 2, 2).color(GpuFormat.RGBA8_UNORM).build(device);
                 var inputB = GpuFramebuffer.builder("snapshot input B", 2, 2).color(GpuFormat.RGBA8_UNORM).build(device);
                 var targetA = GpuFramebuffer.builder("snapshot output A", 2, 2).color(GpuFormat.RGBA8_UNORM).build(device);
                 var targetB = GpuFramebuffer.builder("snapshot output B", 2, 2).color(GpuFormat.RGBA8_UNORM).build(device)) {
                var layout = GpuUniformBuffer.layoutBuilder().field("Tint", GpuUniformBuffer.ValueType.VEC4).build();
                var block = program.createUniformBlock("SnapshotSettings", layout);
                block.setVector("Tint", 1, 0, 0, 1); program.setTexture("InSampler", inputA.color(0).view(), TextureFilter.CLAMP);
                var ownedA = program.snapshotOwnedUniforms(); var texturesA = program.snapshotTextures();
                block.setVector("Tint", 0, 0, 1, 1); program.setTexture("InSampler", inputB.color(0).view(), TextureFilter.CLAMP);
                var ownedB = program.snapshotOwnedUniforms(); var texturesB = program.snapshotTextures();
                check(ownedA.get("SnapshotSettings").bytes().isReadOnly(), "Captured uniform bytes must be immutable");
                var encoder = device.createCommandEncoder();
                inputA.clear(encoder, new Vector4f(0.5F, 1, 1, 1), 1); inputB.clear(encoder, new Vector4f(1, 1, 0.5F, 1), 1);
                var bindingsA = program.prepareUniformSnapshots(encoder, ownedA); var bindingsB = program.prepareUniformSnapshots(encoder, ownedB);
                var sliceA = bindingsA.get("SnapshotSettings"); var sliceB = bindingsB.get("SnapshotSettings");
                check(sliceA.buffer() != sliceB.buffer() || sliceA.offset() != sliceB.offset(), "Queued draws must use distinct native uniform ranges");
                try (var pass = targetA.beginPass(encoder)) { program.bind(pass, bindingsA, texturesA); pass.draw(3, 1, 0, 0); }
                try (var pass = targetB.beginPass(encoder)) { program.bind(pass, bindingsB, texturesB); pass.draw(3, 1, 0, 0); }
                encoder.submit(); assertColor(device, targetA.color(0).texture(), 128, 0, 0, 255); assertColor(device, targetB.color(0).texture(), 0, 0, 128, 255);
                block.restore(ownedA.get("SnapshotSettings"));
                check(java.util.Arrays.equals(block.getFloats("Tint", 0), new float[]{1, 0, 0, 1}), "Restore must recover the exact captured CPU values");
                try { block.restore(java.nio.ByteBuffer.allocateDirect(4)); throw new AssertionError("Wrong-size snapshot was accepted"); }
                catch (IllegalArgumentException expected) { }
                try (var externalA = device.createBuffer(() -> "snapshot external A", GpuBuffer.USAGE_UNIFORM, 16);
                     var externalB = device.createBuffer(() -> "snapshot external B", GpuBuffer.USAGE_UNIFORM, 16)) {
                    program.setUniformBlock("SnapshotSettings", externalA.slice()); var captured = program.snapshotExternalUniforms();
                    program.setUniformBlock("SnapshotSettings", externalB.slice());
                    check(captured.get("SnapshotSettings").buffer() == externalA, "Captured borrowed bindings must survive later binding-map changes");
                }
            }
            record(checked, "queued native UBO/texture snapshots preserve distinct colors in one encoder, typed restore and captured external bindings");
        }
        if (selected.contains("blocksnapshots")) {
            var state = RenderPipeline.builder().withLocation(Identifier.parse("veil:gpu_block_snapshot_acceptance"))
                    .withVertexShader(Identifier.parse("veil:core/gpu_blit")).withFragmentShader(Identifier.parse("veil:core/gpu_snapshot"))
                    .withPrimitiveTopology(PrimitiveTopology.TRIANGLES).withCull(false).withDepthStencilState(Optional.empty())
                    .withColorTargetState(ColorTargetState.DEFAULT)
                    .withBindGroupLayout(BindGroupLayout.builder().withUniform("SnapshotSettings", UniformType.UNIFORM_BUFFER)
                            .withUniform("InSampler", UniformType.COMBINED_IMAGE_SAMPLER).build()).build();
            var blocks = new foundry.veil.impl.client.render.pipeline.VeilShaderBlockState();
            var block = foundry.veil.api.client.render.shader.block.ShaderBlock.<Vector4f>dynamic(device,
                    foundry.veil.api.client.render.shader.block.ShaderBlock.BufferBinding.UNIFORM, 16,
                    (value, bytes) -> value.get(0, bytes));
            try (var source = new GpuShaderSource(client.getResourceManager()); var program = new GpuShaderProgram(device, state, source, Runnable::run);
                 var input = GpuFramebuffer.builder("block snapshot input", 2, 2).color(GpuFormat.RGBA8_UNORM).build(device);
                 var outputA = GpuFramebuffer.builder("block snapshot A", 2, 2).color(GpuFormat.RGBA8_UNORM).build(device);
                 var outputB = GpuFramebuffer.builder("block snapshot B", 2, 2).color(GpuFormat.RGBA8_UNORM).build(device);
                 var external = device.createBuffer(() -> "borrowed block snapshot", GpuBuffer.USAGE_UNIFORM | GpuBuffer.USAGE_COPY_DST, 32)) {
                program.setTexture("InSampler", input.color(0).view(), TextureFilter.CLAMP);
                var value = new Vector4f(1, 0, 0, 1);
                block.set(value); blocks.bind("SnapshotSettings", block);
                var capturedA = blocks.snapshot(program);
                value.set(0, 0, 1, 1); block.setSize(32);
                var capturedB = blocks.snapshot(program);
                check(capturedA.get("SnapshotSettings").bytes().isReadOnly()
                                && capturedA.get("SnapshotSettings").bytes().remaining() == 16
                                && capturedB.get("SnapshotSettings").bytes().remaining() == 32,
                        "CPU snapshots must preserve immutable values and their original selected sizes");
                blocks.unbind(block); block.free();
                var encoder = device.createCommandEncoder(); input.clear(encoder, new Vector4f(1), 0);
                var bindingsA = foundry.veil.impl.client.render.pipeline.VeilShaderBlockState.prepareSnapshots(encoder, capturedA);
                var bindingsB = foundry.veil.impl.client.render.pipeline.VeilShaderBlockState.prepareSnapshots(encoder, capturedB);
                var sliceA = bindingsA.get("SnapshotSettings"); var sliceB = bindingsB.get("SnapshotSettings");
                check(sliceA.buffer() != sliceB.buffer() || sliceA.offset() != sliceB.offset(), "Serialized draws must use distinct native transient ranges");
                try (var pass = outputA.beginPass(encoder)) { program.bind(pass, bindingsA, program.snapshotTextures()); pass.draw(3, 1, 0, 0); }
                try (var pass = outputB.beginPass(encoder)) { program.bind(pass, bindingsB, program.snapshotTextures()); pass.draw(3, 1, 0, 0); }
                encoder.submit(); assertColor(device, outputA.color(0).texture(), 255, 0, 0, 255); assertColor(device, outputB.color(0).texture(), 0, 0, 255, 255);
                var borrowed = foundry.veil.api.client.render.shader.block.ShaderBlock.wrapper(
                        foundry.veil.api.client.render.shader.block.ShaderBlock.BufferBinding.UNIFORM, external.slice());
                borrowed.setSize(16); blocks.bind("SnapshotSettings", borrowed);
                var capturedBorrow = blocks.snapshot(program); borrowed.setSize(32); blocks.unbind(borrowed); borrowed.free();
                encoder = device.createCommandEncoder();
                var green = java.nio.ByteBuffer.allocateDirect(32).order(ByteOrder.nativeOrder()); new Vector4f(0, 1, 0, 1).get(0, green);
                encoder.writeToBuffer(external.slice(), green);
                var borrowedBindings = foundry.veil.impl.client.render.pipeline.VeilShaderBlockState.prepareSnapshots(encoder, capturedBorrow);
                check(borrowedBindings.get("SnapshotSettings").length() == 16 && !external.isClosed(), "Borrowed capture must retain selected range and external ownership");
                try (var pass = outputA.beginPass(encoder)) { program.bind(pass, borrowedBindings, program.snapshotTextures()); pass.draw(3, 1, 0, 0); }
                encoder.submit(); assertColor(device, outputA.color(0).texture(), 0, 255, 0, 255);
            } finally { blocks.clear(); block.free(); }
            record(checked, "queued serialized ShaderBlock snapshots survive mutable object changes, resize, unbind and free; borrowed ranges retain external ownership and drive GPU pixels");
        }
        if (selected.contains("leases")) {
            var state = RenderPipeline.builder().withLocation(Identifier.parse("veil:gpu_lease_acceptance"))
                    .withVertexShader(Identifier.parse("veil:core/gpu_blit")).withFragmentShader(Identifier.parse("veil:core/gpu_snapshot"))
                    .withPrimitiveTopology(PrimitiveTopology.TRIANGLES).withCull(false).withDepthStencilState(Optional.empty())
                    .withColorTargetState(ColorTargetState.DEFAULT)
                    .withBindGroupLayout(BindGroupLayout.builder().withUniform("SnapshotSettings", UniformType.UNIFORM_BUFFER)
                            .withUniform("InSampler", UniformType.COMBINED_IMAGE_SAMPLER).build()).build();
            try (var source = new GpuShaderSource(client.getResourceManager()); var program = new GpuShaderProgram(device, state, source, Runnable::run);
                 var input = GpuFramebuffer.builder("lease input", 2, 2).color(GpuFormat.RGBA8_UNORM).build(device);
                 var outputA = GpuFramebuffer.builder("lease output A", 2, 2).color(GpuFormat.RGBA8_UNORM).build(device);
                 var outputB = GpuFramebuffer.builder("lease output B", 2, 2).color(GpuFormat.RGBA8_UNORM).build(device);
                 var externalSampler = TextureFilter.CLAMP.createSampler(device)) {
                var block = program.createUniformBlock("SnapshotSettings", GpuUniformBuffer.layoutBuilder().field("Tint", GpuUniformBuffer.ValueType.VEC4).build());
                block.setVector("Tint", 1, 0, 0, 1); program.setTexture("InSampler", input.color(0).view(), TextureFilter.CLAMP);
                try (var leaseA = program.retain()) {
                    var snapshotA = program.snapshotOwnedUniforms(); var texturesA = program.snapshotTextures();
                    var ownedSampler = texturesA.get("InSampler").sampler();
                    block.setVector("Tint", 0, 0, 1, 1); program.setTexture("InSampler", input.color(0).view(), externalSampler);
                    check(!ownedSampler.isClosed(), "Replacing a filter must retain a sampler used by queued draws");
                    try (var leaseB = program.retain()) {
                        var snapshotB = program.snapshotOwnedUniforms(); var texturesB = program.snapshotTextures();
                        program.close(); check(!program.isClosed() && !block.isClosed(), "Program retirement must preserve leased pipelines and blocks");
                        try { program.retain(); throw new AssertionError("Retired program accepted a new captured draw"); }
                        catch (IllegalStateException expected) { }
                        var encoder = device.createCommandEncoder(); input.clear(encoder, new Vector4f(1), 1);
                        var bindingsA = program.prepareUniformSnapshots(encoder, snapshotA); var bindingsB = program.prepareUniformSnapshots(encoder, snapshotB);
                        try (var pass = outputA.beginPass(encoder)) { program.bind(pass, bindingsA, texturesA); pass.draw(3, 1, 0, 0); }
                        try (var pass = outputB.beginPass(encoder)) { program.bind(pass, bindingsB, texturesB); pass.draw(3, 1, 0, 0); }
                        encoder.submit(); assertColor(device, outputA.color(0).texture(), 255, 0, 0, 255); assertColor(device, outputB.color(0).texture(), 0, 0, 255, 255);
                        leaseA.close(); check(!program.isClosed(), "A remaining draw lease must keep the program alive");
                        leaseB.close(); check(program.isClosed() && block.isClosed() && ownedSampler.isClosed()
                                && !externalSampler.isClosed(), "Final release must retire owned resources and preserve borrowed samplers");
                    }
                }
            }
            record(checked, "queued draws survive program retirement and owned-sampler replacement; final lease releases owned resources after real GPU pixels");
        }
        if (selected.contains("fields")) {
            var layout = GpuUniformBuffer.layoutBuilder().field("Flag", GpuUniformBuffer.ValueType.BOOL)
                    .field("Gain", GpuUniformBuffer.ValueType.FLOAT).field("Indices", GpuUniformBuffer.ValueType.IVEC2)
                    .field("Unsigned", GpuUniformBuffer.ValueType.UINT).array("Samples", GpuUniformBuffer.ValueType.FLOAT, 2)
                    .field("Transform", GpuUniformBuffer.ValueType.MAT2X3).field("Tint", GpuUniformBuffer.ValueType.VEC4).build();
            check(layout.size() == 112 && layout.fields().get("Samples").baseOffset() == 32
                    && layout.fields().get("Transform").baseOffset() == 64, "Native field layout must retain std140 arrays and rectangular matrices");
            var state = RenderPipeline.builder().withLocation(Identifier.parse("veil:gpu_fields_acceptance"))
                    .withVertexShader(Identifier.parse("veil:core/gpu_blit")).withFragmentShader(Identifier.parse("veil:core/gpu_fields"))
                    .withPrimitiveTopology(PrimitiveTopology.TRIANGLES).withCull(false).withDepthStencilState(Optional.empty())
                    .withColorTargetState(ColorTargetState.DEFAULT)
                    .withBindGroupLayout(BindGroupLayout.builder().withUniform("FieldSettings", UniformType.UNIFORM_BUFFER).build()).build();
            foundry.veil.api.client.render.shader.uniform.ShaderUniform tintView = null;
            try (var source = new GpuShaderSource(client.getResourceManager());
                 var shader = new GpuShaderProgram(device, state, source, Runnable::run);
                 var target = GpuFramebuffer.builder("native uniform field output", 2, 2).color(GpuFormat.RGBA8_UNORM).build(device)) {
                shader.createUniformBlock("FieldSettings", layout);
                var metadata = new foundry.veil.api.client.render.shader.program.ShaderUniformCache(shader);
                check(metadata.getUniformBlock("FieldSettings").size().orElseThrow() == 112
                        && metadata.getUniform("FieldSettings", "Samples").arrayStride() == 16
                        && metadata.getUniform("FieldSettings", "Transform").type() == GpuUniformBuffer.ValueType.MAT2X3,
                        "Shader metadata must derive actual typed block fields without backend program reflection");
                shader.getUniform("FieldSettings", "Flag").setInt(1);
                shader.getUniform("FieldSettings", "Gain").setDouble(0.5);
                shader.getUniform("FieldSettings", "Indices").setVectorI(1, 2);
                var unsigned = shader.getUniform("FieldSettings", "Unsigned");
                unsigned.setLong(0xFFFFFFFFL);
                check(unsigned.getLong() == 0xFFFFFFFFL && unsigned.getDouble() == 4294967295.0,
                        "Unsigned field getters must preserve all 32 bits");
                unsigned.setLong(255);
                var samples = shader.getUniform("FieldSettings", "Samples"); samples.setFloats(0.25F, 0.75F);
                check(samples.getOffset() == 32 && java.util.Arrays.equals(samples.getFloats(), new float[]{0.25F, 0.75F}),
                        "Field accessors must gather array values across std140 padding");
                var matrix = shader.getUniform("FieldSettings", "Transform");
                matrix.setMatrix2x3(new org.joml.Matrix3x2f().set(new float[]{1, 0, 0, 0, 0.5F, 1}));
                check(matrix.getType() == foundry.veil.api.client.render.shader.uniform.ShaderUniform.Type.MATRIX2x3,
                        "Rectangular native matrix metadata must retain dimensions");
                tintView = shader.getUniform("FieldSettings", "Tint"); tintView.setVector64(0.2, 0.4, 0.6, 1);
                var readTint = new org.joml.Vector4f(); tintView.getVector("Tint", readTint);
                check(Math.abs(readTint.z() - 0.6F) < 0.00001F, "Field getter must expose pending CPU vector values");
                try { tintView.setHandle(12); throw new AssertionError("Integer texture handle was accepted"); }
                catch (UnsupportedOperationException expected) { }
                shader.getUniformSafe("FieldSettings", "AbsentOptional").setFloat(0);
                var encoder = device.createCommandEncoder(); shader.prepare(encoder);
                try (var pass = target.beginPass(encoder)) { shader.bind(pass); pass.draw(3, 1, 0, 0); }
                encoder.submit(); assertColor(device, target.color(0).texture(), 51, 128, 153, 191);
                record(checked, "native ShaderUniform field views, bool/uint/int vectors, padded arrays, rectangular matrices, typed getters and numeric conversion drive GPU pixels");
            }
            check(tintView != null && !tintView.isValid(), "Uniform field view must expire when its owning program closes");
        }
        if (selected.contains("blocks")) {
            record BlockData(org.joml.Vector3f color, float scale, org.joml.Matrix3f transform, org.joml.Vector2f offset, int mode) { }
            var schema = foundry.veil.api.client.render.VeilShaderBufferLayout.<BlockData>builder().name("SerializedSettings")
                    .vec3("BaseColor", BlockData::color).f32("Scale", BlockData::scale)
                    .mat3("Transform", BlockData::transform).vec2("Offset", BlockData::offset).integer("Mode", BlockData::mode).build();
            var serializer = foundry.veil.impl.client.render.LayoutSerializer.createSerializer(schema);
            check(serializer.size() == 80 && serializer.fields().get(1).offset() == 12
                    && serializer.fields().get(2).offset() == 16 && serializer.fields().get(3).offset() == 64,
                    "Serialized block must use explicit std140 vec3/scalar packing and padded matrix columns");
            var state = RenderPipeline.builder().withLocation(Identifier.parse("veil:gpu_serialized_acceptance"))
                    .withVertexShader(Identifier.parse("veil:core/gpu_blit")).withFragmentShader(Identifier.parse("veil:core/gpu_serialized"))
                    .withPrimitiveTopology(PrimitiveTopology.TRIANGLES).withCull(false).withDepthStencilState(Optional.empty())
                    .withColorTargetState(ColorTargetState.DEFAULT)
                    .withBindGroupLayout(BindGroupLayout.builder().withUniform("SerializedSettings", UniformType.UNIFORM_BUFFER).build()).build();
            var blockState = new foundry.veil.impl.client.render.pipeline.VeilShaderBlockState();
            var blockCache = new foundry.veil.impl.client.render.pipeline.VeilShaderBufferCache(() -> device, () -> List.of(schema), blockState);
            var block = blockCache.getBlock(schema);
            try (var source = new GpuShaderSource(client.getResourceManager());
                 var shader = new GpuShaderProgram(device, state, source, Runnable::run);
                 var target = GpuFramebuffer.builder("serialized block output", 2, 2).color(GpuFormat.RGBA8_UNORM).build(device)) {
                block.set(new BlockData(new org.joml.Vector3f(0.2F, 0.4F, 0.6F), 0.5F,
                        new org.joml.Matrix3f().scaling(1, 0.5F, 1), new org.joml.Vector2f(0.1F), 5));
                try { block.slice(); throw new AssertionError("Unprepared serialized block was bindable"); }
                catch (IllegalStateException expected) { }
                blockCache.onShaderCompile(Map.of(state.getLocation(), shader));
                blockCache.bind(schema);
                var encoder = device.createCommandEncoder(); blockState.prepare(encoder, shader); shader.prepare(encoder);
                try (var pass = target.beginPass(encoder)) { shader.bind(pass); pass.draw(3, 1, 0, 0); }
                encoder.submit(); assertColor(device, target.color(0).texture(), 51, 51, 77, 255);
                block.set(null); encoder = device.createCommandEncoder(); blockState.prepare(encoder, shader); shader.prepare(encoder);
                try (var pass = target.beginPass(encoder)) { shader.bind(pass); pass.draw(3, 1, 0, 0); }
                encoder.submit(); assertColor(device, target.color(0).texture(), 0, 0, 0, 0);
                record(checked, "serialized ShaderBlock and deterministic std140 object layout, padded mat3, named native binding and null zero upload drive GPU pixels");
            } finally { blockState.clear(); blockCache.free(); block.free(); }
            var dynamic = foundry.veil.api.client.render.shader.block.ShaderBlock.<Float>dynamic(device,
                    foundry.veil.api.client.render.shader.block.ShaderBlock.BufferBinding.UNIFORM, 16,
                    (value, bytes) -> { if (value < 0) throw new IllegalArgumentException("deliberate serializer failure"); bytes.putFloat(0, value); });
            try {
                dynamic.set(0.5F); var encoder = device.createCommandEncoder(); dynamic.prepare(encoder); encoder.submit();
                var oldBuffer = dynamic.slice().buffer();
                dynamic.setSize(64); dynamic.set(-1F);
                try { dynamic.prepare(device.createCommandEncoder()); throw new AssertionError("Failed serializer was accepted"); }
                catch (IllegalArgumentException expected) { check(!oldBuffer.isClosed(), "Serialization failure destroyed live storage"); }
                dynamic.set(0.75F); encoder = device.createCommandEncoder(); dynamic.prepare(encoder); encoder.submit();
                check(oldBuffer.isClosed() && dynamic.slice().length() == 64, "Dynamic block resize must replace and close prior native storage");
                var wrapped = foundry.veil.api.client.render.shader.block.ShaderBlock.wrapper(
                        foundry.veil.api.client.render.shader.block.ShaderBlock.BufferBinding.UNIFORM, dynamic.slice());
                wrapped.setSize(16); wrapped.prepare(device.createCommandEncoder());
                check(wrapped.slice().length() == 16, "Borrowed block must expose the selected typed range");
                wrapped.free(); check(!dynamic.slice().buffer().isClosed(), "Borrowed block free must preserve owner storage");
                record(checked, "resizable serialized native blocks preserve live storage on failure and borrowed wrappers preserve ownership");
            } finally { dynamic.free(); }
        }
        if (selected.contains("post")) {
            var resources = client.getResourceManager();
            var postId = Identifier.parse("veil:gpu_post");
            var manager = new FramebufferManager(device, 2, 2);
            var main = new TextureTarget("post main owner", 2, 2, GpuFormat.RGBA8_UNORM, GpuFormat.D32_FLOAT);
            var postDefinition = FramebufferDefinition.CODEC.parse(JsonOps.INSTANCE,
                    JsonParser.parseString("{\"depth\":true,\"autoClear\":false}")).getOrThrow();
            try (var shaders = new ShaderManager(device, ShaderManager.PROGRAM_SET, new ShaderPreDefinitions())) {
                reload(shaders, resources);
                manager.setMainFramebuffer(main);
                manager.setDefinition(foundry.veil.api.client.render.framebuffer.VeilFramebuffers.POST, postDefinition);
                var post = new foundry.veil.api.client.render.post.PostProcessingManager(device, resources, shaders, manager,
                        Runnable::run, type -> { throw new AssertionError("Post test requests no dynamic world buffers"); },
                        () -> 0, mask -> check(mask == 0, "Unexpected dynamic buffer request"));
                try {
                    reload(post, resources);
                    var pipeline = post.getPipeline(postId);
                    check(pipeline != null, "JSON post pipeline must be loaded through the normal resource manager");
                    var output = manager.getFramebuffer(foundry.veil.api.client.render.framebuffer.VeilFramebuffers.POST);
                    var encoder = device.createCommandEncoder();
                    manager.getFramebuffer(Identifier.parse("minecraft:main")).clear(encoder, new Vector4f(1, 0.5F, 0.25F, 1), 0.75);
                    output.clear(encoder, new Vector4f(0, 0, 0, 1), 0.375);
                    encoder.submit();
                    post.runPipeline(pipeline, false);
                    assertColor(device, output.color(0).texture(), 0, 128, 64, 255);
                    assertDepth(device, output.depth().texture(), 0.375F);
                    check(post.getPostPipelineContext().getFramebuffer(Identifier.parse("temp:scratch")) == null,
                            "Composite temporary targets must not escape the run");
                    record(checked, "JSON composite post: native mask, typed uniforms, filtered down/up copies, NEVER depth comparison and scoped temporary targets drive GPU pixels");

                    main.resize(4, 4); manager.setMainFramebuffer(main); manager.resizeFramebuffers(4, 4);
                    output = manager.getFramebuffer(foundry.veil.api.client.render.framebuffer.VeilFramebuffers.POST);
                    encoder = device.createCommandEncoder();
                    manager.getFramebuffer(Identifier.parse("minecraft:main")).clear(encoder, new Vector4f(0.2F, 0.4F, 0.6F, 1), 0.75);
                    output.clear(encoder, new Vector4f(0, 0, 0, 1), 0.375);
                    encoder.submit();
                    post.runPipeline(pipeline, true);
                    assertColor(device, main.getColorTexture(), 0, 102, 153, 255);
                    reload(post, resources);
                    check(post.getPipeline(postId) != pipeline, "Post reload must retire the previous composite");
                    encoder = device.createCommandEncoder();
                    manager.getFramebuffer(Identifier.parse("minecraft:main")).clear(encoder, new Vector4f(1, 0.5F, 0.25F, 1), 0.75);
                    output.clear(encoder, new Vector4f(0, 0, 0, 1), 0.375);
                    encoder.submit();
                    post.runPipeline(post.getPipeline(postId), true);
                    assertColor(device, main.getColorTexture(), 0, 128, 64, 255);
                    post.endFrame();
                    record(checked, "post manager resizes temporary targets, resolves actual main target, reloads and recreates owned shader variants");
                } finally { post.free(); }
            } finally { manager.free(); main.destroyBuffers(); }
        }
        if (selected.contains("definitions")) {
            var manager = new FramebufferManager(device, 8, 6);
            var dynamic = FramebufferDefinition.CODEC.parse(JsonOps.INSTANCE, JsonParser.parseString(
                    "{\"width\":\"q.screen_width / 2\",\"height\":\"q.screen_height / 2\",\"depth\":true,\"autoClear\":false}")).getOrThrow();
            var id = Identifier.parse("veil_test:dynamic");
            var manualId = Identifier.parse("veil_test:manual");
            try (var manual = GpuFramebuffer.builder("manual", 2, 2).color(GpuFormat.RGBA8_UNORM).build(device)) {
                manager.setDefinition(id, dynamic);
                var previous = manager.getFramebuffer(id);
                check(previous.width() == 4 && previous.height() == 3, "Molang screen dimensions must be evaluated");
                var unsupported = FramebufferDefinition.CODEC.parse(JsonOps.INSTANCE, JsonParser.parseString("{\"depth\":true,\"levels\":2}")).getOrThrow();
                try { manager.setDefinition(id, unsupported); throw new AssertionError("Unsupported mipmapped attachment was accepted"); }
                catch (UnsupportedOperationException expected) {
                    check(manager.getFramebuffer(id) == previous && !previous.isClosed(), "Failed definition replacement must preserve working target");
                }
                manager.resizeFramebuffers(12, 10);
                check(previous.isClosed() && manager.getFramebuffer(id).width() == 6 && manager.getFramebuffer(id).height() == 5,
                        "Resource manager resize must swap and close old targets");
                manager.setFramebuffer(manualId, manual);
                var managed = manager.getFramebuffer(id);
                manager.free();
                check(managed.isClosed() && !manual.isClosed(), "Manager owns resource definitions but not manual targets");
            } finally { manager.free(); }
            record(checked, "resource definitions, Molang sizing, failed replacement, resize and manual ownership");
            try (var sampler = TextureFilter.CLAMP.createSampler(device)) {
                check(sampler.getAddressModeU() == com.mojang.renderpearl.api.textures.AddressMode.CLAMP_TO_EDGE,
                        "Legacy clamp filter must become a native clamp sampler");
                check(sampler.getMinFilter() == FilterMode.NEAREST && sampler.getMaxLod().orElse(-1) == 0,
                        "Native sampler must preserve nearest filtering and disabled mipmaps");
            }
            var light = FramebufferDefinition.CODEC.parse(JsonOps.INSTANCE, JsonParser.parseString(
                    client.getResourceManager().getResource(Identifier.parse("veil:pinwheel/framebuffers/light.json")).orElseThrow().readAllAsString())).getOrThrow();
            try {
                GpuFramebuffer.builder("unsupported RGB", 4, 4).color(GpuFormat.RGB16_FLOAT).build(device);
                throw new AssertionError("Unpacked RGB render attachment must be rejected before a native crash");
            } catch (IllegalArgumentException expected) { }
            try (var hdr = light.createBuilder(4, 4).build(device)) {
                check(hdr.color(0).texture().getFormat() == GpuFormat.RGBA16_FLOAT, "Veil HDR resource definition must keep its storage format");
            }
            record(checked, "native TextureFilter sampler and ported Veil HDR framebuffer definition");
        }
    }

    private static void drawProgram(GpuDevice device, GpuShaderProgram program, GpuFramebuffer output) {
        var encoder = device.createCommandEncoder(); program.prepare(encoder);
        try (var pass = output.beginPass(encoder)) { program.bind(pass); pass.draw(3, 1, 0, 0); }
        encoder.submit();
    }

    private static foundry.veil.api.client.render.post.stage.CompositePostPipeline depthPipeline(
            foundry.veil.api.client.render.post.stage.DepthFunctionPostStage.DepthFunc comparison, float[] color) {
        return new foundry.veil.api.client.render.post.stage.CompositePostPipeline(new foundry.veil.api.client.render.post.PostPipeline[]{
                new foundry.veil.api.client.render.post.stage.DepthFunctionPostStage(comparison),
                new foundry.veil.api.client.render.post.stage.BlitPostStage(Identifier.parse("veil:gpu_post"),
                        Map.of("TintColor", new foundry.veil.api.client.render.post.uniform.FloatUniform(color)),
                        Identifier.parse("minecraft:main"), foundry.veil.api.client.render.framebuffer.VeilFramebuffers.POST, false)
        }, Map.of(), Map.of(), null, 0);
    }

    private static void reload(net.minecraft.server.packs.resources.PreparableReloadListener listener,
            net.minecraft.server.packs.resources.ResourceManager resources) {
        var state = new net.minecraft.server.packs.resources.PreparableReloadListener.SharedState(resources);
        var barrier = new net.minecraft.server.packs.resources.PreparableReloadListener.PreparationBarrier() {
            @Override public <T> java.util.concurrent.CompletableFuture<T> wait(T value) {
                return java.util.concurrent.CompletableFuture.completedFuture(value);
            }
        };
        listener.reload(state, Runnable::run, barrier, Runnable::run).join();
    }

    private static byte[] read(GpuDevice device, GpuTexture texture) {
        int size = Math.multiplyExact(Math.multiplyExact(texture.getWidth(0), texture.getHeight(0)), texture.getFormat().blockSize());
        try (var buffer = device.createBuffer(() -> "veil-test/readback", GpuBuffer.USAGE_COPY_DST | GpuBuffer.USAGE_MAP_READ, size)) {
            var encoder = device.createCommandEncoder();
            encoder.copyTextureToBuffer(texture, buffer, 0, () -> { }, 0);
            try (var fence = encoder.createFence()) {
                encoder.submit();
                check(fence.awaitCompletion(10_000_000_000L), "GPU readback fence timed out");
                try (var mapped = buffer.map(true, false)) {
                    byte[] bytes = new byte[size];
                    mapped.data().get(bytes);
                    return bytes;
                }
            }
        }
    }

    private static void assertColor(GpuDevice device, GpuTexture texture, int r, int g, int b, int a) {
        byte[] bytes = read(device, texture);
        int[] expected = {r, g, b, a};
        for (int index = 0; index < bytes.length; index++) {
            check(Math.abs((bytes[index] & 255) - expected[index % 4]) <= 1,
                    "GPU color mismatch at byte " + index + ": " + (bytes[index] & 255) + " expected " + expected[index % 4]);
        }
    }

    private static void assertDepth(GpuDevice device, GpuTexture texture, float depth) {
        var bytes = java.nio.ByteBuffer.wrap(read(device, texture)).order(ByteOrder.nativeOrder());
        while (bytes.hasRemaining()) check(Math.abs(bytes.getFloat() - depth) < 0.00001F, "GPU depth mismatch");
    }

    private static void record(List<String> checked, String message) {
        checked.add(message);
        System.out.println("GPU CHECK PASS: " + message);
    }

    private static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
