import foundry.veil.api.client.registry.PostPipelineStageRegistry;
import net.fabricmc.api.ModInitializer;

/** Test-only bootstrap at the real Fabric registry registration phase. */
public final class GpuPostBootstrap implements ModInitializer {
    @Override public void onInitialize() {
        var selected = java.util.Set.of(System.getProperty("veil.gpu.acceptance.selection", "").split(","));
        if (selected.contains("bridge")) GpuWorldBridgeAcceptance.bootstrap();
        if (selected.contains("dynamicvariants") || selected.contains("dynamiccache")) GpuDynamicVariantAcceptance.bootstrap();
        if (selected.contains("post") || selected.contains("depth")) {
            PostPipelineStageRegistry.bootstrap();
        }
    }
}
