package foundry.veil.api.event;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.renderpearl.api.commands.CommandEncoder;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.client.renderer.state.level.LevelRenderState;
import org.jetbrains.annotations.Nullable;

import java.util.Locale;
import java.util.Objects;

/**
 * Actual 26.3 level phases. Geometry is submitted during COLLECT_SUBMITS; the
 * END_MAIN supports additional explicit GPU passes after vanilla closes its
 * render passes. Intermediate native callbacks can run inside a vanilla pass.
 * A context is valid only during
 * its callback: extracted world/camera state must not be retained across frames.
 */
@FunctionalInterface
public interface VeilRenderLevelStageEvent {
    void onRenderLevelStage(Stage stage, Context context);

    record Context(Stage stage, GameRenderer gameRenderer, LevelRenderer levelRenderer, LevelRenderState levelState,
                   @Nullable PoseStack poseStack, @Nullable SubmitNodeCollector submitNodeCollector) {
        public Context {
            Objects.requireNonNull(stage);
            Objects.requireNonNull(gameRenderer);
            Objects.requireNonNull(levelRenderer);
            Objects.requireNonNull(levelState);
        }
        public CameraRenderState camera() { return this.levelState.cameraRenderState; }
        public RenderTarget mainTarget() { return this.gameRenderer.mainRenderTarget(); }
        public CommandEncoder createCommandEncoder() {
            if (this.stage != Stage.END_MAIN) throw new IllegalStateException("Additional GPU passes require END_MAIN; " + this.stage + " may run inside vanilla render passes");
            return RenderSystem.getDevice().createCommandEncoder();
        }
        public SubmitNodeCollector submissions() {
            if (this.submitNodeCollector == null) throw new IllegalStateException("No geometry collector in this terrain render phase");
            return this.submitNodeCollector;
        }
    }

    /** Native order: feature collection precedes execution of the frame graph. */
    enum Stage {
        COLLECT_SUBMITS, START_MAIN, AFTER_OPAQUE_TERRAIN, AFTER_SOLID_FEATURES,
        AFTER_TRANSLUCENT_FEATURES, BEFORE_TRANSLUCENT_TERRAIN, AFTER_TRANSLUCENT_TERRAIN, END_MAIN;
        public static final Codec<Stage> CODEC = Codec.STRING.comapFlatMap(name -> {
            for (Stage stage : values()) if (stage.getName().equals(name)) return DataResult.success(stage);
            return DataResult.error(() -> "Unknown native level stage: " + name);
        }, Stage::getName);
        public String getName() { return this.name().toLowerCase(Locale.ROOT); }
    }
}
