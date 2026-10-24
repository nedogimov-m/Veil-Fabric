package foundry.veil.impl.client.render.shader.injection;

import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.api.device.GpuDevice;
import com.mojang.renderpearl.api.pipeline.*;
import com.mojang.renderpearl.api.textures.GpuSampler;
import foundry.veil.api.client.render.shader.NativePipelineBridge;
import foundry.veil.api.client.render.shader.ShaderPreDefinitions;
import foundry.veil.api.client.render.shader.processor.ShaderInjectProcessor;
import foundry.veil.api.client.render.shader.program.ProgramDefinition;
import foundry.veil.api.client.render.shader.texture.*;
import foundry.veil.api.event.VeilAddShaderPreProcessorsEvent;
import foundry.veil.impl.client.render.shader.ShaderImporterImpl;
import it.unimi.dsi.fastutil.ints.Int2ObjectArrayMap;
import it.unimi.dsi.fastutil.ints.Int2ObjectMaps;
import net.minecraft.client.renderer.ShaderDefines;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.ResourceProvider;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.ref.WeakReference;
import java.util.*;
import java.util.function.*;

/**
 * Immutable native source-injection/replacement recipes. Plans own CPU data only; explicit frame
 * preparation captures live typed resources before opening any pass. Keep each frame through submission.
 */
public final class NativeShaderInjectionService implements AutoCloseable {
    private static NativeShaderInjectionService runtime;
    private static final ShaderInjectionManager.Snapshot EMPTY = new ShaderInjectionManager.Snapshot(Map.of(), Map.of(), Map.of());
    private final ShaderInjectionManager injections;
    private final Supplier<Map<Identifier, ProgramDefinition>> definitions;
    private final ShaderPreDefinitions preDefinitions;
    private final Map<RequestKey, BindingRequest> requests = new HashMap<>();
    private final List<WeakReference<Plan>> plans = new ArrayList<>();
    private Frame current;
    private boolean closed;

    public NativeShaderInjectionService(ShaderInjectionManager injections,
            Supplier<Map<Identifier, ProgramDefinition>> definitions, ShaderPreDefinitions preDefinitions) {
        this.injections = Objects.requireNonNull(injections); this.definitions = Objects.requireNonNull(definitions);
        this.preDefinitions = Objects.requireNonNull(preDefinitions);
    }
    public static synchronized void bindRuntime(NativeShaderInjectionService owner) {
        if (runtime != null && runtime != owner) throw new IllegalStateException("Native shader injection runtime already bound");
        runtime = Objects.requireNonNull(owner);
    }
    public static synchronized void unbindRuntime(NativeShaderInjectionService owner) { if (runtime == owner) runtime = null; }
    public static synchronized ShaderInjectionManager.Snapshot runtimeSnapshot() { return runtime == null ? EMPTY : runtime.injections.snapshot(); }

