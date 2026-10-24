package foundry.veil.fabric;

import foundry.veil.api.event.VeilRenderLevelStageEvent;
import foundry.veil.fabric.event.FabricVeilRenderLevelStageEvent;
import foundry.veil.impl.client.necromancer.render.NecromancerRenderDispatcher;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelTerrainRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext;
import net.minecraft.client.renderer.OrderedSubmitNodeCollector;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.BiConsumer;

/** Routes each real native phase once; terrain passes are not relabeled as old GL layers. */
public final class FabricRenderTypeStageHandler {
    private static boolean registered;
    private static final List<Submission> SUBMISSIONS = new ArrayList<>();
    private record Submission(int order, BiConsumer<VeilRenderLevelStageEvent.Context, OrderedSubmitNodeCollector> callback) { }
    public static synchronized void registerSubmission(int order, BiConsumer<VeilRenderLevelStageEvent.Context, OrderedSubmitNodeCollector> callback) {
        SUBMISSIONS.add(new Submission(order, Objects.requireNonNull(callback)));
    }
    private FabricRenderTypeStageHandler() { }

    public static void registerEvents() {
        if (registered) return;
        registered = true;
        LevelRenderEvents.START_MAIN.register(context -> fire(VeilRenderLevelStageEvent.Stage.START_MAIN, context));
        LevelRenderEvents.AFTER_OPAQUE_TERRAIN.register(context -> fire(VeilRenderLevelStageEvent.Stage.AFTER_OPAQUE_TERRAIN, context));
        LevelRenderEvents.COLLECT_SUBMITS.register(context -> fire(VeilRenderLevelStageEvent.Stage.COLLECT_SUBMITS, context));
        LevelRenderEvents.AFTER_SOLID_FEATURES.register(context -> fire(VeilRenderLevelStageEvent.Stage.AFTER_SOLID_FEATURES, context));
        LevelRenderEvents.AFTER_TRANSLUCENT_FEATURES.register(context -> fire(VeilRenderLevelStageEvent.Stage.AFTER_TRANSLUCENT_FEATURES, context));
        LevelRenderEvents.BEFORE_TRANSLUCENT_TERRAIN.register(context -> fire(VeilRenderLevelStageEvent.Stage.BEFORE_TRANSLUCENT_TERRAIN, context));
        LevelRenderEvents.AFTER_TRANSLUCENT_TERRAIN.register(context -> fire(VeilRenderLevelStageEvent.Stage.AFTER_TRANSLUCENT_TERRAIN, context));
        LevelRenderEvents.END_MAIN.register(context -> fire(VeilRenderLevelStageEvent.Stage.END_MAIN, context));
    }

    private static void fire(VeilRenderLevelStageEvent.Stage stage, LevelTerrainRenderContext nativeContext) {
        LevelRenderContext geometry = nativeContext instanceof LevelRenderContext context ? context : null;
        var context = new VeilRenderLevelStageEvent.Context(stage, nativeContext.gameRenderer(), nativeContext.levelRenderer(),
                nativeContext.levelState(), geometry != null ? geometry.poseStack() : null,
                stage == VeilRenderLevelStageEvent.Stage.COLLECT_SUBMITS && geometry != null ? geometry.submitNodeCollector() : null);
        if (stage == VeilRenderLevelStageEvent.Stage.COLLECT_SUBMITS && geometry != null) {
            try (var skeletal = NecromancerRenderDispatcher.begin(geometry.submitNodeCollector())) {
                List<Submission> submissions;
                synchronized (FabricRenderTypeStageHandler.class) { submissions = List.copyOf(SUBMISSIONS); }
                for (Submission submission : submissions) submission.callback.accept(context, geometry.submitNodeCollector().order(submission.order));
                FabricVeilRenderLevelStageEvent.EVENT.invoker().onRenderLevelStage(stage, context);
            }
        } else FabricVeilRenderLevelStageEvent.EVENT.invoker().onRenderLevelStage(stage, context);
    }
}
