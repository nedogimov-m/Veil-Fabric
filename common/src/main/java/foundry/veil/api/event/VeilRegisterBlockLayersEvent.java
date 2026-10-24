package foundry.veil.api.event;

import net.minecraft.client.renderer.OrderedSubmitNodeCollector;
import java.util.function.BiConsumer;

/** Registers extra block geometry on the actual native collector. Terrain material pipelines remain native enum layers. */
@FunctionalInterface
public interface VeilRegisterBlockLayersEvent {
    void onRegisterBlockLayers(Registry registry);
    @FunctionalInterface
    interface Registry {
        /** Register once; each world view receives its actual ordered native geometry collector. */
        void registerBlockGeometry(int order, BiConsumer<VeilRenderLevelStageEvent.Context, OrderedSubmitNodeCollector> submission);
    }
}
