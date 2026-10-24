package foundry.veil.fabric.util;

import net.fabricmc.fabric.api.resource.IdentifiableResourceReloadListener;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.PreparableReloadListener;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

public record FabricReloadListener(Identifier id,
                                   PreparableReloadListener listener) implements IdentifiableResourceReloadListener {

    @Override
    public Identifier getFabricId() {
        return this.id;
    }

    @Override
    public void prepareSharedState(SharedState state) {
        this.listener.prepareSharedState(state);
    }

    @Override
    public CompletableFuture<Void> reload(SharedState state, Executor backgroundExecutor, PreparationBarrier preparationBarrier, Executor gameExecutor) {
        return this.listener.reload(state, backgroundExecutor, preparationBarrier, gameExecutor);
    }

    @Override
    public int hashCode() {
        return this.listener.hashCode();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || this.getClass() != o.getClass()) {
            return false;
        }

        FabricReloadListener that = (FabricReloadListener) o;

        if (!this.id.equals(that.id)) {
            return false;
        }
        return this.listener.equals(that.listener);
    }

    @Override
    public String toString() {
        return this.listener.toString();
    }

    @Override
    public String getName() {
        return this.listener.getName();
    }
}
