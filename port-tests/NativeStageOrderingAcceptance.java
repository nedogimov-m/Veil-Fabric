import foundry.veil.api.event.VeilRenderLevelStageEvent;
import foundry.veil.fabric.event.FabricVeilRenderLevelStageEvent;
import java.util.ArrayList;
import java.util.List;

/** Genuine Fabric phased event dispatch; no Minecraft bootstrap or GPU required. */
public final class NativeStageOrderingAcceptance {
    public static void main(String[] args) {
        var calls = new ArrayList<String>();
        var event = FabricVeilRenderLevelStageEvent.EVENT;
        event.register(FabricVeilRenderLevelStageEvent.FINAL_PHASE, (stage, context) -> {
            calls.add("drawPending"); calls.add("quasar"); calls.add("lighting"); calls.add("bloom/post");
        });
        event.register((stage, context) -> calls.add("early mod callback"));
        event.register(FabricVeilRenderLevelStageEvent.FINAL_PHASE, (stage, context) -> calls.add("later final callback"));
        event.register((stage, context) -> calls.add("late mod callback"));
        for (var stage : VeilRenderLevelStageEvent.Stage.values()) {
            calls.clear(); event.invoker().onRenderLevelStage(stage, null);
            if (!calls.equals(List.of("early mod callback", "late mod callback", "drawPending", "quasar", "lighting", "bloom/post", "later final callback"))) {
                throw new AssertionError("Actual native stage event order for " + stage + ": " + calls);
            }
        }
        System.out.println("PASS genuine Fabric final stage callbacks follow all default callbacks regardless of registration order across all eight native stages");
    }
}
