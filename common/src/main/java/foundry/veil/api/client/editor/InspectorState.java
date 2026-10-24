package foundry.veil.api.client.editor;

import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.BooleanSupplier;

/** Explicit editor ownership keeps inspector APIs independent of the global renderer graph. */
public final class InspectorState {
    private static final Map<Inspector, Host> HOSTS = new IdentityHashMap<>();
    public static synchronized void attach(Inspector inspector, Object owner, BooleanSupplier visible, Runnable hide) {
        Objects.requireNonNull(inspector); Objects.requireNonNull(owner); Objects.requireNonNull(visible); Objects.requireNonNull(hide);
        Host previous = HOSTS.get(inspector);
        if (previous != null && previous.owner != owner) throw new IllegalStateException("Inspector already belongs to a different editor manager");
        HOSTS.put(inspector, new Host(owner, visible, hide));
    }
    public static synchronized void detach(Inspector inspector, Object owner) {
        Host host = HOSTS.get(inspector); if (host != null && host.owner == owner) HOSTS.remove(inspector);
    }
    static boolean isVisible(Inspector inspector) { Host host; synchronized (InspectorState.class) { host = HOSTS.get(inspector); } return host != null && host.visible.getAsBoolean(); }
    static void hide(Inspector inspector) { Host host; synchronized (InspectorState.class) { host = HOSTS.get(inspector); } if (host != null) host.hide.run(); }
    private record Host(Object owner, BooleanSupplier visible, Runnable hide) { }
    private InspectorState() { }
}
