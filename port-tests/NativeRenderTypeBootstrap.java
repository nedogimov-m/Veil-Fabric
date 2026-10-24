import net.fabricmc.api.ModInitializer;
import foundry.veil.api.client.registry.RenderTypeLayerRegistry;

/** Test-only registration at Fabric's actual unfrozen registry lifecycle. */
public final class NativeRenderTypeBootstrap implements ModInitializer {
    @Override public void onInitialize() { RenderTypeLayerRegistry.bootstrap(); }
}
