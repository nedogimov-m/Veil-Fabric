package foundry.veil.impl.client.editor;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import foundry.imgui.api.ImGuiMC;
import foundry.veil.Veil;
import foundry.veil.api.client.editor.SingleWindowInspector;
import foundry.veil.api.client.render.VeilRenderSystem;
import foundry.veil.api.client.render.gpu.GpuFramebuffer;
import foundry.veil.api.client.util.NativeTexturePreview;
import foundry.veil.api.client.util.TextureDownloader;
import imgui.ImGui;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.CompletableFuture;

/** Typed native attachment previews and PNG capture; attachments always remain caller-owned. */
public final class FramebufferInspector extends SingleWindowInspector {
    public static final Component TITLE=Component.translatable("inspector.veil.framebuffer.title");
    private static final Component SAVE=Component.translatable("gui.veil.save");
    private final Map<GpuFramebuffer,List<NativeTexturePreview>> previews=new IdentityHashMap<>();
    private CompletableFuture<?> saving;
    @Override public Component getDisplayName(){return TITLE;}
    @Override public Component getGroup(){return RENDERER_GROUP;}
    @Override protected void renderComponents(){
        final var targets=VeilRenderSystem.renderer().getFramebufferManager().getFramebuffers();
        previews.entrySet().removeIf(entry->{if(targets.containsValue(entry.getKey())&&!entry.getKey().isClosed())return false;retire(entry.getValue());return true;});
        if(!ImGui.beginTabBar("##native_framebuffers"))return;
        for(var id:new TreeSet<>(targets.keySet())){
            final var target=targets.get(id);if(target==null||target.isClosed()||!ImGui.beginTabItem(id.toString()))continue;
            final int count=target.colorCount()+(target.hasDepth()?1:0),columns=Math.max(1,(int)Math.ceil(Math.sqrt(count)));
            final float width=ImGui.getContentRegionAvailX()/columns-ImGui.getStyle().getItemSpacingX();
            final var images=previews.computeIfAbsent(target,ignored->new ArrayList<>());
            while(images.size()<count)images.add(new NativeTexturePreview(RenderSystem.getDevice()));
            for(int index=0;index<count;index++){
                if(index%columns!=0)ImGui.sameLine();ImGui.beginGroup();
                final boolean depth=index==target.colorCount();final var attachment=depth?target.depth().view():target.color(index).view();
                ImGui.text((depth?"Depth":"Color "+index)+" ("+attachment.texture().getFormat()+")");
                final var image=images.get(index).render(attachment,NativeTexturePreview.Projection.TEXTURE_2D,0,0);
                ImGuiMC.image(ImGuiMC.getTexture(image),width,width*image.getHeight(0)/image.getWidth(0),0,1,1,0);
                ImGui.endGroup();
            }
            ImGui.beginDisabled(saving!=null&&!saving.isDone());if(ImGui.button(SAVE.getString(),ImGui.getContentRegionAvailX(),0))save(id,target);ImGui.endDisabled();
            ImGui.endTabItem();
        }
        ImGui.endTabBar();
    }
    private void save(Identifier id,GpuFramebuffer target){
        final Path folder=Minecraft.getInstance().gameDirectory.toPath().resolve("debug-out/veil-framebuffer-"+System.currentTimeMillis());
        final var pending=new ArrayList<CompletableFuture<?>>();
        try{
            for(int index=0;index<target.colorCount();index++)pending.add(TextureDownloader.save(id+"-color"+index,folder,target.color(index).view(),true));
            if(target.hasDepth())pending.add(TextureDownloader.save(id+"-depth",folder,target.depth().view(),true));
            saving=CompletableFuture.allOf(pending.toArray(CompletableFuture[]::new)).thenRunAsync(()->org.lwjgl.sdl.SDLMisc.SDL_OpenURL(folder.toUri().toString()),Minecraft.getInstance())
                    .exceptionally(failure->{Veil.LOGGER.error("Failed to export native framebuffer {}",id,failure);return null;});
        }catch(RuntimeException failure){Veil.LOGGER.error("Failed to capture native framebuffer {}",id,failure);}
    }
    private static void retire(List<NativeTexturePreview> resources){ImGuiMC.afterDraw(()->resources.forEach(NativeTexturePreview::close));}
    @Override public void onHide(){previews.values().forEach(FramebufferInspector::retire);previews.clear();}
    @Override public void free(){onHide();}
}
