import com.mojang.blaze3d.pipeline.PipelineCache;
import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.device.GpuDevice;
import com.mojang.renderpearl.api.pipeline.*;
import foundry.veil.api.client.render.gpu.*;
import foundry.veil.api.client.render.shader.NativePipelineBridge;
import foundry.veil.api.client.render.shader.ShaderPreDefinitions;
import foundry.veil.api.client.render.shader.processor.ShaderPreProcessor;
import foundry.veil.api.client.render.texture.TextureFilter;
import io.github.ocelot.glslprocessor.api.GlslInjectionPoint;
import io.github.ocelot.glslprocessor.api.GlslParser;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.Identifier;
import org.joml.Vector4f;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/** Test-only initialization precedes actual vanilla ShaderManager eager pipeline compilation. */
public final class GpuWorldBridgeAcceptance {
    private static final Set<String> WORLD = ConcurrentHashMap.newKeySet();
    private static final AtomicReference<NativePipelineBridge.Bindings> VALUES = new AtomicReference<>();
    private static final AtomicInteger BINDS = new AtomicInteger();
    public static void bootstrap() {
        NativePipelineBridge.bindRuntime(new ShaderPreDefinitions());
        Set<String> paths = new HashSet<>(List.of("translucent_terrain", "translucent_terrain_multidraw", "translucent_block"));
        for (String phase : List.of("depth_bounds", "transmittance", "accumulate")) for (String mesh : List.of("terrain", "terrain_multidraw", "translucent_block")) paths.add("oit_" + phase + "_" + mesh);
        NativePipelineBridge.register(Identifier.parse("veil:actual_world_probe"), pipeline -> pipeline.getLocation().getNamespace().equals("minecraft")
                        && paths.contains(pipeline.getLocation().getPath().replace("pipeline/", "")), Map.of(),
                () -> (ctx, tree) -> {
                    check(ctx instanceof ShaderPreProcessor.MinecraftContext && !(ctx instanceof ShaderPreProcessor.VeilContext), "World source requires the actual Minecraft context kind");
                    var nativeContext = (ShaderPreProcessor.MinecraftContext) ctx;
                    if (!ctx.isSourceFile()) return;
                    check(nativeContext.shaderInstance().equals(nativeContext.pipeline().getLocation().toString()), "Pipeline identity must be supplied without legacy shader-name guessing");
                    WORLD.add(nativeContext.pipeline().getLocation() + "/" + ctx.stage());
                    tree.getBody().addAll(GlslInjectionPoint.BEFORE_MAIN, GlslParser.parse("float veilWorldProbe() { return 1.0; }").getBody());
                }, pipeline -> new NativePipelineBridge.Bindings(Map.of(), Map.of()));
        NativePipelineBridge.register(Identifier.parse("veil:functional_world_extension"), pipeline -> pipeline.getLocation().equals(Identifier.parse("veil:gpu_world_extension")),
                Map.of("WorldSettings", UniformType.UNIFORM_BUFFER, "WorldInput", UniformType.COMBINED_IMAGE_SAMPLER),
                () -> (ctx, tree) -> {
                    check(ctx instanceof ShaderPreProcessor.MinecraftContext, "Functional extension requires actual native world context");
                    if (ctx.isFragment() && ctx.isSourceFile()) tree.getBody().addAll(GlslInjectionPoint.BEFORE_MAIN, GlslParser.parse("layout(std140) uniform WorldSettings { vec4 WorldTint; }; uniform sampler2D WorldInput; vec4 worldBridgeTint() { return texture(WorldInput, texCoord) * WorldTint; }").getBody());
                }, pipeline -> { BINDS.incrementAndGet(); return VALUES.get(); });
    }
    public static void run(Minecraft client, GpuDevice device, List<String> checks) {
        check(WORLD.stream().anyMatch(value -> value.contains("translucent_terrain") && value.endsWith("FRAGMENT")), "Actual vanilla ShaderManager eager world compilation did not execute the bridge");
        check(WORLD.stream().anyMatch(value -> value.contains("oit_")), "Actual OIT pipeline variants did not execute the bridge");
        var baseLayout = GpuUniformBuffer.layoutBuilder().field("BaseGain", GpuUniformBuffer.ValueType.FLOAT).build();
        var tintLayout = GpuUniformBuffer.layoutBuilder().field("WorldTint", GpuUniformBuffer.ValueType.VEC4).build();
        var original = RenderPipeline.builder().withLocation(Identifier.parse("veil:gpu_world_extension"))
                .withVertexShader(Identifier.parse("veil:core/gpu_blit")).withFragmentShader(Identifier.parse("veil:core/gpu_world_extension"))
                .withShaderDefine("BRIDGE_FLAG").withPrimitiveTopology(PrimitiveTopology.TRIANGLES).withCull(false).withDepthStencilState(Optional.empty())
                .withColorTargetState(ColorTargetState.DEFAULT)
                .withBindGroupLayout(BindGroupLayout.builder().withUniform("BaseSettings", UniformType.UNIFORM_BUFFER).build()).build();
        try (var source = new GpuShaderSource(client.getResourceManager()); var cache = new PipelineCache(device, source);
             var base = new GpuUniformBuffer(device, "bridge original UBO", baseLayout);
             var tintA = new GpuUniformBuffer(device, "bridge tint A", tintLayout);
             var tintB = new GpuUniformBuffer(device, "bridge tint B", tintLayout);
             var inputA = GpuFramebuffer.builder("bridge input A", 2, 2).color(GpuFormat.RGBA8_UNORM).build(device);
             var inputB = GpuFramebuffer.builder("bridge input B", 2, 2).color(GpuFormat.RGBA8_UNORM).build(device);
             var outputA = GpuFramebuffer.builder("bridge output A", 2, 2).color(GpuFormat.RGBA8_UNORM).build(device);
             var outputB = GpuFramebuffer.builder("bridge output B", 2, 2).color(GpuFormat.RGBA8_UNORM).build(device);
             var sampler = TextureFilter.CLAMP.createSampler(device)) {
            var compiled = cache.get(original); var actual = NativePipelineBridge.actualDefinition(compiled).orElseThrow();
            check(actual != original && actual.getShaders().equals(original.getShaders()) && actual.getShaderDefines().equals(original.getShaderDefines())
                            && actual.getColorTargetStates().equals(original.getColorTargetStates()) && actual.getPrimitiveTopology() == original.getPrimitiveTopology()
                            && actual.getVertexFormatBindings().equals(original.getVertexFormatBindings()) && actual.pushConstantSize() == original.pushConstantSize(),
                    "Native immutable pipeline augmentation must preserve all caller shader/state/layout data");
            var declarations = BindGroupLayout.flattenUniforms(actual.getBindGroupLayouts());
            check(declarations.size() == 3 && declarations.stream().anyMatch(uniform -> uniform.name().equals("BaseSettings")), "Native extension must append bindings without losing caller UBOs");
            check(cache.get(original) == compiled, "Native cache must retain the actual extended compiled owner");
            base.setFloat("BaseGain", .5f); tintA.setVector("WorldTint", 1, 0, 0, 1); tintB.setVector("WorldTint", 0, 0, 1, 1);
            var encoder = device.createCommandEncoder(); base.upload(encoder); tintA.upload(encoder); tintB.upload(encoder);
            inputA.clear(encoder, new Vector4f(.5f, 1, 1, 1), 0); inputB.clear(encoder, new Vector4f(1, 1, .5f, 1), 0);
            VALUES.set(new NativePipelineBridge.Bindings(Map.of("WorldSettings", tintA.buffer().slice()), Map.of("WorldInput", new NativePipelineBridge.Texture(inputA.color(0).view(), sampler))));
            try (var pass = outputA.beginPass(encoder)) { pass.setPipeline(compiled); pass.setUniform("BaseSettings", base.buffer().slice()); pass.draw(3, 1, 0, 0); }
            VALUES.set(new NativePipelineBridge.Bindings(Map.of("WorldSettings", tintB.buffer().slice()), Map.of("WorldInput", new NativePipelineBridge.Texture(inputB.color(0).view(), sampler))));
            try (var pass = outputB.beginPass(encoder)) { pass.setPipeline(compiled); pass.setUniform("BaseSettings", base.buffer().slice()); pass.draw(3, 1, 0, 0); }
            encoder.submit(); assertColor(device, outputA, 64, 0, 0, 128); assertColor(device, outputB, 0, 0, 64, 128);
            check(BINDS.get() >= 2, "Actual native setPipeline hook did not capture independent per-pass resources");
            cache.clear(); check(compiled.isClosed() && !base.buffer().isClosed() && !sampler.isClosed(), "Native cache retirement must close only compiled ownership, retaining borrowed resources");
        }
        checks.add("actual eager vanilla/OIT shader compilation and lazy native cache extension preserve state/layouts; real native pass hooks bind independent UBO/texture snapshots and exact pixels; normal cache ownership preserves borrowed resources");
    }
    private static void assertColor(GpuDevice device, GpuFramebuffer output, int... expected) {
        try (var buffer = device.createBuffer(() -> "world bridge readback", GpuBuffer.USAGE_COPY_DST | GpuBuffer.USAGE_MAP_READ, 16)) {
            var encoder = device.createCommandEncoder(); encoder.copyTextureToBuffer(output.color(0).texture(), buffer, 0, () -> {}, 0);
            try (var fence = encoder.createFence()) {
                encoder.submit(); check(fence.awaitCompletion(10_000_000_000L), "World bridge GPU fence timed out");
                try (var mapped = buffer.map(true, false)) {
                    for (int index = 0; index < 16; index++) check(Math.abs((mapped.data().get(index) & 255) - expected[index % 4]) <= 1,
                            "World bridge pixel component " + index + " was " + (mapped.data().get(index) & 255) + ", expected " + expected[index % 4]);
                }
            }
        }
    }
    private static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
