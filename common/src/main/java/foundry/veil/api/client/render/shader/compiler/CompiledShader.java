package foundry.veil.api.client.render.shader.compiler;

import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import foundry.veil.api.client.render.gpu.GpuPipeline;
import net.minecraft.resources.Identifier;

import java.util.Objects;
import java.util.Set;

/** A distinct lease on a genuine compiled native pipeline; every lease closes independently. */
public final class CompiledShader implements AutoCloseable {
    private final Shared owner;
    private boolean closed;

    public CompiledShader(GpuPipeline pipeline, Set<String> definitionDependencies, Set<Identifier> includes) {
        this.owner = new Shared(Objects.requireNonNull(pipeline), Set.copyOf(definitionDependencies), Set.copyOf(includes));
    }
    private CompiledShader(Shared owner) { this.owner = owner; }

    /** The cache and callers retain separate handles, so closing a compiler cannot invalidate a live caller. */
    public CompiledShader retain() {
        synchronized (this.owner) {
            this.ensureOpen();
            this.owner.references = Math.incrementExact(this.owner.references);
            return new CompiledShader(this.owner);
        }
    }
    public RenderPipeline definition() { synchronized (this.owner) { this.ensureOpen(); return this.owner.pipeline.definition(); } }
    public Set<String> definitionDependencies() { return this.owner.definitionDependencies; }
    public Set<Identifier> includes() { return this.owner.includes; }
    public void bind(RenderPass pass) { synchronized (this.owner) { this.ensureOpen(); this.owner.pipeline.bind(pass); } }
    public boolean isClosed() { synchronized (this.owner) { return this.closed; } }
    private void ensureOpen() {
        if (this.closed || this.owner.pipeline.isClosed()) throw new IllegalStateException("Compiled native shader lease is closed");
    }
    public void free() { this.close(); }
    @Override public void close() {
        synchronized (this.owner) {
            if (this.closed) return;
            this.closed = true;
            if (--this.owner.references == 0) this.owner.pipeline.close();
        }
    }
    private static final class Shared {
        final GpuPipeline pipeline;
        final Set<String> definitionDependencies;
        final Set<Identifier> includes;
        int references = 1;
        Shared(GpuPipeline pipeline, Set<String> definitionDependencies, Set<Identifier> includes) {
            this.pipeline = pipeline; this.definitionDependencies = definitionDependencies; this.includes = includes;
        }
    }
}
