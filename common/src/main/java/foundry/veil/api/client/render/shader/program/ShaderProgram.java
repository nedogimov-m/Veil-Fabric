package foundry.veil.api.client.render.shader.program;
import com.mojang.renderpearl.api.commands.CommandEncoder;
import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import com.mojang.renderpearl.api.pipeline.ShaderSource;
import foundry.veil.api.client.render.shader.ShaderFeature;
import net.minecraft.resources.Identifier;
import org.jetbrains.annotations.Nullable;
import org.lwjgl.system.NativeResource;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Executor;

/**
 * A real native render pipeline with named typed resources. Bind only inside an
 * explicit pass, after preparing buffer uploads; lifetime belongs to its owner.
 */
public interface ShaderProgram extends UniformAccess, TextureUniformAccess, NativeResource, AutoCloseable {
    RenderPipeline definition();
    void prepare(CommandEncoder encoder);
    void bind(RenderPass pass);
    void reload(ShaderSource source, Executor workers);
    boolean isClosed();
    default boolean isValid() { return !this.isClosed(); }
    default Identifier getName() { return this.definition().getLocation(); }
    Optional<ProgramDefinition> resourceDefinition();
    default @Nullable ProgramDefinition getDefinition() { return this.resourceDefinition().orElse(null); }
    default Set<ShaderFeature> getRequiredFeatures() {
        return this.resourceDefinition().map(definition -> Set.copyOf(java.util.Arrays.asList(definition.requiredFeatures()))).orElse(Set.of());
    }
    Set<String> getDefinitionDependencies();
    Set<Identifier> includedResources();
    @Override void close();
    @Override default void free() { this.close(); }
}
