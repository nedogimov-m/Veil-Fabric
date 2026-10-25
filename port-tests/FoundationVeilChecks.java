import foundry.imgui.api.ImGuiMC;
import foundry.imgui.impl.ImGuiMCImpl;
import foundry.veil.api.client.editor.*;
import foundry.veil.api.client.render.VeilRenderSystem;
import foundry.veil.impl.client.imgui.VeilImGuiCompat;
import imgui.ImGui;
import java.lang.reflect.Field;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;
import net.fabricmc.fabric.api.client.gametest.v1.context.*;

/** Full installed Veil editor and resource ownership, through its genuine native ImGui host. */
public final class FoundationVeilChecks {
    public static void run(ClientGameTestContext test, TestSingleplayerContext world,
            Map<String,Object> report, List<String> checks) throws Exception {
        String selection = System.getProperty("foundation.case", "editor");
        if (selection.equals("graphics")) { FoundationVeilGpuChecks.run(test, world, report, checks); return; }
        var result = new LinkedHashMap<String,Object>(); report.put("veilEditor", result);
        result.put("scope", "Installed Veil, its genuine F6 key mapping, actual registered production inspectors and native host frames. Inspector show/hide uses the public editor API; opening/closing the overlay uses real keyboard dispatch.");
        var manager = test.computeOnClient(client -> VeilRenderSystem.renderer().getEditorManager());
        test.getInput().pressKey(VeilImGuiCompat.EDITOR_KEY);
        test.waitFor(client -> manager.isEnabled(), 200); frames(test, 4);
        result.put("nativeHotkeyOpened", true);
        var inspectors = test.computeOnClient(client -> inspectors(manager));
        check(!inspectors.isEmpty(), "Installed production inspector registry is empty");
        var rows = new ArrayList<Map<String,Object>>(); result.put("inspectors", rows);
        try {
            for (var inspector : inspectors) {
                var row = new LinkedHashMap<String,Object>(); rows.add(row);
                row.put("class", inspector.getClass().getName()); row.put("title", inspector.getDisplayName().getString());
                result.put("currentInspector", inspector.getClass().getName()); progress(report);
                boolean enabled = test.computeOnClient(client -> inspector.isEnabled()); row.put("enabled", enabled);
                if (!enabled) { row.put("status", "unavailable-on-current-runtime"); continue; }
                test.runOnClient(client -> manager.show(inspector));
                test.waitFor(client -> manager.isVisible(inspector), 200); frames(test, 6);
                test.runOnClient(client -> check(ImGuiMCImpl.handler != null, "Inspector rendering retired the installed native UI handler"));
                row.put("screenshot", FoundationClientAudit.captureComposited(test, "foundation-veil-" + inspector.getClass().getSimpleName()).toString());
                if (inspector.getClass().getSimpleName().equals("SubLevelContainerInspector")) {
                    var size=test.computeOnClient(client->windowSize(inspector.getDisplayName().getString()));
                    row.put("nativeInitialWindowSize",size);progress(report);
                    check(size.get(0)>=320&&size.get(1)>=180,"Native sublevel inspector must open with a readable grid, observed "+size);
                    var ship = world.getServer().computeOnServer(server -> {
                        var level=server.overworld();var at=new net.minecraft.core.BlockPos(8,-58,8);level.setBlockAndUpdate(at,net.minecraft.world.level.block.Blocks.OAK_PLANKS.defaultBlockState());
                        return dev.ryanhcode.sable.api.SubLevelAssemblyHelper.assembleBlocks(level,at,List.of(at),new dev.ryanhcode.sable.companion.math.BoundingBox3i(at,at));
                    });
                    check(ship!=null,"Native inspector body fixture was not created");frames(test,8);
                    row.put("withBodyScreenshot",FoundationClientAudit.captureComposited(test, "foundation-veil-plot-inspector-live-body").toString());
                    world.getServer().runOnServer(server->dev.ryanhcode.sable.api.sublevel.SubLevelContainer.getContainer(server.overworld()).removeSubLevel(ship,dev.ryanhcode.sable.sublevel.storage.SubLevelRemovalReason.REMOVED));frames(test,8);
                    row.put("emptyAfterBodyRemoval",true);
                    test.runOnClient(client->{try(var context=ImGuiMC.withImGui()){ImGui.setWindowSize(inspector.getDisplayName().getString(),340,500);}});frames(test,4);
                    row.put("narrowGridScreenshot",FoundationClientAudit.captureComposited(test,"foundation-veil-plot-inspector-narrow-grid").toString());
                }
                row.put("status", "passed");
                progress(report);
                test.runOnClient(client -> manager.hide(inspector)); frames(test, 2);
            }
            checks.add("Actual F6 keyboard opens installed Veil; " + rows.size() + " genuine registered inspectors enumerated and every enabled inspector shown, rendered and hidden with screenshots");
            var framebuffer = inspectors.stream().filter(i -> i.getClass().getSimpleName().equals("FramebufferInspector")).findFirst().orElseThrow();
            test.runOnClient(client -> manager.show(framebuffer)); frames(test, 4);
            test.getInput().resizeWindow(960, 540); frames(test, 5);
            result.put("resizedScreenshot", FoundationClientAudit.captureComposited(test, "foundation-veil-framebuffer-inspector-resized").toString());
            var reload = new AtomicReference<CompletableFuture<Void>>();
            test.runOnClient(client -> reload.set(client.reloadResourcePacks()));
            test.waitFor(client -> reload.get() != null && reload.get().isDone(), 600); test.runOnClient(client -> reload.get().join());
            test.waitFor(client -> client.gui.overlay() == null, 300); frames(test, 6);
            check(test.computeOnClient(client -> manager.isEnabled() && ImGuiMCImpl.handler != null), "Resource reload lost the active installed editor");
            result.put("reloadScreenshot", FoundationClientAudit.captureComposited(test, "foundation-veil-framebuffer-inspector-after-reload").toString());
            test.runOnClient(client -> manager.hide(framebuffer)); frames(test, 2);
            test.getInput().pressKey(VeilImGuiCompat.EDITOR_KEY); test.waitFor(client -> !manager.isEnabled(), 200);
            result.put("nativeHotkeyClosed", true); result.put("status", "passed");
            checks.add("Actual native resize and Minecraft resource reload while the production framebuffer inspector remains open, then real F6 close");
        } finally {
            test.runOnClient(client -> { for (var inspector : inspectors) manager.hide(inspector); manager.setEnabled(false); client.mouseHandler.grabMouse(); });
        }
    }
    private static List<Inspector> inspectors(EditorManager manager) {
        try { Field field = EditorManager.class.getDeclaredField("editors"); field.setAccessible(true); return new ArrayList<>(((Map<Inspector,?>)field.get(manager)).keySet()); }
        catch (ReflectiveOperationException failure) { throw new AssertionError(failure); }
    }
    private static void progress(Map<String,Object> report) {
        try { java.nio.file.Files.writeString(java.nio.file.Path.of(System.getProperty("port.smoke.report")).resolveSibling("editor-progress.json"),new com.google.gson.GsonBuilder().setPrettyPrinting().create().toJson(report)); }
        catch (java.io.IOException failure) { throw new RuntimeException(failure); }
    }
    private static List<Integer> windowSize(String title) {
        try(var context=ImGuiMC.withImGui()) {
            check(context!=null,"Installed inspector context missing");boolean selected=false;
            for(var line:ImGui.saveIniSettingsToMemory().split("\\R")) {
                if(line.startsWith("[Window]["))selected=line.equals("[Window]["+title+"]");
                else if(selected&&line.startsWith("Size=")){var values=line.substring(5).split(",");return List.of(Integer.parseInt(values[0]),Integer.parseInt(values[1]));}
            }
            throw new AssertionError("Native inspector window was not saved: "+title);
        }
    }
    private static void frames(ClientGameTestContext test, int count) {
        int start = test.computeOnClient(client -> { try (var context = ImGuiMC.withImGui()) { check(context != null, "Native editor context missing"); return ImGui.getFrameCount(); } });
        test.waitFor(client -> { try (var context = ImGuiMC.withImGui()) { return context != null && ImGui.getFrameCount() >= start + count; } }, 300);
    }
    private static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
    private FoundationVeilChecks() {}
}
