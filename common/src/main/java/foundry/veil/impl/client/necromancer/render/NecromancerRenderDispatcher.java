package foundry.veil.impl.client.necromancer.render;

import com.mojang.blaze3d.vertex.PoseStack;
import foundry.veil.api.client.necromancer.Skeleton;
import foundry.veil.api.client.necromancer.render.NecromancerRenderer;
import foundry.veil.api.client.necromancer.render.Skin;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.util.LightCoordsUtil;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.joml.Vector4f;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Captures bone deformation at extraction and submits only immutable native geometry. */
public final class NecromancerRenderDispatcher {
    private static final ThreadLocal<Scope> CURRENT = new ThreadLocal<>();
    private NecromancerRenderDispatcher() {}
    public record Draw(RenderType type, Skin.Snapshot geometry) {
        public Draw { Objects.requireNonNull(type); Objects.requireNonNull(geometry); }
        public void submit(SubmitNodeCollector collector, PoseStack pose) { this.geometry.submit(collector, pose, this.type); }
    }
    /** A separate recorder belongs to one native entity extraction, with no global or GPU state. */
    public static Recording recording() { return new Recording(); }
    public static final class Recording extends State {
        private final List<Draw> draws = new ArrayList<>();
        @Override protected void accept(Draw draw) { this.draws.add(draw); }
        public List<Draw> snapshot() { return List.copyOf(this.draws); }
    }
    public static Scope begin(SubmitNodeCollector collector) {
        Scope scope = new Scope(CURRENT.get(), Objects.requireNonNull(collector)); CURRENT.set(scope); return scope;
    }
    public static NecromancerRenderer getRenderer() {
        Scope scope = CURRENT.get();
        if (scope == null || scope.closed) throw new IllegalStateException("Skeletal rendering requires a native COLLECT_SUBMITS scope or an explicit recording");
        return scope.renderer;
    }
    public static void end() { Scope scope = CURRENT.get(); if (scope == null) throw new IllegalStateException("No native skeletal collection scope"); scope.close(); }
    public static void delete() { while (CURRENT.get() != null) CURRENT.get().close(); }
    public static final class Scope implements AutoCloseable {
        private final Scope previous;
        private final State renderer;
        private boolean closed;
        private Scope(Scope previous, SubmitNodeCollector collector) {
            this.previous = previous;
            this.renderer = new State() { @Override protected void accept(Draw draw) {
                if (Scope.this.closed || CURRENT.get() != Scope.this) throw new IllegalStateException("Native skeletal collection scope is no longer selected");
                draw.submit(collector, new PoseStack());
            } };
        }
        @Override public void close() {
            if (this.closed) return;
            if (CURRENT.get() != this) throw new IllegalStateException("Native skeletal collection scopes must close in stack order");
            this.closed = true; if (this.previous == null) CURRENT.remove(); else CURRENT.set(this.previous);
        }
    }
    private static abstract class State implements NecromancerRenderer {
        private int overlay, light;
        private final Vector4f color = new Vector4f(1);
        private final Matrix4f transform = new Matrix4f();
        State() { this.reset(); }
        protected abstract void accept(Draw draw);
        @Override public void setUv1(int u, int v) { this.overlay = u & 65535 | (v & 65535) << 16; }
        @Override public void setUv2(int u, int v) { this.light = u & 65535 | (v & 65535) << 16; }
        @Override public void setColor(float r, float g, float b, float a) { this.color.set(r, g, b, a); }
        @Override public void setColor(int color) { this.color.set((color >>> 16 & 255) / 255f, (color >>> 8 & 255) / 255f, (color & 255) / 255f, (color >>> 24) / 255f); }
        @Override public void setTransform(Matrix4fc transform) { this.transform.set(transform); }
        @Override public void reset() { this.overlay = OverlayTexture.NO_OVERLAY; this.light = LightCoordsUtil.FULL_BRIGHT; this.color.set(1); this.transform.identity(); }
        @Override public void draw(RenderType type, Skeleton skeleton, Skin skin, float partialTicks) {
            this.accept(new Draw(type, skin.snapshot(skeleton, this.transform, this.color, this.overlay, this.light, partialTicks)));
        }
    }
}
