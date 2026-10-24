package foundry.veil.api.client.render.gpu;

import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import com.mojang.renderpearl.api.commands.CommandEncoder;
import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.api.device.GpuDevice;
import com.mojang.renderpearl.api.pipeline.BindGroupLayout;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import com.mojang.renderpearl.api.pipeline.ShaderSource;
import com.mojang.renderpearl.api.pipeline.UniformType;
import com.mojang.renderpearl.api.textures.GpuSampler;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import com.mojang.renderpearl.api.textures.GpuTexture;
import foundry.veil.api.client.render.texture.TextureFilter;
import foundry.veil.api.client.render.shader.uniform.ShaderUniform;
import foundry.veil.api.client.render.shader.uniform.ShaderUniformAccess;
import foundry.veil.impl.client.render.shader.uniform.ShaderUniformImpl;
import org.jetbrains.annotations.Nullable;
import foundry.veil.api.client.render.shader.program.ShaderProgram;
import foundry.veil.api.client.render.shader.program.ProgramDefinition;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.Executor;

/**
 * Shader program expressed as a native immutable pipeline plus typed bindings.
 * Prepare uniform uploads before opening a pass, then bind inside the pass.
 * Owned blocks and compiled pipelines close with this program; texture/sampler
 * and external block bindings remain owned by their callers.
 * Lifecycle mutations, snapshots and draw leases run on the owning render thread.
 */
public class GpuShaderProgram implements ShaderProgram {
    private final GpuDevice device;
    private final GpuPipeline pipeline;
    private final java.util.Set<String> generatedBlocks;
    private Map<String, GpuUniformBuffer.Layout> automaticLayouts;
    private final Map<String, UniformType> declarations;
    private final Map<String, GpuUniformBuffer> ownedBlocks = new HashMap<>();
    private final Map<String, GpuBufferSlice> externalBlocks = new HashMap<>();
    private final Map<String, TextureBinding> textures = new HashMap<>();
    private final Map<String, GpuSampler> ownedSamplers = new HashMap<>();
    private final Map<String, TextureFilter> samplerFilters = new HashMap<>();
    private boolean closed;
    private boolean closeRequested;
    private int leases;
    private final java.util.List<GpuSampler> retiredSamplers = new java.util.ArrayList<>();
    private java.util.Set<net.minecraft.resources.Identifier> includes;

