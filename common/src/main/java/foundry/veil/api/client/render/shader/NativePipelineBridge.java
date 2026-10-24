package foundry.veil.api.client.render.shader;

import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.api.commands.CommandEncoder;
import foundry.veil.impl.client.render.shader.injection.NativeShaderInjectionService;
import foundry.veil.impl.client.render.shader.injection.NativeShaderInjectionFrames;
import com.mojang.renderpearl.api.device.GpuDevice;
import com.mojang.renderpearl.api.pipeline.*;
import com.mojang.renderpearl.api.textures.GpuSampler;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import com.mojang.renderpearl.api.vertex.VertexFormat;
import foundry.veil.api.client.render.shader.processor.ShaderPreProcessor;
import foundry.veil.api.event.VeilAddShaderPreProcessorsEvent;
import foundry.veil.impl.client.render.shader.debug.NativePipelineRegistry;
import foundry.veil.impl.client.render.shader.processor.NativeShaderProcessor;
import foundry.veil.impl.client.render.shader.ShaderImporterImpl;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.ResourceProvider;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.function.*;

/**
 * Adds explicit typed resources and source modifications to real native world pipelines.
 * Register during client initialization, before shader-resource loading. Normal native caches
 * retain/retire the genuine compiled pipelines. Resource snapshots are borrowed until submission.
 */
