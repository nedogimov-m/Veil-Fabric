package foundry.veil.api.resource.type;

import foundry.veil.api.client.render.VeilRenderSystem;
import foundry.veil.api.resource.VeilResourceAction;
import foundry.veil.api.resource.VeilResourceInfo;
import foundry.veil.api.resource.VeilResourceManager;
import foundry.veil.impl.resource.action.TextEditAction;
import org.jetbrains.annotations.ApiStatus;

import java.io.IOException;
import java.util.List;

@ApiStatus.Internal
public record RenderTypeResource(VeilResourceInfo resourceInfo) implements VeilTextResource<RenderTypeResource> {

    @Override
    public List<VeilResourceAction<RenderTypeResource>> getActions() {
        return List.of(new TextEditAction<>());
    }

    @Override
    public boolean canHotReload() {
        return true;
    }

    @Override
    public void hotReload(VeilResourceManager resourceManager) throws IOException {
        foundry.veil.api.resource.NativeResourceReload.reload(resourceManager.resources(this.resourceInfo), VeilRenderSystem.renderer().getDynamicRenderTypeManager())
                .exceptionally(failure -> { foundry.veil.Veil.LOGGER.error("Failed native resource hot reload {}", this.resourceInfo.location(), failure); return null; });
    }

    @Override
    public int getIconCode() {
        return 0xED0F;
    }
}
