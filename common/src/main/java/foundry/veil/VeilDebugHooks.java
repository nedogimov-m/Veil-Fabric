package foundry.veil;

/**
 * <p>Class designed to expose useful debugging functions buried in Minecraft source code.</p>
 * <p>The intent is to allow for an easy target for mixin or placing a breakpoint.</p>
 *
 * @author Ocelot
 */
public final class VeilDebugHooks {

    private VeilDebugHooks() {
    }

    /** Breakpoint hook receiving the actual backend validation callback. */
    public static void onGpuMessage(String backend, int severity, int type, int id, String message, long userParam) {
    }

    public static void onGLError(int source, int type, int id, int severity, String message, long userParam) {
    }
}
