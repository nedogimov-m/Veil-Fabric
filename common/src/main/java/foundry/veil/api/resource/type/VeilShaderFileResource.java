package foundry.veil.api.resource.type;
import foundry.veil.api.client.render.shader.ShaderManager;
import foundry.veil.api.client.render.shader.ShaderSourceSet;
import foundry.veil.api.resource.VeilResourceAction;
import foundry.veil.api.resource.VeilResourceInfo;
import foundry.veil.api.resource.VeilResourceManager;
import foundry.veil.impl.resource.action.TextEditAction;
import net.minecraft.resources.Identifier;
import java.util.List;

/** Resource source edits schedule the real native program variants using that stage. */
public record VeilShaderFileResource(VeilResourceInfo resourceInfo, ShaderManager shaderManager) implements VeilShaderResource<VeilShaderFileResource> {
    @Override public List<VeilResourceAction<VeilShaderFileResource>> getActions() { return List.of(new TextEditAction<>()); }
    @Override public boolean canHotReload() { return true; }
    @Override public void hotReload(VeilResourceManager resourceManager) {
        Identifier file = this.resourceInfo.location();
        int type = ShaderSourceSet.getShaderType(file);
        if (type == -1) throw new IllegalArgumentException("Unknown shader stage resource: " + file);
        String custom = this.shaderManager.getSourceSet().getFolder() + "/";
        String path = file.getPath();
        String prefix = path.startsWith(custom) ? custom : "shaders/";
        if (!path.startsWith(prefix)) throw new IllegalArgumentException("Not a native shader source resource: " + file);
        String relative = path.substring(prefix.length());
        int extension = relative.lastIndexOf('.');
        Identifier source = Identifier.fromNamespaceAndPath(file.getNamespace(), extension >= 0 ? relative.substring(0, extension) : relative);
        this.shaderManager.scheduleRecompileForSource(type, source);
    }
}