    /** Pure CPU planning, called before asynchronous compilation. The bridge must detach/expand includes while resources are live. */
    public synchronized Optional<Plan> plan(RenderPipeline original, ShaderSource originalSource, ResourceProvider resources) {
        this.ensureOpen(); Objects.requireNonNull(original); Objects.requireNonNull(originalSource); Objects.requireNonNull(resources);
        var snapshot = this.injections.snapshot(); Identifier replacement = null;
        for (var shader : original.getShaders().entrySet()) {
            Identifier selected = snapshot.getReplacement(shader.getKey().idConverter().idToFile(shader.getValue()));
            if (selected == null) selected = snapshot.getReplacement(shader.getValue());
            if (selected == null) continue;
            if (replacement != null && !replacement.equals(selected)) throw new IllegalArgumentException("Native shader stages request conflicting replacement programs: " + original.getLocation());
            replacement = selected;
        }
        if (replacement == null && snapshot.shaders().isEmpty()) return Optional.empty();
        BindingRequest request = null; RenderPipeline pipeline = original;
        var dependencies = new HashSet<String>();
        if (replacement != null) {
            ProgramDefinition definition = this.definitions.get().get(replacement);
            if (definition == null) throw new IllegalArgumentException("Unknown native replacement shader definition: " + replacement);
            request = this.request(replacement, definition);
            pipeline = replacementPipeline(original, request.definition, request.definition.getMacros(dependencies, this.preDefinitions));
        }
        var text = new HashMap<SourceKey, String>();
        for (var shader : pipeline.getShaders().entrySet()) {
            String raw = replacement == null ? originalSource.getShader(shader.getValue(), shader.getKey())
                    : readReplacement(resources, shader.getValue(), shader.getKey());
            if (raw == null) throw new IllegalArgumentException("Native source is missing: " + shader.getValue() + " " + shader.getKey());
            text.put(new SourceKey(shader.getValue(), shader.getKey()), raw);
        }
        Plan result = new Plan(original, pipeline, Map.copyOf(text), resources, snapshot, request, Set.copyOf(dependencies));
        this.plans.add(new WeakReference<>(result)); return Optional.of(result);
    }
    private BindingRequest request(Identifier id, ProgramDefinition definition) {
        return this.requests.computeIfAbsent(new RequestKey(id, definition), ignored -> new BindingRequest(id, snapshot(definition)));
    }
    /** Additional UBOs required by already planned native draws; inherited native draw blocks stay with their draw owner. */
    public synchronized Set<String> requiredUniformBlocks(BindingRequest request) {
        var required = new HashSet<String>();
        for (var reference : this.plans) {
            Plan plan = reference.get();
            if (plan != null && plan.request == request) required.addAll(plan.requiredUniformBlocks);
        }
        return Set.copyOf(required);
    }
    /** Current definitions and still-live compiled recipes, including programs first used lazily later in the frame. */
    public synchronized List<BindingRequest> bindingRequests() {
        this.ensureOpen();
        Set<BindingRequest> selected = Collections.newSetFromMap(new IdentityHashMap<>());
        Map<Identifier, ProgramDefinition> programs = this.definitions.get();
        for (Identifier id : new HashSet<>(this.injections.snapshot().replacements().values())) {
            ProgramDefinition definition = programs.get(id);
            if (definition == null) throw new IllegalArgumentException("Unknown native replacement shader definition: " + id);
            selected.add(this.request(id, definition));
        }
        var iterator = this.plans.iterator();
        while (iterator.hasNext()) {
            Plan plan = iterator.next().get();
            if (plan == null) iterator.remove(); else if (plan.request != null) selected.add(plan.request);
        }
        return List.copyOf(selected);
    }
    /** Known extra bindings, plus explicitly declared custom blocks for native pipelines first compiled later in the frame. */
    public synchronized Set<String> frameUniformBlocks() {
        var names = new HashSet<String>();
        for (BindingRequest request : this.bindingRequests()) {
            boolean known = false;
            for (var reference : this.plans) {
                Plan plan = reference.get();
                if (plan != null && plan.request == request) { known = true; names.addAll(plan.requiredUniformBlocks); }
            }
            if (!known) {
                var custom = new HashSet<>(request.definition.uniformBlocks());
                custom.removeAll(Set.of("Projection", "DynamicTransforms", "Globals", "Fog", "Lighting"));
                names.addAll(custom);
            }
        }
        return Set.copyOf(names);
    }
    /**
     * Resolve every active replacement recipe at the frame boundary. Resolvers may upload/create resources here;
     * each returned owner is released only when this frame closes, after the caller's native encoder submission.
     */
    public synchronized Frame prepareFrame(ResourceResolver resolver) {
        this.ensureOpen(); Objects.requireNonNull(resolver);
        List<BindingRequest> selected = this.bindingRequests();
        var bindings = new IdentityHashMap<BindingRequest, PreparedBindings>();
        try {
            for (BindingRequest request : selected) {
                PreparedBindings prepared = Objects.requireNonNull(resolver.prepare(request), "Missing prepared replacement resources: " + request.id);
                bindings.put(request, prepared); validate(request, prepared, this.requiredUniformBlocks(request));
            }
        } catch (RuntimeException | Error failure) { closeBindings(bindings.values(), failure); throw failure; }
        Frame frame = new Frame(this.current, bindings); this.current = frame; return frame;
    }
    /** Convenience preparation for JSON texture sources and explicitly supplied named native uniform slices. */
    public Frame prepareFrame(GpuDevice device, ShaderTextureSource.Context textures,
            BiFunction<BindingRequest, String, GpuBufferSlice> uniforms) {
        return this.prepareFrame(request -> {
            var blocks = new HashMap<String, GpuBufferSlice>(); var sampled = new HashMap<String, NativePipelineBridge.Texture>();
            var owned = new ArrayList<GpuSampler>();
            try {
                for (String name : request.definition.uniformBlocks()) {
                    GpuBufferSlice value = uniforms.apply(request, name);
                    if (value != null) blocks.put(name, value);
                }
                request.definition.samplers().forEach((name, source) -> {
                    var view = source.getTexture(textures); GpuSampler sampler;
                    if (source.filter() != null) { sampler = source.filter().createSampler(device); owned.add(sampler); }
                    else if (source instanceof LocationSource location) sampler = textures.getSampler(location.location());
                    else if (source instanceof FramebufferSource framebuffer) { sampler = textures.framebufferFilter(framebuffer.name(), framebuffer.sampler(), framebuffer.depth()).createSampler(device); owned.add(sampler); }
                    else throw new IllegalArgumentException("Unknown native replacement texture source: " + source);
                    sampled.put(name, new NativePipelineBridge.Texture(view, sampler));
                });
                return new PreparedBindings(blocks, sampled, () -> owned.forEach(GpuSampler::close));
            } catch (RuntimeException | Error failure) { owned.forEach(GpuSampler::close); throw failure; }
        });
    }
    public final class Plan {
        private final RenderPipeline original, pipeline;
        private final Map<SourceKey, String> text;
        private final ResourceProvider resources;
        private final ShaderInjectionManager.Snapshot snapshot;
        private final BindingRequest request;
        private final Set<String> dependencies;
        private final Set<String> requiredUniformBlocks;
        private Plan(RenderPipeline original, RenderPipeline pipeline, Map<SourceKey, String> text, ResourceProvider resources,
                ShaderInjectionManager.Snapshot snapshot, BindingRequest request, Set<String> dependencies) {
            this.original = original; this.pipeline = pipeline; this.text = text; this.resources = resources;
            this.snapshot = snapshot; this.request = request; this.dependencies = dependencies;
            var inherited = new HashSet<String>();
            BindGroupLayout.flattenUniforms(original.getBindGroupLayouts()).forEach(uniform -> {
                if (uniform.type() == UniformType.UNIFORM_BUFFER) inherited.add(uniform.name());
            });
            var required = new HashSet<String>();
            if (request != null) required.addAll(request.definition.uniformBlocks());
            required.removeAll(inherited); this.requiredUniformBlocks = Set.copyOf(required);
        }
        public RenderPipeline original() { return this.original; }
        public RenderPipeline pipeline() { return this.pipeline; }
        public ResourceProvider resources() { return this.resources; }
        public Optional<BindingRequest> replacement() { return Optional.ofNullable(this.request); }
        public Set<String> definitionDependencies() { return this.dependencies; }
        public Set<String> requiredUniformBlocks() { return this.requiredUniformBlocks; }
        /** Fresh source owner on each replay; raw stage text survives previous source/native cache retirement. */
        public ShaderSource source() { return new RawSource(this.text, this.resources); }
        public List<VeilAddShaderPreProcessorsEvent> registrars() {
            return List.of((resources, registry) -> registry.addPreprocessor(new ShaderInjectProcessor(this.snapshot), false));
        }
        /** Capture the same prepared frame selected by bind, keeping its exact resource owners live through submission. */
        public Optional<AutoCloseable> retainFrame() {
            if (this.request == null) return Optional.empty();
            return Optional.of(this.selectedFrame().retain());
        }
        private Frame selectedFrame() {
            Frame frame = NativeShaderInjectionService.this.current;
            if (frame == null || frame.closed || !frame.bindings.containsKey(this.request))
                throw new IllegalStateException("Prepare native replacement resources before opening the consuming pass: " + this.request.id);
            return frame;
        }
        /** Only bind existing frame snapshots. This method never creates encoders, uploads, or GPU resources. */
        public void bind(RenderPass pass) {
            if (this.request == null) return;
            PreparedBindings bindings = this.selectedFrame().bindings.get(this.request);
            validate(this.request, bindings, this.requiredUniformBlocks);
            this.requiredUniformBlocks.forEach(name -> pass.setUniform(name, bindings.uniforms.get(name)));
            bindings.textures.forEach((name, texture) -> pass.setUniform(name, texture.view(), texture.sampler()));
        }
    }
    public final class Frame implements AutoCloseable {
        private final Frame previous;
        private final Map<BindingRequest, PreparedBindings> bindings;
        private final List<PreparedBindings> attachedOwners = new ArrayList<>();
        private int references = 1;
        private boolean closed;
        private Frame(Frame previous, Map<BindingRequest, PreparedBindings> bindings) { this.previous = previous; this.bindings = Collections.unmodifiableMap(bindings); }
        /** Additional named-buffer ownership shares every generated-block and replacement submission lease. */
        public Frame own(AutoCloseable owner) {
            if (this.closed) throw new IllegalStateException("Cannot attach resources to a closed native replacement frame");
            this.attachedOwners.add(new PreparedBindings(Map.of(), Map.of(), owner)); return this;
        }
        public AutoCloseable retain() {
            if (this.closed) throw new IllegalStateException("Cannot retain a closed native replacement frame scope");
            this.references++;
            return new AutoCloseable() {
                private boolean released;
                @Override public void close() { if (!this.released) { this.released = true; Frame.this.release(); } }
            };
        }
        private void release() {
            if (--this.references != 0) return;
            var all = new ArrayList<>(this.bindings.values()); all.addAll(this.attachedOwners);
            closeBindings(all, null);
        }
        @Override public void close() {
            if (this.closed) return;
            if (NativeShaderInjectionService.this.current != this) throw new IllegalStateException("Native replacement frames must close in stack order");
            this.closed = true; NativeShaderInjectionService.this.current = this.previous;
            this.release();
        }
    }
    @FunctionalInterface public interface ResourceResolver { PreparedBindings prepare(BindingRequest request); }
    public record BindingRequest(Identifier id, ProgramDefinition definition) { }
    public record PreparedBindings(Map<String, GpuBufferSlice> uniforms, Map<String, NativePipelineBridge.Texture> textures, AutoCloseable owner) {
        public PreparedBindings { uniforms = Map.copyOf(uniforms); textures = Map.copyOf(textures); Objects.requireNonNull(owner); }
    }
    private static void validate(BindingRequest request, PreparedBindings bindings, Set<String> requiredUniformBlocks) {
        if (!request.definition.uniformBlocks().containsAll(bindings.uniforms.keySet()) || !bindings.textures.keySet().equals(request.definition.samplers().keySet()))
            throw new IllegalArgumentException("Prepared native replacement bindings differ from its exact definition: " + request.id);
        if (!bindings.uniforms.keySet().containsAll(requiredUniformBlocks))
            throw new IllegalArgumentException("Prepare required additional native replacement uniform blocks before drawing " + request.id + ": " + requiredUniformBlocks);
        bindings.uniforms.forEach((name, buffer) -> {
            if (buffer.buffer().isClosed() || (buffer.buffer().usage() & GpuBuffer.USAGE_UNIFORM) == 0) throw new IllegalArgumentException("Replacement uniform buffer is not live/typed: " + name);
        });
        bindings.textures.forEach((name, texture) -> {
            if (texture.view().isClosed() || texture.view().texture().isClosed() || texture.sampler().isClosed()) throw new IllegalArgumentException("Replacement sampled texture is not live: " + name);
        });
    }
    private static RenderPipeline replacementPipeline(RenderPipeline original, ProgramDefinition definition, Map<String, String> macros) {
        if (definition.vertex() == null || definition.fragment() == null || definition.geometry() != null || definition.compute() != null
                || definition.tesselationControl() != null || definition.tesselationEvaluation() != null)
            throw new UnsupportedOperationException("Native pipeline replacements require migrated vertex+fragment stages");
        for (var feature : definition.requiredFeatures()) if (!feature.isSupported()) throw new UnsupportedOperationException("Native replacement shader feature is not supported: " + feature);
        var declarations = new HashMap<String, UniformType>();
        BindGroupLayout.flattenUniforms(original.getBindGroupLayouts()).forEach(uniform -> declarations.put(uniform.name(), uniform.type()));
        var extra = BindGroupLayout.builder(); int extraCount = 0;
        for (String block : definition.uniformBlocks()) extraCount += declare(declarations, extra, block, UniformType.UNIFORM_BUFFER);
        for (String sampler : new TreeSet<>(definition.samplers().keySet())) extraCount += declare(declarations, extra, sampler, UniformType.COMBINED_IMAGE_SAMPLER);
        var values = new HashMap<>(original.getShaderDefines().values()); var flags = new HashSet<>(original.getShaderDefines().flags());
        macros.forEach((name, value) -> {
            if (!name.matches("[A-Za-z_][A-Za-z0-9_]*") || value.contains("\n") || value.contains("\r")) throw new IllegalArgumentException("Invalid native replacement macro: " + name);
            if (value.isBlank()) { values.remove(name); flags.add(name); } else { flags.remove(name); values.put(name, value); }
        });
        var shaders = new EnumMap<ShaderType, Identifier>(ShaderType.class); shaders.put(ShaderType.VERTEX, definition.vertex()); shaders.put(ShaderType.FRAGMENT, definition.fragment());
        var snippet = new RenderPipeline.Snippet(shaders, Optional.of(new ShaderDefines(values, flags)), Optional.of(original.getBindGroupLayouts()),
                original.getColorTargetStates().toArray(ColorTargetState[]::new), original.getColorTargetStates().size(),
                Optional.ofNullable(original.getDepthStencilState()), Optional.of(original.getPolygonMode()), Optional.of(original.isCull()),
                original.getVertexFormatBindings().toArray(com.mojang.renderpearl.api.vertex.VertexFormat[]::new), Optional.of(original.getPrimitiveTopology()), original.pushConstantSize());
        var builder = RenderPipeline.builder(snippet).withLocation(original.getLocation()); if (extraCount != 0) builder.withBindGroupLayout(extra.build());
        return builder.build();
    }
    private static int declare(Map<String, UniformType> declarations, BindGroupLayout.Builder extra, String name, UniformType type) {
        if (!name.matches("[A-Za-z_][A-Za-z0-9_]*")) throw new IllegalArgumentException("Invalid native replacement binding: " + name);
        UniformType old = declarations.putIfAbsent(name, type);
        if (old != null && old != type) throw new IllegalArgumentException("Native replacement binding conflicts with original draw resource: " + name);
        if (old != null) return 0; extra.withUniform(name, type); return 1;
    }
    private static ProgramDefinition snapshot(ProgramDefinition definition) {
        return new ProgramDefinition(definition.vertex(), definition.tesselationControl(), definition.tesselationEvaluation(), definition.geometry(),
                definition.fragment(), definition.compute(), definition.definitions().clone(), Map.copyOf(definition.definitionDefaults()), Map.copyOf(definition.samplers()),
                definition.uniformBlocks(), Int2ObjectMaps.unmodifiable(new Int2ObjectArrayMap<>(definition.shaders())), definition.requiredFeatures().clone(), definition.blendMode());
    }
    private static String readReplacement(ResourceProvider resources, Identifier id, ShaderType stage) {
        Identifier file = Identifier.fromNamespaceAndPath(id.getNamespace(), "pinwheel/shaders/program/" + id.getPath() + (stage == ShaderType.VERTEX ? ".vsh" : ".fsh"));
        if (resources.getResource(file).isEmpty()) file = stage.idConverter().idToFile(id);
        try { return resources.getResourceOrThrow(file).readAllAsString(); }
        catch (IOException failure) { throw new UncheckedIOException("Cannot load native replacement source: " + file, failure); }
    }
    private static void closeBindings(Collection<PreparedBindings> bindings, Throwable original) {
        Set<AutoCloseable> owners = Collections.newSetFromMap(new IdentityHashMap<>()); RuntimeException failure = null;
        for (PreparedBindings prepared : bindings) if (owners.add(prepared.owner)) try { prepared.owner.close(); }
        catch (Exception cleanup) { if (original != null) original.addSuppressed(cleanup); else if (failure == null) failure = new IllegalStateException("Cannot release native replacement resources", cleanup); else failure.addSuppressed(cleanup); }
        if (failure != null) throw failure;
    }
    private void ensureOpen() { if (this.closed) throw new IllegalStateException("Native shader injection service is closed"); }
    @Override public synchronized void close() { if (!this.closed) { this.closed = true; unbindRuntime(this); this.requests.clear(); this.plans.clear(); } }
    private record RequestKey(Identifier id, ProgramDefinition source) { }
    private record SourceKey(Identifier id, ShaderType stage) { }
    private static final class RawSource implements ShaderSource {
        private final Map<SourceKey, String> text;
        private final ShaderImporterImpl importer;
        private final Map<Identifier, CachedIncludeSource> includes = new HashMap<>();
        private boolean closed;
        RawSource(Map<SourceKey, String> text, ResourceProvider resources) { this.text = text; this.importer = new ShaderImporterImpl(resources); }
        @Override public String getShader(Identifier id, ShaderType stage) { if (this.closed) throw new IllegalStateException("Native replacement source is closed"); return this.text.get(new SourceKey(id, stage)); }
        @Override public synchronized CachedIncludeSource getInclude(Identifier id) {
            if (this.closed) throw new IllegalStateException("Native replacement source is closed");
            return this.includes.computeIfAbsent(id, key -> {
                try { return CachedIncludeSource.create(key, this.importer.loadSource(key)); }
                catch (IOException failure) { return CachedIncludeSource.createError(failure.getMessage()); }
            });
        }
        @Override public synchronized void close() { if (!this.closed) { this.closed = true; this.includes.values().forEach(CachedIncludeSource::close); this.includes.clear(); } }
    }
}
