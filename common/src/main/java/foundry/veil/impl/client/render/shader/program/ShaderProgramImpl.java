package foundry.veil.impl.client.render.shader.program;
import com.mojang.renderpearl.api.device.GpuDevice;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import com.mojang.renderpearl.api.pipeline.ShaderSource;
import foundry.veil.api.client.render.gpu.GpuShaderProgram;
import foundry.veil.api.client.render.shader.program.ProgramDefinition;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Executor;

/** Resource-defined native program, retaining its reload/predefinition provenance. */
public final class ShaderProgramImpl extends GpuShaderProgram {
    private final ProgramDefinition definition;
    private final Set<String> dependencies;
    public ShaderProgramImpl(GpuDevice device, RenderPipeline pipeline, ShaderSource source, Executor workers,
            ProgramDefinition definition, Set<String> dependencies) {
        super(device, pipeline, validate(source, definition, dependencies), workers);
        this.definition = definition;
        // AST processors discover dependencies while the real native compiler requests its source.
        this.dependencies = Set.copyOf(dependencies);
    }
    @Override public Optional<ProgramDefinition> resourceDefinition() { return Optional.of(this.definition); }
    @Override public Set<String> getDefinitionDependencies() { return this.dependencies; }
    private static ShaderSource validate(ShaderSource source, ProgramDefinition definition, Set<String> dependencies) {
        Objects.requireNonNull(definition); Objects.requireNonNull(dependencies); return Objects.requireNonNull(source);
    }
}
