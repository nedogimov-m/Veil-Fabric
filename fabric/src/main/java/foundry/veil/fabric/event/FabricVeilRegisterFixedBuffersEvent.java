package foundry.veil.fabric.event;

import foundry.veil.api.event.VeilRegisterFixedBuffersEvent;
import net.fabricmc.fabric.api.event.Event;
import net.fabricmc.fabric.api.event.EventFactory;

/**
 * Fired to register persistent geometry callbacks on each actual native world view.
 *
 * @author Ocelot
 */
@FunctionalInterface
public interface FabricVeilRegisterFixedBuffersEvent extends VeilRegisterFixedBuffersEvent {

    Event<VeilRegisterFixedBuffersEvent> EVENT = EventFactory.createArrayBacked(VeilRegisterFixedBuffersEvent.class, events -> registry -> {
        for (VeilRegisterFixedBuffersEvent event : events) {
            event.onRegisterFixedBuffers(registry);
        }
    });
}
