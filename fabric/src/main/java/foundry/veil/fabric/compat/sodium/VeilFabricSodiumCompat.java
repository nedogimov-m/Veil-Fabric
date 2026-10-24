package foundry.veil.fabric.compat.sodium;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import foundry.veil.api.compat.SodiumCompat;
import foundry.veil.fabric.mixin.compat.sodium.*;
import it.unimi.dsi.fastutil.longs.LongLists;
import net.caffeinemc.mods.sodium.client.render.SodiumWorldRenderer;
import net.caffeinemc.mods.sodium.client.render.chunk.RenderSection;
import net.caffeinemc.mods.sodium.client.render.chunk.RenderSectionManager;
import net.caffeinemc.mods.sodium.client.render.chunk.lists.SortedRenderLists;
import net.caffeinemc.mods.sodium.client.render.chunk.lists.DeferredTaskList;
import net.caffeinemc.mods.sodium.client.render.chunk.storage.QueuedSectionStorage;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.Identifier;
import org.jetbrains.annotations.Nullable;
import java.util.*;
import java.util.concurrent.CompletableFuture;

/** Genuine Sodium0.9.2 pipeline inventory, resource reload and resident terrain-list ownership. */
public final class VeilFabricSodiumCompat implements SodiumCompat {
    private static @Nullable RenderSectionManager manager() {
        SodiumWorldRenderer renderer=SodiumWorldRenderer.instanceNullable();
        return renderer==null?null:((SodiumWorldRendererAccessor)renderer).getRenderSectionManager();
    }
    @Override public Map<Identifier,RenderPipeline> getPipelines(){
        RenderSystem.assertOnRenderThread();Map<Identifier,RenderPipeline> result=new TreeMap<>();
        NativeSodiumPipelinesAccessor.veil$programs().values().forEach(pipeline->result.put(pipeline.getLocation(),pipeline));
        NativeSodiumPipelinesAccessor.veil$oitPrograms().values().forEach(set->{
            for(var pipeline:List.of(set.depthBoundsPipeline(),set.transmittancePipeline(),set.accumulatePipeline()))result.put(pipeline.getLocation(),pipeline);
        });
        return Collections.unmodifiableMap(result);
    }
    @Override public CompletableFuture<Void> recompile(){RenderSystem.assertOnRenderThread();return Minecraft.getInstance().reloadResourcePacks();}
    @Override public void markChunksDirty(){
        RenderSystem.assertOnRenderThread();var manager=manager();if(manager==null)return;
        var storage=((RenderSectionManagerAccessor)manager).veil$sections();
        if(!(storage instanceof QueuedSectionStorage))throw new IllegalStateException("Unknown native Sodium resident section storage: "+storage.getClass());
        var access=(NativeSodiumStorageAccessor)storage;
        Set<RenderSection> sections=Collections.newSetFromMap(new IdentityHashMap<>());sections.addAll(access.veil$sections().values());sections.addAll(access.veil$queuedPuts().values());
        for(var section:sections)if(!section.isDisposed())manager.scheduleRebuild(section.getChunkX(),section.getChunkY(),section.getChunkZ(),true);
    }
    @Override public @Nullable Object getSortedRenderLists(){var manager=manager();return manager==null?null:((RenderSectionManagerAccessor)manager).veil$renderLists();}
    @Override public void setSortedRenderLists(@Nullable Object lists){
        RenderSystem.assertOnRenderThread();var manager=manager();if(manager!=null)((RenderSectionManagerAccessor)manager).veil$renderLists(lists==null?SortedRenderLists.empty():(SortedRenderLists)lists);
    }
    @Override public @Nullable Object getTaskLists(){var manager=manager();return manager==null?null:((RenderSectionManagerAccessor)manager).veil$taskLists();}
    @Override public void setTaskList(@Nullable Object tasks){
        RenderSystem.assertOnRenderThread();var manager=manager();if(manager!=null)((RenderSectionManagerAccessor)manager).veil$taskLists(tasks==null?DeferredTaskList.createHeapCopyOf(LongLists.emptyList(),0,0):(DeferredTaskList)tasks);
    }
    @Override public AutoCloseable preserveVisibility(){
        RenderSystem.assertOnRenderThread();var owner=manager();if(owner==null)return ()->{};
        var access=(RenderSectionManagerAccessor)owner;var lists=access.veil$renderLists();var tasks=access.veil$taskLists();
        return new AutoCloseable(){private boolean closed;@Override public void close(){
            RenderSystem.assertOnRenderThread();if(closed)return;closed=true;
            if(manager()==owner){access.veil$renderLists(lists);access.veil$taskLists(tasks);}
        }};
    }
}
