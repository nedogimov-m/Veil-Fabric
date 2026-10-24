package foundry.veil.api.resource.type;

import foundry.veil.api.client.render.VeilRenderSystem;
import foundry.veil.api.resource.VeilResourceAction;
import foundry.veil.api.resource.VeilResourceInfo;
import foundry.veil.api.resource.VeilResourceManager;
import foundry.veil.impl.resource.action.ShellInspectAction;
import foundry.veil.impl.resource.action.TextEditAction;

import java.io.IOException;
import java.util.List;

public record ShellResource(VeilResourceInfo resourceInfo) implements VeilTextResource<ShellResource> {

    @Override
    public List<VeilResourceAction<ShellResource>> getActions() {
        return List.of(new TextEditAction<>(), new ShellInspectAction<>());
    }

    @Override
    public boolean canHotReload() {
        return true;
    }

    @Override
    public void hotReload(VeilResourceManager resourceManager) throws IOException {
        foundry.veil.api.resource.NativeResourceReload.reload(resourceManager.resources(this.resourceInfo), VeilRenderSystem.renderer().getEffectManager().getShellManager())
                .exceptionally(failure -> { foundry.veil.Veil.LOGGER.error("Failed native resource hot reload {}", this.resourceInfo.location(), failure); return null; });
    }

    @Override
    public int getIconCode() {
        return 0xEDDF;
    }
}
