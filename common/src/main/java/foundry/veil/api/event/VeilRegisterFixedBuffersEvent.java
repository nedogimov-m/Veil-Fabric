package foundry.veil.api.event;

import net.minecraft.client.renderer.OrderedSubmitNodeCollector;
import java.util.function.BiConsumer;

/**
 * Registers persistent geometry submission callbacks. Each callback receives the actual native
 * ordered collector during COLLECT_SUBMITS; native feature phases own staging and execution.
 *
 * @author Ocelot
 */
@FunctionalInterface
public interface VeilRegisterFixedBuffersEvent {

    /**
     * Registers persistent callbacks for native world geometry collection.
     *
     * @param registry the registry for callbacks and their native feature order
     */
    void onRegisterFixedBuffers(Registry registry);

    /**
     * Registers additional native geometry submissions.
     *
     * @author Ocelot
     */
    @FunctionalInterface
    interface Registry {

        /** Register once; the callback submits immutable native geometry for each actual world view. */
        void registerSubmission(int order, BiConsumer<VeilRenderLevelStageEvent.Context, OrderedSubmitNodeCollector> submission);
    }
}