    public GpuShaderProgram(GpuDevice device, RenderPipeline definition, ShaderSource source, Executor workers) {
        this.device = Objects.requireNonNull(device);
        definition = foundry.veil.api.client.render.shader.NativePipelineBridge.prepareDirectDefinition(definition, source);
        this.generatedBlocks = foundry.veil.api.client.render.shader.NativePipelineBridge.generatedUniformBlocks(source);
        this.automaticLayouts = foundry.veil.api.client.render.shader.NativePipelineBridge.automaticUniformLayouts(source);
        this.declarations = new HashMap<>();
        for (var uniform : BindGroupLayout.flattenUniforms(definition.getBindGroupLayouts())) {
            this.declarations.put(uniform.name(), uniform.type());
        }
        this.pipeline = GpuPipeline.compile(device, definition, source, workers);
        try {
            this.automaticLayouts.forEach(this::createUniformBlock);
        } catch (RuntimeException | Error failure) {
            this.ownedBlocks.values().forEach(GpuUniformBuffer::close); this.ownedBlocks.clear(); this.pipeline.close(); throw failure;
        }
        this.includes = source instanceof GpuShaderSource resourceSource ? resourceSource.includedResources() : java.util.Set.of();
    }
    public RenderPipeline definition() { return this.pipeline.definition(); }
    @Override public java.util.Optional<ProgramDefinition> resourceDefinition() { return java.util.Optional.empty(); }
    @Override public java.util.Set<net.minecraft.resources.Identifier> includedResources() { return this.includes; }
    @Override public java.util.Set<String> getDefinitionDependencies() {
        var dependencies = new java.util.HashSet<>(this.definition().getShaderDefines().values().keySet());
        dependencies.addAll(this.definition().getShaderDefines().flags());
        return java.util.Set.copyOf(dependencies);
    }
    public boolean hasUniform(String name) {
        if (this.declarations.containsKey(name)) return true;
        GpuUniformBuffer managed = this.ownedBlocks.get(GpuShaderSource.MANAGED_UNIFORMS);
        return managed != null && managed.layout().fields().containsKey(name);
    }
    public Map<String, UniformType> bindingTypes() { this.ensureOpen(); return Map.copyOf(this.declarations); }
    public Map<String, GpuUniformBuffer.Layout> uniformLayouts() {
        this.ensureOpen();
        var layouts = new HashMap<String, GpuUniformBuffer.Layout>();
        this.ownedBlocks.forEach((name, buffer) -> layouts.put(name, buffer.layout()));
        return Map.copyOf(layouts);
    }
    public Map<String, Long> uniformBlockSizes() {
        this.ensureOpen();
        var sizes = new HashMap<String, Long>();
        this.ownedBlocks.forEach((name, buffer) -> sizes.put(name, (long) buffer.layout().size()));
        this.externalBlocks.forEach((name, buffer) -> sizes.put(name, buffer.length()));
        return Map.copyOf(sizes);
    }
    public GpuUniformBuffer createUniformBlock(String name, GpuUniformBuffer.Layout layout) {
        this.require(name, UniformType.UNIFORM_BUFFER);
        GpuUniformBuffer replacement = new GpuUniformBuffer(this.device, this.definition().getLocation() + "/" + name, layout);
        GpuUniformBuffer previous = this.ownedBlocks.put(name, replacement);
        this.externalBlocks.remove(name);
        if (previous != null) previous.close();
        return replacement;
    }
    public void setUniformBlock(String name, GpuBufferSlice buffer) {
        this.require(name, UniformType.UNIFORM_BUFFER);
        if (buffer.buffer().isClosed()) throw new IllegalStateException("Cannot bind a closed uniform buffer");
        this.externalBlocks.put(name, buffer);
        GpuUniformBuffer previous = this.ownedBlocks.remove(name);
        if (previous != null) previous.close();
    }
    public void removeExternalUniformBlock(String name) { this.ensureOpen(); this.externalBlocks.remove(name); }
    public void setTexture(String name, GpuTextureView texture, GpuSampler sampler) {
        this.require(name, UniformType.COMBINED_IMAGE_SAMPLER);
        if (texture.isClosed() || sampler.isClosed()) throw new IllegalStateException("Cannot bind a closed texture or sampler");
        GpuSampler previous = this.ownedSamplers.get(name);
        TextureFilter retainedFilter = previous == sampler ? this.samplerFilters.get(name) : null;
        if (previous != sampler) {
            this.ownedSamplers.remove(name); this.samplerFilters.remove(name);
            if (previous != null) this.retireSampler(previous);
        }
        this.textures.put(name, new TextureBinding(texture, sampler, retainedFilter));
    }
    public void setTexture(String name, GpuTextureView texture, TextureFilter filter) {
        this.require(name, UniformType.COMBINED_IMAGE_SAMPLER);
        if (texture.isClosed()) throw new IllegalStateException("Cannot bind a closed texture");
        GpuSampler sampler = this.ownedSamplers.get(name);
        if (sampler == null || !filter.equals(this.samplerFilters.get(name))) {
            GpuSampler replacement = filter.createSampler(this.device);
            GpuSampler previous = this.ownedSamplers.put(name, replacement);
            this.samplerFilters.put(name, filter);
            if (previous != null) this.retireSampler(previous);
            sampler = replacement;
        }
        this.textures.put(name, new TextureBinding(texture, sampler, filter));
    }
    public boolean isClosed() { return this.closed; }
    /** Keep queued draw resources live across manager retirement and later material changes. */
    public Lease retain() {
        this.ensureOpen();
        if (this.closeRequested) throw new IllegalStateException("Cannot capture a retired shader program");
        this.leases = Math.incrementExact(this.leases);
        return new Lease();
    }
    public final class Lease implements AutoCloseable {
        private boolean released;
        private Lease() { }
        public GpuShaderProgram program() { return GpuShaderProgram.this; }
        @Override public void close() {
            if (this.released) return;
            this.released = true;
            if (--GpuShaderProgram.this.leases == 0) {
                GpuShaderProgram.this.retiredSamplers.forEach(GpuSampler::close);
                GpuShaderProgram.this.retiredSamplers.clear();
                if (GpuShaderProgram.this.closeRequested) GpuShaderProgram.this.dispose();
            }
        }
    }
    private void retireSampler(GpuSampler sampler) {
        if (this.leases != 0) this.retiredSamplers.add(sampler); else sampler.close();
    }
    public Map<String, GpuUniformBuffer.Snapshot> snapshotOwnedUniforms() {
        this.ensureOpen();
        var snapshots = new HashMap<String, GpuUniformBuffer.Snapshot>();
        this.ownedBlocks.forEach((name, buffer) -> snapshots.put(name, buffer.snapshot()));
        return Map.copyOf(snapshots);
    }
    public Map<String, GpuBufferSlice> snapshotExternalUniforms() { this.ensureOpen(); return Map.copyOf(this.externalBlocks); }
    public Map<String, TextureBinding> snapshotTextures() { this.ensureOpen(); return Map.copyOf(this.textures); }
    /** Distinct transient storage for each queued draw; valid for the owning frame. */
    public Map<String, GpuBufferSlice> prepareUniformSnapshots(CommandEncoder encoder, Map<String, GpuUniformBuffer.Snapshot> snapshots) {
        this.ensureOpen();
        var slices = new HashMap<String, GpuBufferSlice>();
        long alignment = this.device.getDeviceInfo().limits().minUniformOffsetAlignment();
        snapshots.forEach((name, snapshot) -> {
            this.require(name, UniformType.UNIFORM_BUFFER);
            slices.put(name, encoder.transientMemory().uploadGpu(snapshot.bytes(), alignment,
                    com.mojang.renderpearl.api.buffers.GpuBuffer.USAGE_UNIFORM));
        });
        return Map.copyOf(slices);
    }
    /** Start a new draw's texture bindings while retaining reusable owned samplers. */
    public void clearTextureBindings() { this.ensureOpen(); this.textures.clear(); }
    @Override public void removeTexture(String name) {
        this.ensureOpen(); this.textures.remove(name);
        GpuSampler sampler = this.ownedSamplers.remove(name); this.samplerFilters.remove(name);
        if (sampler != null) this.retireSampler(sampler);
    }
    public boolean usesTexture(GpuTexture texture) {
        this.ensureOpen();
        return this.textures.values().stream().anyMatch(binding -> binding.view.texture() == texture);
    }
    public GpuUniformBuffer getUniformBlock(String name) { this.ensureOpen(); return this.ownedBlocks.get(name); }
    public @Nullable ShaderUniform getUniform(String block, String field) {
        GpuUniformBuffer buffer = this.getUniformBlock(block);
        if (buffer == null || !buffer.layout().fields().containsKey(field)) return null;
        return new ShaderUniformImpl(() -> this.getUniformBlock(block), field);
    }
    public ShaderUniformAccess getUniformSafe(String block, String field) {
        ShaderUniform uniform = this.getUniform(block, field);
        return uniform != null ? uniform : ShaderUniformAccess.EMPTY;
    }
    private void require(String name, UniformType type) {
        this.ensureOpen();
        if (this.declarations.get(name) != type) throw new IllegalArgumentException("Shader binding " + name + " is not a " + type);
    }
    public void prepare(CommandEncoder encoder) {
        this.ensureOpen();
        this.ownedBlocks.values().forEach(block -> block.upload(encoder));
    }
    public void bind(RenderPass pass) {
        this.bind(pass, Map.of());
    }
    public void bind(RenderPass pass, Map<String, GpuBufferSlice> overrides) {
        this.bind(pass, overrides, this.textures);
    }
    /** Bind captured draw resources without mutating the live program bindings. */
    public void bind(RenderPass pass, Map<String, GpuBufferSlice> overrides, Map<String, TextureBinding> textures) {
        this.ensureOpen();
        overrides.keySet().forEach(name -> this.require(name, UniformType.UNIFORM_BUFFER));
        this.pipeline.bind(pass);
        this.declarations.forEach((name, type) -> {
            switch (type) {
                case UNIFORM_BUFFER -> {
                    GpuBufferSlice override = overrides.get(name);
                    if (override != null) {
                        if (override.buffer().isClosed()) throw new IllegalStateException("Captured uniform buffer is closed: " + name);
                        pass.setUniform(name, override); break;
                    }
                    GpuUniformBuffer owned = this.ownedBlocks.get(name);
                    if (owned != null) owned.bind(pass, name);
                    else {
                        GpuBufferSlice external = this.externalBlocks.get(name);
                        if (external == null && this.generatedBlocks.contains(name)
                                && foundry.veil.impl.client.render.shader.injection.NativeShaderInjectionFrames.hasNamedBinding(name)) break;
                        if (external == null || external.buffer().isClosed()) throw new IllegalStateException("Missing/live uniform buffer required: " + name);
                        pass.setUniform(name, external);
                    }
                }
                case COMBINED_IMAGE_SAMPLER -> {
                    TextureBinding texture = textures.get(name);
                    if (texture == null || texture.view.isClosed() || texture.sampler.isClosed()) throw new IllegalStateException("Missing/live texture and sampler required: " + name);
                    pass.setUniform(name, texture.view, texture.sampler);
                }
                case TEXEL_BUFFER -> throw new UnsupportedOperationException("Texel buffers need a typed texture-buffer binding adapter: " + name);
            }
        });
    }
    public void reload(ShaderSource source, Executor workers) {
        this.ensureOpen();
        if (this.leases != 0) throw new IllegalStateException("Queued draws retain this pipeline; replace its manager variant when reloading");
        foundry.veil.api.client.render.shader.NativePipelineBridge.prepareDirectDefinition(this.definition(), source);
        var nextLayouts = foundry.veil.api.client.render.shader.NativePipelineBridge.automaticUniformLayouts(source);
        if (!nextLayouts.keySet().equals(this.automaticLayouts.keySet()))
            throw new IllegalArgumentException("Changed owned uniform binding names require a new immutable native program owner");
        var replacements = new HashMap<String, GpuUniformBuffer>();
        try {
            nextLayouts.forEach((name, layout) -> {
                if (layout.equals(this.automaticLayouts.get(name))) return;
                if (this.externalBlocks.containsKey(name)) throw new IllegalStateException("External storage owns the changed uniform schema: " + name);
                var buffer = new GpuUniformBuffer(this.device, this.definition().getLocation() + "/" + name, layout);
                replacements.put(name, buffer);
                var previous = this.ownedBlocks.get(name);
                if (previous != null) {
                    var bytes = java.nio.ByteBuffer.allocateDirect(layout.size()).order(java.nio.ByteOrder.nativeOrder());
                    var oldBytes = previous.snapshot().bytes();
                    layout.fields().forEach((fieldName, field) -> {
                        var old = previous.layout().fields().get(fieldName);
                        if (old == null || old.type() != field.type() || old.arrayLength() != field.arrayLength()) return;
                        int size = field.type().columns() > 1 ? field.type().columns() * 16 : field.type().rows() * 4;
                        for (int element = 0; element < Math.max(1,field.arrayLength()); element++)
                            for (int index = 0; index < size; index++) bytes.put(field.offset(element) + index, oldBytes.get(old.offset(element) + index));
                    });
                    buffer.restore(bytes);
                }
            });
            this.pipeline.reload(source, workers);
        } catch (RuntimeException | Error failure) { replacements.values().forEach(GpuUniformBuffer::close); throw failure; }
        replacements.forEach((name, buffer) -> {
            GpuUniformBuffer previous = this.ownedBlocks.put(name,buffer); if (previous != null) previous.close();
        });
        this.automaticLayouts = nextLayouts;
        this.includes = source instanceof GpuShaderSource resourceSource ? resourceSource.includedResources() : java.util.Set.of();
    }
    private void ensureOpen() { if (this.closed) throw new IllegalStateException("Shader program is closed"); }
    @Override public void close() {
        if (this.closed) return;
        this.closeRequested = true;
        if (this.leases == 0) this.dispose();
    }
    private void dispose() {
        if (this.closed) return;
        this.closed = true;
        this.ownedBlocks.values().forEach(GpuUniformBuffer::close);
        this.ownedBlocks.clear();
        this.externalBlocks.clear();
        this.textures.clear();
        this.ownedSamplers.values().forEach(GpuSampler::close);
        this.ownedSamplers.clear();
        this.samplerFilters.clear();
        this.retiredSamplers.forEach(GpuSampler::close); this.retiredSamplers.clear();
        this.pipeline.close();
    }
    /** Views/samplers are borrowed. A non-null filter identifies a program-owned sampler. */
    public record TextureBinding(GpuTextureView view, GpuSampler sampler, @Nullable TextureFilter filter) { }
}
