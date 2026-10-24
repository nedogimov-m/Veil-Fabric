package foundry.veil.api.resource.type;

import foundry.veil.Veil;
import foundry.veil.api.client.render.VeilRenderSystem;
import foundry.veil.api.resource.VeilResourceAction;
import foundry.veil.api.resource.VeilResourceInfo;
import foundry.veil.api.resource.VeilResourceManager;
import foundry.veil.impl.resource.action.ModelInspectAction;
import foundry.veil.impl.resource.action.TextEditAction;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.ResourceManager;
import org.jetbrains.annotations.ApiStatus;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.CompletionException;

@ApiStatus.Internal
public record BlockModelResource(VeilResourceInfo resourceInfo) implements VeilTextResource<BlockModelResource> {

    @Override
    public List<VeilResourceAction<BlockModelResource>> getActions() {
        return List.of(new TextEditAction<>(), new ModelInspectAction<>());
    }

    @Override
    public boolean canHotReload() {
        return true;
    }

    @Override
    public void hotReload(VeilResourceManager resourceManager) throws IOException {
        Identifier location = this.resourceInfo.location();
        ResourceManager resources = resourceManager.resources(this.resourceInfo);
        Minecraft client = Minecraft.getInstance();
        // Native model preparation requires the atlas stitch futures in the same SharedState.
        foundry.veil.api.resource.NativeResourceReload.reload(resources, client.getAtlasManager(), client.getModelManager())
                .thenAcceptAsync(unused -> { if (client.level != null) VeilRenderSystem.rebuildChunks(); }, client)
                .exceptionally(e -> {
                    while (e instanceof CompletionException) {
                        e = e.getCause();
                    }
                    Veil.LOGGER.error("Failed to load model {}", location, e);
                    return null;
                });
    }

    @Override
    public int getIconCode() {
        return 0xF383;
    }
}
