package foundry.veil.api.compat;

import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import foundry.veil.platform.VeilPlatform;
import net.minecraft.resources.Identifier;
import org.jetbrains.annotations.Nullable;

import java.util.Map;
import java.util.ServiceLoader;
import java.util.concurrent.CompletableFuture;

/** Typed native Sodium integration. Dynamic attachment masks belong to the native world bridge. */
public interface SodiumCompat {
    @Nullable SodiumCompat INSTANCE = load();
    private static @Nullable SodiumCompat load() {
        boolean installed=ServiceLoader.load(VeilPlatform.class).findFirst().map(platform->platform.isModLoaded("sodium")).orElse(false);
        return installed?ServiceLoader.load(SodiumCompat.class).findFirst().orElse(null):null;
    }
    static boolean isLoaded() { return INSTANCE != null; }
    /** Actual immutable native definitions already created by Sodium; observation never compiles a selection. */
    Map<Identifier, RenderPipeline> getPipelines();
    /** Actual resource reload, including Sodium includes and the native Minecraft pipeline cache. */
    CompletableFuture<Void> recompile();
    void markChunksDirty();
    /** Opaque identities of Sodium's real SortedRenderLists and DeferredTaskList, never fabricated legacy queue maps. */
    @Nullable Object getSortedRenderLists();
    void setSortedRenderLists(@Nullable Object lists);
    @Nullable Object getTaskLists();
    void setTaskList(@Nullable Object tasks);
    /** Restore list references to their original native owner, unless a real level reload replaced that owner. */
    AutoCloseable preserveVisibility();
}
