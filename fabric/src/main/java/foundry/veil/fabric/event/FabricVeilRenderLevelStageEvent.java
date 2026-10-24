package foundry.veil.fabric.event;

import foundry.veil.api.event.VeilRenderLevelStageEvent;
import net.fabricmc.fabric.api.event.Event;
import net.fabricmc.fabric.api.event.EventFactory;
import net.minecraft.resources.Identifier;

/** Native level-render phase event; no legacy immediate BufferSource or GL state. */
@FunctionalInterface
public interface FabricVeilRenderLevelStageEvent extends VeilRenderLevelStageEvent {
    /** Native draw/composite work runs after listeners in the default phase, regardless of registration order. */
    Identifier FINAL_PHASE = Identifier.fromNamespaceAndPath("veil", "final_native_stage");
    Event<VeilRenderLevelStageEvent> EVENT = EventFactory.createWithPhases(VeilRenderLevelStageEvent.class,
            listeners -> (stage, context) -> {
                for (VeilRenderLevelStageEvent listener : listeners) listener.onRenderLevelStage(stage, context);
            }, Event.DEFAULT_PHASE, FINAL_PHASE);
}
