package foundry.veil.impl.client.editor;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.textures.GpuTexture;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import foundry.imgui.api.ImGuiMC;
import foundry.veil.Veil;
import foundry.veil.api.client.editor.SingleWindowInspector;
import foundry.veil.api.client.render.texture.NativeArrayTextureViews;
import foundry.veil.api.client.util.NativeTexturePreview;
import foundry.veil.api.client.util.NativeTextureRegistry;
import foundry.veil.api.client.util.TextureDownloader;
import imgui.ImGui;
import imgui.flag.ImGuiWindowFlags;
import imgui.type.ImBoolean;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.CompletableFuture;

/** Inventories real native allocations and previews their actual sampled contents. */
public final class TextureInspector extends SingleWindowInspector {
    public static final Component TITLE=Component.translatable("inspector.veil.texture.title");
    public static final Component DOWNLOAD=Component.translatable("inspector.veil.texture.button.download");
    public static final Component POP_OUT=Component.translatable("inspector.veil.texture.toggle.pop_out");
    public static final Component FLIP_X=Component.translatable("inspector.veil.texture.toggle.flip_x");
    public static final Component FLIP_Y=Component.translatable("inspector.veil.texture.toggle.flip_y");
    private final Map<GpuTexture,Storage> storage=new IdentityHashMap<>();
    private final Map<GpuTexture,OpenTexture> windows=new IdentityHashMap<>();
    private final ImBoolean flipX=new ImBoolean(),flipY=new ImBoolean();
    private GpuTexture selected;
    private CompletableFuture<?> download;
    @Override public Component getDisplayName(){return TITLE;}
    @Override public Component getGroup(){return RENDERER_GROUP;}
    @Override protected void renderComponents(){
        final var textures=NativeTextureRegistry.snapshot();final var live=Collections.newSetFromMap(new IdentityHashMap<GpuTexture,Boolean>());
        textures.forEach(entry->live.add(entry.texture()));
        storage.entrySet().removeIf(entry->{if(live.contains(entry.getKey()))return false;retire(entry.getValue());return true;});
        windows.keySet().removeIf(texture->!live.contains(texture));
        if(!live.contains(selected))selected=textures.isEmpty()?null:textures.getFirst().texture();
        if(ImGui.beginCombo("##native_textures",selected==null?"No native textures":name(selected))){
            for(var entry:textures){final var texture=entry.texture();ImGui.pushID(System.identityHashCode(texture));
                if(ImGui.selectable(name(texture),texture==selected))selected=texture;ImGui.popID();}
            ImGui.endCombo();
        }
        ImGui.beginDisabled(download!=null&&!download.isDone());if(ImGui.button(DOWNLOAD.getString()))download(textures);ImGui.endDisabled();
        ImGui.sameLine();ImGui.beginDisabled(selected==null);if(ImGui.button(POP_OUT.getString()))windows.put(selected,new OpenTexture(flipX.get(),flipY.get()));ImGui.endDisabled();
        ImGui.checkbox(FLIP_X.getString(),flipX);ImGui.sameLine();ImGui.checkbox(FLIP_Y.getString(),flipY);
        if(selected!=null)textures.stream().filter(entry->entry.texture()==selected).findFirst().ifPresent(entry->image(entry,flipX.get(),flipY.get()));
    }
    @Override public void render(){
        super.render();final var entries=NativeTextureRegistry.snapshot();
        final var iterator=windows.entrySet().iterator();
        while(iterator.hasNext()){
            final var entry=iterator.next();final var texture=entry.getKey();final var window=entry.getValue();if(!window.visible.get())continue;
            if(ImGui.begin(name(texture)+"###native_texture_"+System.identityHashCode(texture),window.open,ImGuiWindowFlags.NoSavedSettings)){
                ImGui.checkbox(FLIP_X.getString(),window.flipX);ImGui.sameLine();ImGui.checkbox(FLIP_Y.getString(),window.flipY);
                entries.stream().filter(value->value.texture()==texture).findFirst().ifPresent(value->image(value,window.flipX.get(),window.flipY.get()));
            }
            ImGui.end();if(!window.open.get())iterator.remove();
        }
    }
    private void image(NativeTextureRegistry.Entry entry,boolean horizontal,boolean vertical){
        final var texture=entry.texture();ImGui.text(texture.getFormat()+" "+texture.getWidth(0)+" x "+texture.getHeight(0)+", layers "+texture.getDepthOrLayers()+", mips "+texture.getMipLevels());
        if((texture.usage()&GpuTexture.USAGE_TEXTURE_BINDING)==0){ImGui.text("This allocation has no native sampling usage.");return;}
        final var item=storage.computeIfAbsent(texture,ignored->new Storage(entry));
        final int count=entry.kind()==NativeTextureRegistry.Kind.ARRAY?texture.getDepthOrLayers():1;
        for(int layer=0;layer<count;layer++){
            if(count>1)ImGui.text("Layer "+layer);
            while(item.previews.size()<=layer)item.previews.add(new NativeTexturePreview(RenderSystem.getDevice()));
            final var view=item.previews.get(layer).render(item.view,projection(entry.kind()),layer,0);
            final float width=ImGui.getContentRegionAvailX(),height=width*view.getHeight(0)/view.getWidth(0);
            ImGuiMC.image(ImGuiMC.getTexture(view),width,height,horizontal?1:0,vertical?1:0,horizontal?0:1,vertical?0:1);
        }
    }
    private void download(List<NativeTextureRegistry.Entry> textures){
        final Path folder=Minecraft.getInstance().gameDirectory.toPath().resolve("debug-out/veil-textures-"+System.currentTimeMillis());
        final var pending=new ArrayList<CompletableFuture<?>>();
        for(var entry:textures){
            if((entry.texture().usage()&GpuTexture.USAGE_TEXTURE_BINDING)==0)continue;
            try{final var item=storage.computeIfAbsent(entry.texture(),ignored->new Storage(entry));pending.add(TextureDownloader.save(name(entry.texture()),folder,item.view,projection(entry.kind()),false));}
            catch(RuntimeException failure){pending.add(CompletableFuture.failedFuture(failure));}
        }
        download=CompletableFuture.allOf(pending.toArray(CompletableFuture[]::new)).thenRunAsync(()->org.lwjgl.sdl.SDLMisc.SDL_OpenURL(folder.toUri().toString()),Minecraft.getInstance())
                .exceptionally(failure->{Veil.LOGGER.error("Failed to export native textures",failure);return null;});
    }
    private static NativeTexturePreview.Projection projection(NativeTextureRegistry.Kind kind){return switch(kind){case TEXTURE_2D->NativeTexturePreview.Projection.TEXTURE_2D;case CUBEMAP->NativeTexturePreview.Projection.CUBEMAP;case ARRAY->NativeTexturePreview.Projection.ARRAY;};}
    private static String name(GpuTexture texture){return Objects.toString(texture.getLabel(),"Texture")+" ["+texture.getFormat()+"]";}
    private static void retire(Storage item){ImGuiMC.afterDraw(item::close);}
    @Override public void renderMenuBar(){windows.forEach((texture,window)->ImGui.menuItem(name(texture),null,window.visible));}
    @Override public boolean isMenuBarEnabled(){return !windows.isEmpty();}
    @Override public void onHide(){storage.values().forEach(TextureInspector::retire);storage.clear();windows.clear();selected=null;}
    @Override public void free(){onHide();}
    private static final class OpenTexture {
        final ImBoolean open=new ImBoolean(true),visible=new ImBoolean(true),flipX,flipY;
        OpenTexture(boolean x,boolean y){flipX=new ImBoolean(x);flipY=new ImBoolean(y);}
    }
    private static final class Storage implements AutoCloseable {
        final GpuTextureView view;final List<NativeTexturePreview> previews=new ArrayList<>();
        Storage(NativeTextureRegistry.Entry entry){final var device=RenderSystem.getDevice();view=entry.kind()==NativeTextureRegistry.Kind.ARRAY?NativeArrayTextureViews.create(device,entry.texture()):device.createTextureView(entry.texture());}
        @Override public void close(){previews.forEach(NativeTexturePreview::close);view.close();}
    }
}
