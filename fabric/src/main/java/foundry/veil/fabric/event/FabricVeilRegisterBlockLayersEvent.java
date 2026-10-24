package foundry.veil.fabric.event;

import foundry.veil.api.event.VeilRegisterBlockLayersEvent;
import foundry.veil.api.event.VeilRegisterFixedBuffersEvent;
import net.fabricmc.fabric.api.event.Event;
import net.fabricmc.fabric.api.event.EventFactory;

/**
 * Fired to register extra block geometry on the actual native ordered collector.
 * Terrain materials keep Minecraft's native enum layers and pipeline ownership.
 *
 * @author Ocelot
 * @see VeilRegisterFixedBuffersEvent
 */
@FunctionalInterface
public interface FabricVeilRegisterBlockLayersEvent extends VeilRegisterBlockLayersEvent {

    Event<VeilRegisterBlockLayersEvent> EVENT = EventFactory.createArrayBacked(VeilRegisterBlockLayersEvent.class, events -> registry -> {
        for (VeilRegisterBlockLayersEvent event : events) {
            event.onRegisterBlockLayers(registry);
        }
    });
}