public final class NativePipelineBridge {
    private static final Map<Identifier, Extension> EXTENSIONS = new LinkedHashMap<>();
    private static final Map<ShaderSource, ResourceProvider> SOURCES = new WeakHashMap<>();
    private static final Map<CompiledRenderPipeline, Compiled> COMPILED = new WeakHashMap<>();
    private static final Map<CompiledRenderPipeline, SourceInfo> INPUTS = new WeakHashMap<>();
    private static ShaderPreDefinitions definitions = new ShaderPreDefinitions();
    private static NativeShaderInjectionService injectionService;
    private static List<VeilAddShaderPreProcessorsEvent> globalProcessors = List.of();
    private static long sourceRevision;
    private static Object injectionGeneration;
    private static final Map<RenderPass, CommandEncoder> PASSES = new IdentityHashMap<>();
    private static final Map<RenderPass, Compiled> BOUND = new IdentityHashMap<>();
    private static final Map<CommandEncoder, List<AutoCloseable>> SUBMISSIONS = new IdentityHashMap<>();
    public static synchronized void bindRuntime(ShaderPreDefinitions preDefinitions) {
        bindRuntime(preDefinitions, null, List.of());
    }
    public static synchronized void bindRuntime(ShaderPreDefinitions preDefinitions, NativeShaderInjectionService injections,
                                                List<VeilAddShaderPreProcessorsEvent> processors) {
        if (injectionService != null && injectionService != injections) NativeShaderInjectionService.unbindRuntime(injectionService);
        if (injections != null) NativeShaderInjectionService.bindRuntime(injections);
        definitions = Objects.requireNonNull(preDefinitions); injectionService = injections; globalProcessors = List.copyOf(processors);
        sourceRevision++; injectionGeneration = NativeShaderInjectionService.runtimeSnapshot();
        preDefinitions.addListener(changed -> {
            synchronized (NativePipelineBridge.class) { if (definitions == preDefinitions) sourceRevision++; }
        });
    }
    public static synchronized void unbindRuntime() {
        if (injectionService != null) NativeShaderInjectionService.unbindRuntime(injectionService);
        definitions = new ShaderPreDefinitions(); injectionService = null; globalProcessors = List.of(); sourceRevision++;
    }
    /** Native caches retire real owners when source settings change, then compile from their genuine raw source. */
    public static synchronized long sourceRevision() {
        Object generation = NativeShaderInjectionService.runtimeSnapshot();
        if (generation != injectionGeneration) { injectionGeneration = generation; sourceRevision++; }
        return sourceRevision;
    }
    public static synchronized void register(Identifier id, Predicate<RenderPipeline> matches, Map<String, UniformType> bindings,
            Supplier<? extends ShaderPreProcessor> processors, Function<RenderPipeline, Bindings> resources) {
        Objects.requireNonNull(id); Objects.requireNonNull(matches); Objects.requireNonNull(processors); Objects.requireNonNull(resources);
        TreeMap<String, UniformType> declarations = new TreeMap<>(bindings);
        declarations.forEach((name, type) -> {
            if (!name.matches("[A-Za-z_][A-Za-z0-9_]*")) throw new IllegalArgumentException("Invalid native resource name: " + name);
            if (type != UniformType.UNIFORM_BUFFER && type != UniformType.COMBINED_IMAGE_SAMPLER) throw new IllegalArgumentException("Extension resource needs a UBO or texture/sampler: " + name);
        });
        if (EXTENSIONS.putIfAbsent(id, new Extension(id, matches, Collections.unmodifiableMap(declarations), processors, resources)) != null)
            throw new IllegalStateException("Native pipeline extension already registered: " + id);
    }
    /** Called by the real vanilla source-loader return injection; the source owns its native includes. */
    public static synchronized void sourceLoaded(ShaderSource source, ResourceProvider resources) { SOURCES.put(Objects.requireNonNull(source), Objects.requireNonNull(resources)); }
    /** Record direct Veil owners before their caller-owned source closes; only CPU text is retained. */
    public static void directCompiled(RenderPipeline definition, CompiledRenderPipeline compiled, ShaderSource source) {
        ResourceProvider resources; ShaderPreDefinitions preDefinitions;
        synchronized (NativePipelineBridge.class) { resources = SOURCES.get(source); preDefinitions = definitions; }
        if (resources == null) return;
        NativeShaderProcessor template = template(source);
        Map<SourceKey, String> raw = capture(definition, source, template);
        synchronized (NativePipelineBridge.class) {
            INPUTS.put(compiled, new SourceInfo(definition, definition, definition, raw, resources, preDefinitions, List.of(), template, null));
            if (template != null && !template.generatedUniformBlocks().isEmpty())
                COMPILED.put(compiled, new Compiled(definition, definition, List.of(), null, template.generatedUniformBlocks()));
        }
    }
    private static NativeShaderProcessor template(ShaderSource source) {
        return source instanceof foundry.veil.api.client.render.gpu.GpuShaderSource managed
                && managed.processor() instanceof NativeShaderProcessor processor ? processor : null;
    }
    /** Expand source-block directives before a direct program freezes its native binding schema. */
    public static RenderPipeline prepareDirectDefinition(RenderPipeline definition, ShaderSource source) {
        NativeShaderProcessor processor = template(source);
        if (processor == null) return definition;
        ((foundry.veil.api.client.render.gpu.GpuShaderSource) source).prepare(definition);
        for (ShaderType stage : List.of(ShaderType.VERTEX, ShaderType.FRAGMENT)) {
            Identifier id = definition.getShaders().get(stage); if (id != null) source.getShader(id, stage);
        }
        return generatedBindings(definition, processor);
    }
    private static Set<String> requiredUniformBlocks(NativeShaderProcessor processor) {
        var names = new HashSet<>(processor.generatedUniformBlocks()); names.addAll(processor.automaticUniformLayouts().keySet()); names.addAll(processor.nativeUniformBlocks()); return Set.copyOf(names);
    }
    public static Map<String, foundry.veil.api.client.render.gpu.GpuUniformBuffer.Layout> automaticUniformLayouts(ShaderSource source) {
        NativeShaderProcessor processor = template(source); return processor == null ? Map.of() : processor.automaticUniformLayouts();
    }
    private static RenderPipeline generatedBindings(RenderPipeline definition, NativeShaderProcessor processor) {
        var names = new TreeMap<String, UniformType>();
        requiredUniformBlocks(processor).forEach(name -> names.put(name, UniformType.UNIFORM_BUFFER));
        processor.automaticSamplerNames().forEach(name -> {
            if (names.putIfAbsent(name, UniformType.COMBINED_IMAGE_SAMPLER) != null)
                throw new IllegalArgumentException("Managed sampled image conflicts with a generated uniform block: " + name);
        });
        if (names.isEmpty()) return definition;
        var declared = new HashMap<String, UniformType>();
        BindGroupLayout.flattenUniforms(definition.getBindGroupLayouts()).forEach(uniform -> declared.put(uniform.name(), uniform.type()));
        var extra = BindGroupLayout.builder(); int added = 0;
        for (var binding : names.entrySet()) {
            UniformType previous = declared.putIfAbsent(binding.getKey(), binding.getValue());
            if (previous != null && previous != binding.getValue()) throw new IllegalArgumentException("Generated source resource conflicts with native binding: " + binding.getKey());
            if (previous == null) { extra.withUniform(binding.getKey(), binding.getValue()); added++; }
        }
        if (added == 0) return definition;
        var snippet = new RenderPipeline.Snippet(definition.getShaders(), Optional.of(definition.getShaderDefines()), Optional.of(definition.getBindGroupLayouts()),
                definition.getColorTargetStates().toArray(ColorTargetState[]::new), definition.getColorTargetStates().size(),
                Optional.ofNullable(definition.getDepthStencilState()), Optional.of(definition.getPolygonMode()), Optional.of(definition.isCull()),
                definition.getVertexFormatBindings().toArray(VertexFormat[]::new), Optional.of(definition.getPrimitiveTopology()), definition.pushConstantSize());
        return RenderPipeline.builder(snippet).withLocation(definition.getLocation()).withBindGroupLayout(extra.build()).build();
    }
    public static Set<String> generatedUniformBlocks(ShaderSource source) {
        NativeShaderProcessor processor = template(source); return processor == null ? Set.of() : processor.generatedUniformBlocks();
    }
    private static Map<SourceKey, String> capture(RenderPipeline definition, ShaderSource source, NativeShaderProcessor template) {
        Map<SourceKey, String> raw = new HashMap<>();
        definition.getShaders().forEach((stage, id) -> {
            String value = template != null && source instanceof foundry.veil.api.client.render.gpu.GpuShaderSource managed
                    ? managed.rawShader(id, stage) : source.getShader(id, stage);
            if (value != null) raw.put(new SourceKey(id, stage), value);
        });
        return Map.copyOf(raw);
    }
    @FunctionalInterface public interface Compiler { CompletableFuture<CompiledRenderPipeline.Pending> compile(RenderPipeline pipeline, ShaderSource source, Executor workers); }
    public static CompletableFuture<CompiledRenderPipeline.Pending> compile(GpuDevice device, RenderPipeline original, ShaderSource source, Executor workers, Compiler compiler) {
        List<Extension> extensions; ResourceProvider provider; ShaderPreDefinitions preDefinitions;
        NativeShaderInjectionService injections; List<VeilAddShaderPreProcessorsEvent> globals;
        synchronized (NativePipelineBridge.class) {
            extensions = List.copyOf(EXTENSIONS.values()); provider = SOURCES.get(source); preDefinitions = definitions;
            injections = injectionService; globals = globalProcessors;
        }
        List<Extension> selected = extensions.stream().filter(extension -> extension.matches.test(original)).toList();
        NativeShaderInjectionService.Plan plan = injections == null ? null
                : injections.plan(original, source, Objects.requireNonNull(provider, "Native injected source requires a resource owner")).orElse(null);
        NativeShaderProcessor sourceTemplate = template(source);
        RenderPipeline sourceDefinition = plan == null ? original : plan.pipeline();
        Map<SourceKey, String> raw;
        if (plan == null) raw = provider == null ? Map.of() : capture(sourceDefinition, source, sourceTemplate);
        else try (ShaderSource replacement = plan.source()) { raw = capture(sourceDefinition, replacement, null); }
        if (selected.isEmpty() && globals.isEmpty() && plan == null && NativeShaderBlocks.layoutNames().isEmpty()
                && !NativeShaderDefaults.isAvailable()) {
            SourceInfo input = new SourceInfo(original, original, sourceDefinition, raw, provider, preDefinitions, selected, sourceTemplate, null);
            return compiler.compile(original, source, workers).thenApply(pending -> () -> {
                CompiledRenderPipeline pipeline = pending.finishCompile();
                if (pipeline != null) synchronized (NativePipelineBridge.class) { INPUTS.put(pipeline, input); }
                return pipeline;
            });
        }
        if (provider == null) throw new IllegalStateException("Native shader source has no resource owner: " + original.getLocation());
        ResourceProvider resources = plan == null ? provider : plan.resources();
        Map<String, UniformType> names = new HashMap<>();
        BindGroupLayout.flattenUniforms(sourceDefinition.getBindGroupLayouts()).forEach(uniform -> names.put(uniform.name(), uniform.type()));
        var extra = BindGroupLayout.builder(); int declarations = 0;
        for (Extension extension : selected) for (var binding : extension.bindings.entrySet()) {
            if (names.putIfAbsent(binding.getKey(), binding.getValue()) != null) throw new IllegalArgumentException("Native extension binding collides with another pipeline resource: " + binding.getKey());
            extra.withUniform(binding.getKey(), binding.getValue()); declarations++;
        }
        RenderPipeline modified = sourceDefinition;
        if (declarations != 0) {
            var snippet = new RenderPipeline.Snippet(sourceDefinition.getShaders(), Optional.of(sourceDefinition.getShaderDefines()), Optional.of(sourceDefinition.getBindGroupLayouts()),
                    sourceDefinition.getColorTargetStates().toArray(ColorTargetState[]::new), sourceDefinition.getColorTargetStates().size(),
                    Optional.ofNullable(sourceDefinition.getDepthStencilState()), Optional.of(sourceDefinition.getPolygonMode()), Optional.of(sourceDefinition.isCull()),
                    sourceDefinition.getVertexFormatBindings().toArray(VertexFormat[]::new), Optional.of(sourceDefinition.getPrimitiveTopology()), sourceDefinition.pushConstantSize());
            modified = RenderPipeline.builder(snippet).withLocation(original.getLocation()).withBindGroupLayout(extra.build()).build();
        }
        var registrars = new ArrayList<>(globals);
        if (plan != null) registrars.addAll(plan.registrars());
        selected.forEach(extension -> registrars.add((resources1, registry) -> registry.addPreprocessor(Objects.requireNonNull(extension.processors.get()), false)));
        NativeShaderProcessor processor;
        if (plan != null && plan.replacement().isPresent()) processor = NativeShaderProcessor.forMinecraftProgram(device, resources, preDefinitions,
                plan.replacement().orElseThrow().definition(), sourceDefinition, 0, registrars);
        else processor = sourceTemplate == null ? NativeShaderProcessor.forMinecraft(device, resources, preDefinitions, sourceDefinition, 0, registrars)
                : sourceTemplate.forVariant(sourceDefinition, 0, registrars);
        SourceInfo input = new SourceInfo(original, modified, sourceDefinition, raw, resources, preDefinitions, selected, processor, plan);
        DetachedSource detached = new DetachedSource(input, processor);
        RenderPipeline actual = generatedBindings(modified, processor);
        CompletableFuture<CompiledRenderPipeline.Pending> compilation;
        try { compilation = compiler.compile(actual, detached, workers); }
        catch (RuntimeException | Error failure) { detached.close(); throw failure; }
        return compilation.whenComplete((pending, failure) -> { if (failure != null) detached.close(); }).thenApply(pending -> () -> {
            try {
                CompiledRenderPipeline pipeline = pending.finishCompile();
                if (pipeline != null) {
                    synchronized (NativePipelineBridge.class) {
                        COMPILED.put(pipeline, new Compiled(original, actual, selected, plan, processor.generatedUniformBlocks()));
                        INPUTS.put(pipeline, new SourceInfo(input.original, actual, input.sourceDefinition, input.raw, input.resources,
                                input.definitions, input.extensions, input.template, input.plan));
                    }
                    NativePipelineRegistry.observe(actual, pipeline);
                }
                return pipeline;
            } finally { detached.close(); }
        });
    }
    /**
     * Compile an independently owned real native variant before opening its consuming pass.
     * The ordinary native cache retains its base pipeline. Shader source text is detached on this
     * call, so a later native cache reload cannot invalidate asynchronous compiler include memory.
     * Finish the returned Pending on the render thread and retain the resulting owner through submission.
     */
    public static CompletableFuture<CompiledRenderPipeline.Pending> compileVariant(GpuDevice device, CompiledRenderPipeline base,
            RenderPipeline variant, int buffers, List<VeilAddShaderPreProcessorsEvent> extraProcessors, Executor workers) {
        return compileVariant(device, base, variant, buffers, extraProcessors, workers, UnaryOperator.identity());
    }
    /** Finalize immutable render state after synchronous CPU preprocessing reveals the produced outputs. */
    public static CompletableFuture<CompiledRenderPipeline.Pending> compileVariant(GpuDevice device, CompiledRenderPipeline base,
            RenderPipeline variant, int buffers, List<VeilAddShaderPreProcessorsEvent> extraProcessors, Executor workers,
            UnaryOperator<RenderPipeline> finalizeState) {
        SourceInfo input;
        synchronized (NativePipelineBridge.class) { input = INPUTS.get(base); }
        if (base.isClosed() || input == null || input.resources == null) throw new IllegalArgumentException("Native pipeline has no live source owner for a derived variant");
        if (!variant.getShaders().equals(input.actual.getShaders()) || !variant.getShaderDefines().equals(input.actual.getShaderDefines())
                || !variant.getVertexFormatBindings().equals(input.actual.getVertexFormatBindings()))
            throw new IllegalArgumentException("Native variants must preserve the source shader/define/vertex contract");
        var registrars = new ArrayList<VeilAddShaderPreProcessorsEvent>();
        if (input.template == null) input.extensions.forEach(extension -> registrars.add((resources, registry) -> registry.addPreprocessor(Objects.requireNonNull(extension.processors.get()), false)));
        registrars.addAll(extraProcessors);
        var processor = input.template == null ? NativeShaderProcessor.forMinecraft(device, input.resources, input.definitions, input.sourceDefinition, buffers, registrars)
                : input.template.forVariant(input.sourceDefinition, buffers, registrars);
        var detached = new DetachedSource(input, processor);
        RenderPipeline actual;
        try {
            if (input.template != null && !input.template.automaticUniformLayouts().equals(processor.automaticUniformLayouts()))
                throw new IllegalArgumentException("Mask-dependent owned uniform schemas require an explicit stable named block");
            actual = generatedBindings(Objects.requireNonNull(finalizeState.apply(variant)), processor);
            if (!actual.getShaders().equals(variant.getShaders()) || !actual.getShaderDefines().equals(variant.getShaderDefines())
                    || !actual.getVertexFormatBindings().equals(variant.getVertexFormatBindings()))
                throw new IllegalArgumentException("Final native render state must preserve the shader/define/vertex contract");
        }
        catch (RuntimeException | Error failure) { detached.close(); throw failure; }
        CompletableFuture<CompiledRenderPipeline.Pending> compilation;
        try { compilation = device.compilePipeline(actual, detached, workers); }
        catch (RuntimeException | Error failure) { detached.close(); throw failure; }
        return compilation.whenComplete((pending, failure) -> { if (failure != null) detached.close(); }).thenApply(pending -> () -> {
            try {
                CompiledRenderPipeline pipeline = pending.finishCompile();
                if (pipeline != null) {
                    synchronized (NativePipelineBridge.class) {
                        COMPILED.put(pipeline, new Compiled(input.original, actual, input.extensions, input.plan, processor.generatedUniformBlocks()));
                        INPUTS.put(pipeline, new SourceInfo(input.original, actual, input.sourceDefinition, input.raw, input.resources, input.definitions, input.extensions, input.template, input.plan));
                    }
                    NativePipelineRegistry.observe(actual, pipeline);
                }
                return pipeline;
            } finally { detached.close(); }
        });
    }
    public static synchronized Optional<RenderPipeline> originalDefinition(CompiledRenderPipeline pipeline) {
        SourceInfo input = INPUTS.get(pipeline); return input == null ? Optional.empty() : Optional.of(input.original);
    }
    /** Runs only after the real frontend pass selected its genuine compiled pipeline. No upload/encoder is created here. */
    public static void bind(RenderPass pass, CompiledRenderPipeline pipeline) {
        Compiled metadata;
        synchronized (NativePipelineBridge.class) {
            metadata = COMPILED.get(pipeline);
            if (metadata == null) BOUND.remove(pass); else BOUND.put(pass, metadata);
        }
        if (metadata == null) return;
        if (!metadata.generatedBlocks.isEmpty()) {
            AutoCloseable lease = NativeShaderInjectionFrames.retainNamedFrame().orElseThrow(() -> new IllegalStateException("Prepare native source-block snapshots before drawing: " + metadata.generatedBlocks));
            try {
                NativeShaderInjectionFrames.bindNamed(pass, metadata.generatedBlocks);
                synchronized (NativePipelineBridge.class) {
                    CommandEncoder encoder = PASSES.get(pass);
                    if (encoder == null) throw new IllegalStateException("Native source-block pass is missing its real encoder owner");
                    SUBMISSIONS.computeIfAbsent(encoder, ignored -> new ArrayList<>()).add(lease); lease = null;
                }
            } finally { if (lease != null) close(lease); }
        }
        if (metadata.plan != null) {
            AutoCloseable lease = metadata.plan.retainFrame().orElse(null);
            try {
                metadata.plan.bind(pass);
                if (lease != null) {
                    synchronized (NativePipelineBridge.class) {
                        CommandEncoder encoder = PASSES.get(pass);
                        if (encoder == null) throw new IllegalStateException("Native replacement pass is missing its real encoder owner");
                        SUBMISSIONS.computeIfAbsent(encoder, ignored -> new ArrayList<>()).add(lease); lease = null;
                    }
                }
            } finally { if (lease != null) close(lease); }
        }
        for (Extension extension : metadata.extensions) {
            Bindings values = Objects.requireNonNull(extension.resources.apply(metadata.original), "Missing native extension resources: " + extension.id);
            Set<String> provided = new HashSet<>(); provided.addAll(values.uniforms.keySet()); provided.addAll(values.textures.keySet());
            if (!provided.equals(extension.bindings.keySet())) throw new IllegalStateException("Native resource snapshot does not match declarations: " + extension.id);
            extension.bindings.forEach((name, type) -> {
                if (type == UniformType.UNIFORM_BUFFER) {
                    GpuBufferSlice uniform = values.uniforms.get(name);
                    if (uniform == null || uniform.buffer().isClosed() || (uniform.buffer().usage() & GpuBuffer.USAGE_UNIFORM) == 0) throw new IllegalStateException("Missing/live native uniform slice required: " + name);
                    pass.setUniform(name, uniform);
                } else {
                    Texture texture = values.textures.get(name);
                    if (texture == null || texture.view.isClosed() || texture.sampler.isClosed()) throw new IllegalStateException("Missing/live native texture and sampler required: " + name);
                    pass.setUniform(name, texture.view, texture.sampler);
                }
            });
        }
    }
    /** Apply explicit replacement resources after the caller has supplied ordinary native draw bindings. */
    public static void beforeDraw(RenderPass pass) {
        Compiled metadata; synchronized (NativePipelineBridge.class) { metadata = BOUND.get(pass); }
        if (metadata != null) {
            if (!metadata.generatedBlocks.isEmpty()) NativeShaderInjectionFrames.bindNamed(pass, metadata.generatedBlocks);
            if (metadata.plan != null) metadata.plan.bind(pass);
        }
    }
    public static synchronized Optional<RenderPipeline> actualDefinition(CompiledRenderPipeline pipeline) { Compiled compiled = COMPILED.get(pipeline); return compiled == null ? Optional.empty() : Optional.of(compiled.modified); }
    public record Texture(GpuTextureView view, GpuSampler sampler) { public Texture { Objects.requireNonNull(view); Objects.requireNonNull(sampler); } }
    public record Bindings(Map<String, GpuBufferSlice> uniforms, Map<String, Texture> textures) {
        public Bindings { uniforms = Map.copyOf(uniforms); textures = Map.copyOf(textures); }
    }
    private record Extension(Identifier id, Predicate<RenderPipeline> matches, Map<String, UniformType> bindings,
                             Supplier<? extends ShaderPreProcessor> processors, Function<RenderPipeline, Bindings> resources) { }
    private record Compiled(RenderPipeline original, RenderPipeline modified, List<Extension> extensions, NativeShaderInjectionService.Plan plan,
                            Set<String> generatedBlocks) { }
    private record SourceInfo(RenderPipeline original, RenderPipeline actual, RenderPipeline sourceDefinition, Map<SourceKey, String> raw, ResourceProvider resources,
                              ShaderPreDefinitions definitions, List<Extension> extensions, NativeShaderProcessor template, NativeShaderInjectionService.Plan plan) { }
    public static synchronized void opened(RenderPass pass, CommandEncoder encoder) { PASSES.put(pass, encoder); }
    public static synchronized void closed(RenderPass pass) { PASSES.remove(pass); BOUND.remove(pass); }
    public static void submitted(CommandEncoder encoder) {
        List<AutoCloseable> leases; synchronized (NativePipelineBridge.class) { leases = SUBMISSIONS.remove(encoder); }
        if (leases != null) leases.forEach(NativePipelineBridge::close);
    }
    /** Flush captured replacement snapshots while their native device still exists, before closing the runtime service. */
    public static void flush() {
        List<CommandEncoder> encoders;
        synchronized (NativePipelineBridge.class) {
            encoders = List.copyOf(SUBMISSIONS.keySet());
            if (PASSES.values().stream().anyMatch(encoders::contains))
                throw new IllegalStateException("Close native shader passes before shutting down their resource frames");
        }
        encoders.forEach(CommandEncoder::submit);
    }
    private static void close(AutoCloseable lease) {
        try { lease.close(); } catch (RuntimeException | Error failure) { throw failure; }
        catch (Exception failure) { throw new IllegalStateException("Cannot retire native shader frame resources", failure); }
    }
    /** Owns real include-cache allocations; the raw native source is consumed only during CPU snapshot preparation. */
    private static final class DetachedSource implements ShaderSource {
        private final Map<SourceKey, String> shaders = new HashMap<>();
        private final Map<Identifier, CachedIncludeSource> includes = new HashMap<>();
        private final ShaderImporterImpl importer;
        private boolean closed;
        DetachedSource(SourceInfo input, NativeShaderProcessor processor) {
            this.importer = new ShaderImporterImpl(input.resources);
            var processed = processor.processProgram(input.sourceDefinition.getShaders(),
                    stage -> input.raw.get(new SourceKey(input.sourceDefinition.getShaders().get(stage), stage)));
            processed.forEach((stage, source) -> this.shaders.put(new SourceKey(input.sourceDefinition.getShaders().get(stage), stage), source));
        }
        @Override public synchronized String getShader(Identifier id, ShaderType stage) { this.ensureOpen(); return this.shaders.get(new SourceKey(id, stage)); }
        @Override public synchronized CachedIncludeSource getInclude(Identifier id) {
            this.ensureOpen();
            return this.includes.computeIfAbsent(id, key -> {
                try { return CachedIncludeSource.create(key, this.importer.loadSource(key)); }
                catch (java.io.IOException failure) { return CachedIncludeSource.createError(failure.getMessage()); }
            });
        }
        private void ensureOpen() { if (this.closed) throw new IllegalStateException("Native variant source is closed"); }
        @Override public synchronized void close() {
            if (this.closed) return; this.closed = true;
            this.includes.values().forEach(CachedIncludeSource::close); this.includes.clear(); this.shaders.clear();
        }
    }
    private record SourceKey(Identifier id, ShaderType stage) { }
    private NativePipelineBridge() { }
}
