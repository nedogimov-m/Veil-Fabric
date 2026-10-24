package foundry.veil.api.resource.type;

import foundry.veil.api.resource.VeilResourceAction;
import foundry.veil.api.resource.VeilResourceInfo;
import foundry.veil.api.resource.VeilResourceManager;
import foundry.veil.impl.resource.action.TextEditAction;
import net.minecraft.client.Minecraft;
import org.jetbrains.annotations.ApiStatus;

import java.util.List;

@ApiStatus.Internal
public record VanillaShaderFileResource(VeilResourceInfo resourceInfo) implements VeilShaderResource<VanillaShaderFileResource> {

    @Override
    public List<VeilResourceAction<VanillaShaderFileResource>> getActions() {
        return List.of(new TextEditAction<>());
    }

    @Override
    public boolean canHotReload() {
        return true;
    }

    @Override
    public void hotReload(VeilResourceManager resourceManager) {
        // Native vanilla shader sources and cache variants are replaced by their real resource reload owner.
        Minecraft.getInstance().reloadResourcePacks().exceptionally(failure -> {
            org.slf4j.LoggerFactory.getLogger("Veil").error("Cannot reload vanilla native shader resources", failure); return null;
        });
    }
}
