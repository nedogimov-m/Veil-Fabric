package foundry.veil.api.resource.type;

import foundry.veil.api.resource.VeilResourceAction;
import foundry.veil.api.resource.VeilResourceInfo;
import foundry.veil.api.resource.VeilResourceManager;
import foundry.veil.impl.flare.FlareManager;
import foundry.veil.impl.resource.action.TemplateInspectAction;
import foundry.veil.impl.resource.action.TextEditAction;

import java.io.IOException;
import java.util.List;

public record FlareResource(VeilResourceInfo resourceInfo) implements VeilTextResource<FlareResource> {

    @Override
    public List<VeilResourceAction<FlareResource>> getActions() {
        return this.resourceInfo.location().getPath().startsWith("flare/templates") ?
                List.of(new TextEditAction<>(), new TemplateInspectAction<>()) :
                List.of(new TextEditAction<>());
    }

    @Override
    public boolean canHotReload() {
        return true;
    }

    @Override
    public void hotReload(VeilResourceManager resourceManager) throws IOException {
        foundry.veil.api.resource.NativeResourceReload.reload(resourceManager.resources(this.resourceInfo), FlareManager.Reloader.INSTANCE)
                .exceptionally(failure -> { foundry.veil.Veil.LOGGER.error("Failed native resource hot reload {}", this.resourceInfo.location(), failure); return null; });
    }

    @Override
    public int getIconCode() {
        return 0xEA1C;
    }
}
