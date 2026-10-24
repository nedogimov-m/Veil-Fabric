import com.google.gson.GsonBuilder;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.api.device.GpuDevice;
import com.mojang.renderpearl.api.pipeline.*;
import foundry.veil.api.client.render.gpu.GpuFramebuffer;
import foundry.veil.api.client.render.shader.compiler.CompiledShader;
import foundry.veil.api.client.render.shader.compiler.ShaderCompiler;
import foundry.veil.impl.client.render.shader.compiler.CachedShaderCompiler;
import foundry.veil.impl.client.render.shader.program.DynamicShaderProgramImpl;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.resources.Identifier;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/** Test-only entrypoint for newly migrated full native pipeline compiler and dynamic program ownership. */
public final class GpuCompilerAcceptance implements ClientModInitializer {
    private int ticks;
    private boolean ran;
    @Override public void onInitializeClient() {
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (this.ran || ++this.ticks < 5 || !client.isGameLoadFinished()) return;
            this.ran = true;
            var report = new LinkedHashMap<String, Object>();
            try {
                var device = RenderSystem.getDevice();
                report.put("backend", device.getDeviceInfo().backendName()); report.put("device", device.getDeviceInfo().name());
                check(device.getDeviceInfo().backendName().toLowerCase(Locale.ROOT).contains("vulkan"), "Actual Vulkan required");
                var checks = new ArrayList<String>(); report.put("checks", checks);
                run(device, checks); report.put("status", "passed"); report.put("cases", checks.size());
            } catch (Throwable failure) {
                failure.printStackTrace(); report.put("status", "failed"); report.put("error", failure.toString());
            } finally {
                try { Files.writeString(Path.of(System.getProperty("veil.compiler.report")), new GsonBuilder().setPrettyPrinting().create().toJson(report) + "\n"); }
                catch (Exception failure) { throw new RuntimeException(failure); }
                client.stop();
            }
        });
    }
    private static void run(GpuDevice device, List<String> checks) throws Exception {
        var pipeline = RenderPipeline.builder().withLocation(Identifier.parse("veil:compiler_acceptance"))
                .withVertexShader(Identifier.parse("veil:compiler_vertex")).withFragmentShader(Identifier.parse("veil:compiler_fragment"))
                .withPrimitiveTopology(PrimitiveTopology.TRIANGLES).withCull(false).withDepthStencilState(Optional.empty())
                .withColorTargetState(ColorTargetState.DEFAULT).build();
        try (var output = GpuFramebuffer.builder("native compiler output", 2, 2).color(GpuFormat.RGBA8_UNORM).build(device);
             var red = new Sources("vec4(1,0,0,1)"); var blue = new Sources("vec4(0,0,1,1)")) {
            try (var direct = ShaderCompiler.direct(device); var compiled = direct.compile(pipeline, red, Runnable::run)) {
                draw(device, output, compiled::bind, 255, 0, 0, 255);
                check(red.stages.containsAll(Set.of(ShaderType.VERTEX, ShaderType.FRAGMENT)) && !red.closed, "Direct compiler must consume all real stages while borrowing caller source");
            }
            checks.add("direct full native pipeline compiles both real stages and draws exact RGBA");
            var cache = new CachedShaderCompiler(device, null);
            var first = cache.compile(pipeline, red, Runnable::run); int firstRequests = red.requests;
            var second = cache.compile(pipeline, red, Runnable::run);
            check(first != second && red.requests == firstRequests, "Cache must return distinct leases without recompiling exact source owners");
            var different = cache.compile(pipeline, blue, Runnable::run);
            draw(device, output, different::bind, 0, 0, 255, 255);
            cache.close(); first.close(); first.close();
            draw(device, output, second::bind, 255, 0, 0, 255);
            second.close(); different.close();
            boolean rejected = false; try { second.definition(); } catch (IllegalStateException expected) { rejected = true; }
            check(rejected && !red.closed && !blue.closed, "Compiler/cache ownership must not close caller sources or a sibling compiled lease");
            checks.add("exact source-owner cache avoids colliding hashes, source differences and double-close errors; live leases outlast compiler");
            var created = new AtomicInteger(); var sourceCloses = new AtomicInteger();
            try (var providerCache = new CachedShaderCompiler(device, state -> { created.incrementAndGet(); return new Sources("vec4(0,1,0,1)", sourceCloses); })) {
                try (var supplied = providerCache.compile(pipeline, Runnable::run); var again = providerCache.compile(pipeline, Runnable::run)) {
                    check(created.get() == 1 && sourceCloses.get() == 1, "Native provider must transfer and close one source after compilation");
                    providerCache.clear(); draw(device, output, again::bind, 0, 255, 0, 255);
                }
            }
            checks.add("provider source closes after real compilation; resource cache clear preserves caller pipeline leases");
            var callbacks = new AtomicInteger();
            try (var dynamic = new DynamicShaderProgramImpl(device, pipeline, red, Runnable::run, callbacks::incrementAndGet);
                 var invalid = new Sources("INVALID_NATIVE_SHADER")) {
                boolean failed = false; try { dynamic.reload(invalid, Runnable::run); } catch (RuntimeException expected) { failed = true; }
                check(failed, "Deliberate actual native compiler failure must surface");
                draw(device, output, dynamic::bind, 255, 0, 0, 255);
                dynamic.reload(blue, Runnable::run); draw(device, output, dynamic::bind, 0, 0, 255, 255);
                try (var lease = dynamic.retain()) {
                    dynamic.close(); dynamic.close();
                    check(callbacks.get() == 1, "Dynamic retirement callback must run exactly once");
                    draw(device, output, lease.program()::bind, 0, 0, 255, 255);
                }
                boolean closed = false; try { dynamic.bind(null); } catch (IllegalStateException expected) { closed = true; }
                check(closed, "Actual GPU program disposes after its final captured draw lease");
            }
            checks.add("dynamic program preserves old pixels on compiler failure, installs successful reload, and defers GPU disposal through retained draws");
        }
    }
    private static void draw(GpuDevice device, GpuFramebuffer output, Consumer<RenderPass> binding, int... expected) {
        var encoder = device.createCommandEncoder();
        try (var pass = output.beginPass(encoder)) { binding.accept(pass); pass.draw(3, 1, 0, 0); }
        encoder.submit();
        try (var buffer = device.createBuffer(() -> "compiler pixels", GpuBuffer.USAGE_COPY_DST | GpuBuffer.USAGE_MAP_READ, 16)) {
            var readback = device.createCommandEncoder(); readback.copyTextureToBuffer(output.color(0).texture(), buffer, 0, () -> {}, 0);
            try (var fence = readback.createFence()) {
                readback.submit(); check(fence.awaitCompletion(10_000_000_000L), "Compiler GPU readback timed out");
                try (var mapped = buffer.map(true, false)) {
                    for (int i = 0; i < 16; i++) check(Math.abs((mapped.data().get(i) & 255) - expected[i % 4]) <= 1, "Wrong native compiler pixel at " + i);
                }
            }
        }
    }
    private static final class Sources implements ShaderSource {
        private final String color;
        private final AtomicInteger closes;
        final Set<ShaderType> stages = new HashSet<>();
        int requests;
        boolean closed;
        Sources(String color) { this(color, new AtomicInteger()); }
        Sources(String color, AtomicInteger closes) { this.color = color; this.closes = closes; }
        @Override public String getShader(Identifier id, ShaderType stage) {
            check(!this.closed, "Compiler used a closed source owner"); this.requests++; this.stages.add(stage);
            return "#version 450\n" + (stage == ShaderType.VERTEX
                    ? "void main(){vec2 uv=vec2((gl_VertexIndex<<1)&2,gl_VertexIndex&2);gl_Position=vec4(uv*2-1,0,1);}" 
                    : "layout(location=0) out vec4 fragColor;void main(){fragColor=" + this.color + ";}");
        }
        @Override public CachedIncludeSource getInclude(Identifier id) { throw new AssertionError("Unexpected include: " + id); }
        @Override public int hashCode() { return 7; }
        @Override public void close() { if (!this.closed) { this.closed = true; this.closes.incrementAndGet(); } }
    }
    private static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
