package foundry.veil.impl.client.render.shader.injection;

import net.minecraft.client.renderer.state.level.CameraRenderState;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;

import java.util.Objects;

/** Updates CPU view blocks before capturing the resources used by that native view. */
public final class NativeShaderViews {
    private static final ThreadLocal<Scope> CURRENT = new ThreadLocal<>();
    private static Handler runtime;

    private NativeShaderViews() {}

    public enum Kind { WORLD, HAND, GUI, SCENE }
    public record View(Kind kind, Matrix4fc projection, Matrix4fc view, double x, double y, double z, float guiScale) {
        public View {
            Objects.requireNonNull(kind);
            projection = new Matrix4f(projection); view = new Matrix4f(view);
        }
    }
    @FunctionalInterface public interface State extends AutoCloseable { @Override void close(); }
    @FunctionalInterface public interface Handler { State enter(View view); }

    public static synchronized void bindRuntime(Handler handler) {
        if (runtime != null && runtime != handler) throw new IllegalStateException("Native shader view runtime already bound");
        runtime = Objects.requireNonNull(handler);
    }
    public static synchronized void unbindRuntime(Handler handler) { if (runtime == handler) runtime = null; }
    /** The actual nested native view, including alternate-world origins and detached local scenes. */
    public static java.util.Optional<View> currentView() {
        Scope scope = CURRENT.get();
        return scope == null ? java.util.Optional.empty() : java.util.Optional.of(scope.view);
    }
    public static Scope beginWorld(CameraRenderState camera) {
        return begin(new View(Kind.WORLD, camera.projectionMatrix, camera.viewRotationMatrix, camera.pos.x, camera.pos.y, camera.pos.z, 0));
    }
    public static Scope beginHand(Matrix4fc projection, CameraRenderState camera) {
        return begin(new View(Kind.HAND, projection, camera.viewRotationMatrix, camera.pos.x, camera.pos.y, camera.pos.z, 0));
    }
    public static Scope beginGui(Matrix4fc projection, float scale) {
        return begin(new View(Kind.GUI, projection, new Matrix4f(), 0, 0, 0, scale));
    }
    /** Detached feature scenes use their explicit view matrices and local origin. */
    public static Scope beginScene(Matrix4fc projection, Matrix4fc view) {
        return begin(new View(Kind.SCENE, projection, view, 0, 0, 0, 0));
    }
    public static Scope begin(View view) {
        Handler handler; synchronized (NativeShaderViews.class) { handler = runtime; }
        State state = handler == null ? () -> {} : Objects.requireNonNull(handler.enter(view));
        NativeShaderInjectionFrames.Scope frame;
        try { frame = NativeShaderInjectionFrames.beginRuntimeFrame(); }
        catch (RuntimeException | Error failure) {
            try { state.close(); } catch (RuntimeException | Error cleanup) { failure.addSuppressed(cleanup); }
            throw failure;
        }
        Scope scope = new Scope(CURRENT.get(), view, state, frame); CURRENT.set(scope); return scope;
    }
    public static final class Scope implements AutoCloseable {
        private final Scope previous;
        private final View view;
        private final State state;
        private final NativeShaderInjectionFrames.Scope frame;
        private boolean closed;
        private Scope(Scope previous, View view, State state, NativeShaderInjectionFrames.Scope frame) {
            this.previous = previous; this.view = view; this.state = state; this.frame = frame;
        }
        @Override public void close() {
            if (this.closed) return;
            if (CURRENT.get() != this) throw new IllegalStateException("Native shader view scopes must close in stack order");
            try { this.frame.close(); }
            finally {
                try { this.state.close(); }
                finally { this.closed = true; if (this.previous == null) CURRENT.remove(); else CURRENT.set(this.previous); }
            }
        }
    }
}
