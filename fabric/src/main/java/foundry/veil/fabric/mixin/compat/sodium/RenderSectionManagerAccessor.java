package foundry.veil.fabric.mixin.compat.sodium;

import net.caffeinemc.mods.sodium.client.render.chunk.RenderSectionManager;
import net.caffeinemc.mods.sodium.client.render.chunk.storage.SectionStorage;
import net.caffeinemc.mods.sodium.client.render.chunk.lists.SortedRenderLists;
import net.caffeinemc.mods.sodium.client.render.chunk.lists.DeferredTaskList;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(value=RenderSectionManager.class,remap=false)
public interface RenderSectionManagerAccessor {
    @Accessor("renderSections") SectionStorage veil$sections();
    @Accessor("renderLists") SortedRenderLists veil$renderLists();
    @Accessor("renderLists") void veil$renderLists(SortedRenderLists value);
    @Accessor("taskLists") DeferredTaskList veil$taskLists();
    @Accessor("taskLists") void veil$taskLists(DeferredTaskList value);
}
