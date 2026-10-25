import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.device.GpuDevice;
import foundry.veil.api.client.render.VeilRenderSystem;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;
import net.fabricmc.fabric.api.client.gametest.v1.context.*;

/** Reuses test-only assertions against actual installed production FBO owners in a live world. */
public final class FoundationVeilGpuChecks {
    public static void run(ClientGameTestContext test, TestSingleplayerContext world,
            Map<String,Object> report, List<String> checks) throws Exception {
        var result = new LinkedHashMap<String,Object>(); report.put("veilGraphics", result);
        result.put("scope", "Genuine complete installed Veil in a native world: typed Minecraft framebuffer wrappers, actual replacement/resize/destroy ownership, transparent clears read from the GPU, live registered framebuffer views and resource reload. Existing test methods supply assertions, no production replacements.");
        test.runOnClient(client -> {
            try {
                for (var name : List.of("wrapperAndMixin", "transparentManagerDelta")) {
                    var method = NativeFramebufferOwnerAcceptance.class.getDeclaredMethod(name, GpuDevice.class); method.setAccessible(true);
                    method.invoke(null, RenderSystem.getDevice());
                }
            } catch (java.lang.reflect.InvocationTargetException failure) { throw new AssertionError("Installed native framebuffer contract failed", failure.getCause()); }
            catch (ReflectiveOperationException failure) { throw new AssertionError(failure); }
            result.put("before", snapshot());
        });
        checks.add("Installed Veil/Minecraft native framebuffer Mixin: borrowed views expire on resize/replacement, caller-owned storage survives wrapper close, external targets survive Minecraft destroy, native storage returns on wrapper removal; exact transparent GPU clear passed");
        test.getInput().resizeWindow(1000, 620); test.waitTicks(6);
        test.runOnClient(client -> result.put("afterResize", snapshot()));
        var reload = new AtomicReference<CompletableFuture<Void>>();
        test.runOnClient(client -> reload.set(client.reloadResourcePacks()));
        test.waitFor(client -> reload.get() != null && reload.get().isDone(), 600); test.runOnClient(client -> reload.get().join());
        test.waitFor(client -> client.gui.overlay() == null, 300); test.waitTicks(6);
        test.runOnClient(client -> result.put("afterReload", snapshot()));
        result.put("screenshot", test.takeScreenshot("foundation-veil-native-framebuffer-world-after-reload").toString());
        result.put("status", "passed"); checks.add("Installed production framebuffer manager exposes live views before/after actual SDL resize and native resource reload with a world open");
    }
    private static Map<String,Object> snapshot() {
        var manager = VeilRenderSystem.renderer().getFramebufferManager(); var values = new TreeMap<String,Object>();
        for (var entry : manager.getFramebuffers().entrySet()) {
            var framebuffer = entry.getValue(); check(!framebuffer.isClosed(), "Live production manager retained a closed framebuffer: " + entry.getKey());
            check(framebuffer.width() > 0 && framebuffer.height() > 0, "Invalid live framebuffer dimensions: " + entry.getKey());
            for (int i = 0; i < framebuffer.colorCount(); i++) check(!framebuffer.color(i).view().isClosed(), "Live framebuffer retained a closed color view: " + entry.getKey());
            if (framebuffer.hasDepth()) check(!framebuffer.depth().view().isClosed(), "Live framebuffer retained a closed depth view: " + entry.getKey());
            values.put(entry.getKey().toString(), List.of(framebuffer.width(), framebuffer.height()));
        }
        check(!values.isEmpty(), "Installed production framebuffer manager is empty"); return values;
    }
    private static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
    private FoundationVeilGpuChecks() {}
}
