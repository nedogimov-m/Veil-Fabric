package foundry.veil.api.resource.type;
import foundry.veil.api.client.render.VeilRenderSystem;
import foundry.veil.api.client.render.shader.ShaderManager;
import foundry.veil.api.resource.VeilResourceAction;
import foundry.veil.api.resource.VeilResourceInfo;
import foundry.veil.api.resource.VeilResourceManager;
import foundry.veil.impl.resource.action.TextEditAction;
import net.minecraft.resources.Identifier;
import java.util.List;

/** Hot reload follows native compiler-observed include dependencies. */
public record VeilShaderIncludeResource(VeilResourceInfo resourceInfo) implements VeilShaderResource<VeilShaderIncludeResource> {
    @Override public List<VeilResourceAction<VeilShaderIncludeResource>> getActions() { return List.of(new TextEditAction<>()); }
    @Override public boolean canHotReload() { return true; }
    @Override public void hotReload(VeilResourceManager resourceManager) {
        Identifier file = this.resourceInfo.location();
        String path = file.getPath();
        String prefix = path.startsWith("pinwheel/shaders/include/") ? "pinwheel/shaders/include/" : "shaders/include/";
        if (!path.startsWith(prefix) || !path.endsWith(".glsl")) throw new IllegalArgumentException("Not a shader include resource: " + file);
        Identifier include = Identifier.fromNamespaceAndPath(file.getNamespace(), path.substring(prefix.length(), path.length() - 5));
        VeilRenderSystem.renderer().getShaderManager().scheduleRecompileForInclude(include);
    }
}
