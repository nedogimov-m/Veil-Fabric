package foundry.veil.fabric.mixin.compat.sodium;

import it.unimi.dsi.fastutil.longs.Long2ReferenceMap;
import net.caffeinemc.mods.sodium.client.render.chunk.RenderSection;
import net.caffeinemc.mods.sodium.client.render.chunk.storage.QueuedSectionStorage;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(value=QueuedSectionStorage.class,remap=false)
public interface NativeSodiumStorageAccessor {
    @Accessor("sections") Long2ReferenceMap<RenderSection> veil$sections();
    @Accessor("queuedPuts") Long2ReferenceMap<RenderSection> veil$queuedPuts();
}
