package foundry.veil.api.resource.type;

import foundry.imgui.api.ImGuiMC;
import foundry.imgui.api.ImGuiTextureProvider;
import foundry.veil.api.client.imgui.VeilImGuiUtil;
import foundry.veil.api.client.render.VeilRenderSystem;
import foundry.veil.api.resource.VeilResource;
import foundry.veil.api.resource.VeilResourceAction;
import foundry.veil.api.resource.VeilResourceInfo;
import foundry.veil.api.resource.VeilResourceManager;
import foundry.veil.api.util.CompositeReloadListener;
import foundry.veil.ext.TextureAtlasExtension;
import imgui.ImGui;
import imgui.flag.ImGuiCol;
import imgui.flag.ImGuiStyleVar;
import net.minecraft.util.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.atlas.SpriteSource;
import net.minecraft.resources.Identifier;
import org.jetbrains.annotations.ApiStatus;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.CompletableFuture;

@ApiStatus.Internal
public record TextureResource(VeilResourceInfo resourceInfo) implements VeilResource<TextureResource> {

    @Override
    public void render(boolean dragging, boolean fullName) {
        float size = ImGui.getTextLineHeight();
        ImGuiTextureProvider texture = ImGuiMC.getTexture(Minecraft.getInstance().getTextureManager().getTexture(this.resourceInfo.location()));

        ImGui.pushStyleColor(ImGuiCol.Text, this.resourceInfo.isStatic() ? 0xFFAAAAAA : 0xFFFFFFFF);
        if (dragging) {
            ImGuiMC.image(texture, size * 8, size * 8);
            VeilImGuiUtil.resourceLocation(this.resourceInfo().location());
        } else {
            ImGui.pushStyleVar(ImGuiStyleVar.ItemSpacing, 0, 0);
            ImGui.setNextItemAllowOverlap();
            ImGuiMC.image(texture, size, size);
            ImGui.sameLine();
            ImGui.popStyleVar();

            if (ImGui.isItemHovered()) {
                ImGui.beginTooltip();
                ImGuiMC.image(texture, size * 16, size * 16);
                ImGui.endTooltip();
            }
            ImGui.sameLine();

            if (fullName) {
                VeilImGuiUtil.resourceLocation(this.resourceInfo.location());
            } else {
                ImGui.text(this.resourceInfo.fileName());
            }
        }
        ImGui.popStyleColor();
    }

    @Override
    public List<VeilResourceAction<TextureResource>> getActions() {
        return List.of();
    }

    @Override
    public boolean canHotReload() {
        return true;
    }

    @Override
    public void hotReload(VeilResourceManager resourceManager) throws IOException {
        final var resources=resourceManager.resources(this.resourceInfo);
        final var client=Minecraft.getInstance();
        final var texture=client.getTextureManager().getTexture(this.resourceInfo.location());
        if(texture instanceof net.minecraft.client.renderer.texture.ReloadableTexture reloadable){
            try(var contents=reloadable.loadContents(resources)){reloadable.apply(contents);}
        }
        final var sprite=SpriteSource.TEXTURE_ID_CONVERTER.fileToId(this.resourceInfo.location());
        final boolean[] used={false};
        client.getAtlasManager().forEach((name,atlas)->{
            if(((TextureAtlasExtension)atlas).veil$hasTexture(sprite))used[0]=true;
        });
        if(!used[0])return;
        // Native model baking consumes the exact stitch futures stored by AtlasManager in SharedState.
        // Reload both owners so item/block/fluid models never retain retired sprite instances.
        final var transaction=CompositeReloadListener.of(client.getAtlasManager(),client.getModelManager());
        final var shared=new net.minecraft.server.packs.resources.PreparableReloadListener.SharedState(resources);
        transaction.prepareSharedState(shared);
        transaction.reload(shared,Util.backgroundExecutor(),new net.minecraft.server.packs.resources.PreparableReloadListener.PreparationBarrier(){
            @Override public <T> CompletableFuture<T> wait(T value){return CompletableFuture.completedFuture(value);}
        },client).thenRunAsync(()->{if(client.level!=null)VeilRenderSystem.rebuildChunks();},client)
                .exceptionally(failure->{foundry.veil.Veil.LOGGER.error("Failed native texture/atlas hot reload {}",this.resourceInfo.location(),failure);return null;});
    }

    @Override
    public int getIconCode() {
        return 0xF3C5; // Image file icon
    }
}
