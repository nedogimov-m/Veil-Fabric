package foundry.veil.api.client.render.ext;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.api.device.GpuDevice;
import java.util.*;

/** Native diagnostics. Resource labels are supplied to the actual device at allocation. */
public final class VeilDebug {
    private static final VeilDebug INSTANCE = new VeilDebug();
    private final Deque<String> groups = new ArrayDeque<>();
    private final Map<RenderPass, Integer> passes = new IdentityHashMap<>();
    public static VeilDebug get() { return INSTANCE; }
    public boolean isEnabled() { GpuDevice device = RenderSystem.tryGetDevice(); return device != null && device.isDebuggingEnabled(); }
    public List<String> messages() { return List.copyOf(RenderSystem.getDevice().getLastDebugMessages()); }
    public void pushDebugGroup(CharSequence label) {
        String name = Objects.requireNonNull(label).toString(); groups.addLast(name);
        for (var entry : passes.entrySet()) { entry.getKey().pushDebugGroup(() -> name); entry.setValue(entry.getValue() + 1); }
    }
    public void popDebugGroup() {
        if (groups.isEmpty()) throw new IllegalStateException("Native debug group stack is empty");
        for (var entry : passes.entrySet()) { entry.getKey().popDebugGroup(); entry.setValue(entry.getValue() - 1); }
        groups.removeLast();
    }
    public void opened(RenderPass pass) {
        if (passes.containsKey(pass)) throw new IllegalStateException("Native debug pass is already open");
        int count = 0;
        for (String group : groups) { pass.pushDebugGroup(() -> group); count++; }
        passes.put(pass, count);
    }
    public void closed(RenderPass pass) {
        Integer count = passes.remove(pass);
        if (count != null) for (int i = 0; i < count; i++) pass.popDebugGroup();
    }
    public void reset() {
        for (var entry : passes.entrySet()) for (int i = 0; i < entry.getValue(); i++) entry.getKey().popDebugGroup();
        passes.replaceAll((pass, count) -> 0); groups.clear();
    }
    public List<String> activeGroups() { return List.copyOf(groups); }
    public int activePasses() { return passes.size(); }
    private VeilDebug() { }
}
