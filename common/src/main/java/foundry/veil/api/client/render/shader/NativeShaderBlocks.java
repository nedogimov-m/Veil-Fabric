package foundry.veil.api.client.render.shader;

import foundry.veil.api.client.render.VeilShaderBufferLayout;
import net.minecraft.resources.Identifier;

import java.util.*;
import java.util.function.Supplier;

/** Actual registered source-block layouts, detached for each native shader processor recipe. */
public final class NativeShaderBlocks {
    private static Supplier<Map<Identifier, VeilShaderBufferLayout<?>>> runtime = Map::of;
    public static synchronized void bindRuntime(Supplier<Map<Identifier, VeilShaderBufferLayout<?>>> layouts) { runtime = Objects.requireNonNull(layouts); }
    public static synchronized void unbindRuntime() { runtime = Map::of; }
    public static synchronized Map<Identifier, VeilShaderBufferLayout<?>> snapshot() { return Map.copyOf(runtime.get()); }
    public static Set<String> layoutNames() {
        var names = new HashSet<String>(); snapshot().values().forEach(layout -> names.add(layout.name())); return Set.copyOf(names);
    }
    private NativeShaderBlocks() { }
}
