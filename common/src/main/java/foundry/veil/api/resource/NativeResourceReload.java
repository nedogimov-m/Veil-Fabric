package foundry.veil.api.resource;

import foundry.veil.api.util.CompositeReloadListener;
import net.minecraft.client.Minecraft;
import net.minecraft.server.packs.resources.PreparableReloadListener;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.util.Util;
import java.util.concurrent.CompletableFuture;

/** Executes a resource editor hot reload as one real native shared-state transaction. */
public final class NativeResourceReload {
    private NativeResourceReload() {}
    public static CompletableFuture<Void> reload(ResourceManager resources, PreparableReloadListener... listeners) {
        final var transaction = CompositeReloadListener.of(listeners);
        final var shared = new PreparableReloadListener.SharedState(resources);
        transaction.prepareSharedState(shared);
        return transaction.reload(shared, Util.backgroundExecutor(), new PreparableReloadListener.PreparationBarrier() {
            @Override public <T> CompletableFuture<T> wait(T value) {
                return CompletableFuture.completedFuture(value);
            }
        }, Minecraft.getInstance());
    }
}
