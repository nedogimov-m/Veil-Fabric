package foundry.veil.api.client.render.shader;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonDeserializer;
import com.mojang.renderpearl.api.device.GpuDevice;
import com.mojang.renderpearl.api.pipeline.BindGroupLayout;
import com.mojang.renderpearl.api.pipeline.ColorTargetState;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import com.mojang.renderpearl.api.pipeline.UniformType;
import foundry.veil.api.client.render.gpu.GpuShaderProgram;
import foundry.veil.api.client.render.gpu.GpuShaderSource;
import foundry.veil.api.client.render.shader.program.ProgramDefinition;
import foundry.veil.api.client.render.shader.texture.ShaderTextureSource;
import foundry.veil.api.client.render.shader.texture.FramebufferSource;
import foundry.veil.api.client.render.shader.texture.LocationSource;
import foundry.veil.impl.client.render.shader.program.ShaderProgramImpl;
import foundry.veil.impl.client.render.shader.processor.NativeShaderProcessor;
import foundry.veil.api.event.VeilAddShaderPreProcessorsEvent;
import net.minecraft.resources.FileToIdConverter;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.SimplePreparableReloadListener;
import net.minecraft.util.profiling.ProfilerFiller;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.Closeable;
import java.io.Reader;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.function.Consumer;

/**
 * Resource-defined native shader program variants. Vertex layout/topology/output
 * formats and immutable render state are supplied by the actual draw pipeline;
 * the shader definition supplies stages, predefinitions, samplers and blend state.
 * Compilation failures never install a fake GL program or an empty shader.
 */
public class ShaderManager extends SimplePreparableReloadListener<Map<Identifier, ProgramDefinition>> implements Closeable {
    private static final Logger LOGGER = LoggerFactory.getLogger("Veil");
    private static final Gson GSON = new GsonBuilder()
            .registerTypeAdapter(Identifier.class, (JsonDeserializer<Identifier>) (json, type, context) -> Identifier.parse(json.getAsString()))
            .registerTypeAdapter(ProgramDefinition.class, new ProgramDefinition.Deserializer()).create();
    public static final FileToIdConverter INCLUDE_LISTER = new FileToIdConverter("pinwheel/shaders/include", ".glsl");
    public static final ShaderSourceSet PROGRAM_SET = new ShaderSourceSet("pinwheel/shaders/program");

    private final GpuDevice device;
    private final ShaderSourceSet sourceSet;
    private final ShaderPreDefinitions preDefinitions;
    private volatile Map<Identifier, ProgramDefinition> definitions = Map.of();
    private final Map<ProgramKey, GpuShaderProgram> programs = new HashMap<>();
    private final Set<Identifier> dirty = ConcurrentHashMap.newKeySet();
    private final List<Consumer<GpuShaderProgram>> globalSetters = new ArrayList<>();
    private final List<VeilAddShaderPreProcessorsEvent> processorRegistrars = new ArrayList<>();
    private int activeBuffers;
    private ResourceManager resources;

    public ShaderManager(GpuDevice device, ShaderSourceSet sourceSet, ShaderPreDefinitions preDefinitions) {
        this.device = java.util.Objects.requireNonNull(device);
        this.sourceSet = java.util.Objects.requireNonNull(sourceSet);
        this.preDefinitions = java.util.Objects.requireNonNull(preDefinitions);
        preDefinitions.addListener(changed -> this.definitions.forEach((id, definition) -> {
            if (java.util.Arrays.asList(definition.definitions()).contains(changed)) this.dirty.add(id);
        }));
        preDefinitions.addListener(changed -> this.programs.forEach((key, program) -> {
            if (program.getDefinitionDependencies().contains(changed)) this.dirty.add(key.name);
        }));
    }
    public void addPreprocessorRegistry(VeilAddShaderPreProcessorsEvent registrar) {
        this.processorRegistrars.add(java.util.Objects.requireNonNull(registrar)); this.dirty.addAll(this.definitions.keySet());
    }
    public void setActiveBuffers(int mask) {
        if (this.activeBuffers != mask) { this.activeBuffers = mask; this.dirty.addAll(this.definitions.keySet()); }
    }

    @Override protected Map<Identifier, ProgramDefinition> prepare(ResourceManager resources, ProfilerFiller profiler) {
        Map<Identifier, ProgramDefinition> result = new HashMap<>();
        this.sourceSet.getShaderDefinitionLister().listMatchingResources(resources).forEach((file, resource) -> {
            Identifier id = this.sourceSet.getShaderDefinitionLister().fileToId(file);
            try (Reader reader = resource.openAsReader()) {
                ProgramDefinition definition = GSON.fromJson(reader, ProgramDefinition.class);
                if (definition == null || definition.shaders().isEmpty()) throw new IllegalArgumentException("No shader stages declared");
                result.put(id, definition);
            } catch (Exception failure) { LOGGER.error("Cannot load Veil shader definition {}", id, failure); }
        });
        return result;
    }

    @Override protected void apply(Map<Identifier, ProgramDefinition> definitions, ResourceManager resources, ProfilerFiller profiler) {
        var replacements = new HashMap<ProgramKey, GpuShaderProgram>();
        try {
            for (ProgramKey key : this.programs.keySet()) {
                ProgramDefinition definition = definitions.get(key.name);
                if (definition != null) replacements.put(key, this.compile(key, definition, resources, Runnable::run));
            }
        } catch (RuntimeException | Error failure) {
            replacements.values().forEach(GpuShaderProgram::close);
            throw failure;
        }
        this.closePrograms();
        this.programs.putAll(replacements);
        this.definitions = Map.copyOf(definitions);
        this.resources = resources;
        LOGGER.info("Loaded {} native shader program definitions", definitions.size());
    }

    /** Uses the caller's real native pipeline state; repeated draws reuse this variant. */
    public GpuShaderProgram getShader(Identifier id, RenderPipeline drawState, Executor workers) {
        if (this.resources == null) throw new IllegalStateException("Shader resources have not reloaded yet");
        ProgramDefinition definition = this.definitions.get(id);
        if (definition == null) throw new IllegalArgumentException("Unknown Veil shader definition: " + id);
        ProgramKey requested = new ProgramKey(id, drawState);
        if (this.dirty.remove(id)) {
            var replacements = new HashMap<ProgramKey, GpuShaderProgram>();
            try {
                for (ProgramKey key : this.programs.keySet()) {
                    if (key.name.equals(id)) replacements.put(key, this.compile(key, definition, this.resources, workers));
                }
                if (!replacements.containsKey(requested)) replacements.put(requested, this.compile(requested, definition, this.resources, workers));
            } catch (RuntimeException | Error failure) {
                replacements.values().forEach(GpuShaderProgram::close);
                // Keep every last working variant. Another explicit edit/reload may retry;
                // do not recompile the same invalid source once per frame.
                throw failure;
            }
            replacements.forEach((key, replacement) -> {
                GpuShaderProgram previous = this.programs.put(key, replacement);
                if (previous != null) previous.close();
            });
        }
        return this.programs.computeIfAbsent(requested, key -> this.compile(key, definition, this.resources, workers));
    }

    private GpuShaderProgram compile(ProgramKey key, ProgramDefinition definition, ResourceManager resources, Executor workers) {
        if (definition.vertex() == null || definition.fragment() == null || definition.compute() != null
                || definition.geometry() != null || definition.tesselationControl() != null || definition.tesselationEvaluation() != null) {
            throw new UnsupportedOperationException("Native RenderPearl programs need migrated vertex+fragment stages: " + key.name);
        }
        for (ShaderFeature feature : definition.requiredFeatures()) {
            if (!feature.isSupported()) throw new UnsupportedOperationException("Shader feature needs an explicit native algorithm migration: " + feature + " in " + key.name);
        }
        RenderPipeline state = key.state;
        var defines = new RenderPipeline.Snippet(Map.of(), Optional.of(state.getShaderDefines()), Optional.empty(),
                new ColorTargetState[0], 0, Optional.empty(), Optional.empty(), Optional.empty(),
                new com.mojang.renderpearl.api.vertex.VertexFormat[0], Optional.empty(), 0);
        var builder = RenderPipeline.builder(defines).withLocation(key.name)
                .withVertexShader(definition.vertex()).withFragmentShader(definition.fragment())
                .withPrimitiveTopology(state.getPrimitiveTopology()).withPolygonMode(state.getPolygonMode())
                .withCull(state.isCull()).withDepthStencilState(Optional.ofNullable(state.getDepthStencilState()))
                .withPushConstantSize(state.pushConstantSize());
        for (int index = 0; index < state.getVertexFormatBindings().size(); index++) builder.withVertexBinding(index, state.getVertexFormatBinding(index));
        for (int index = 0; index < state.getColorTargetStates().size(); index++) {
            ColorTargetState target = state.getColorTargetStates().get(index);
            if (target == null) builder.withUnusedColorTargetState(index);
            else builder.withColorTargetState(index, definition.blendMode() == null ? target
                    : new ColorTargetState(Optional.of(definition.blendMode().toBlendFunction()), target.format(), target.writeMask()));
        }
        Set<String> bindings = new HashSet<>();
        state.getBindGroupLayouts().forEach(layout -> {
            builder.withBindGroupLayout(layout);
            layout.uniforms().forEach(uniform -> bindings.add(uniform.name()));
        });
        var extraSamplers = BindGroupLayout.builder();
        int extra = 0;
        for (String block : definition.uniformBlocks()) {
            if (bindings.add(block)) { extraSamplers.withUniform(block, UniformType.UNIFORM_BUFFER); extra++; }
            else if (state.getBindGroupLayouts().stream().flatMap(layout -> layout.uniforms().stream())
                    .anyMatch(uniform -> uniform.name().equals(block) && uniform.type() != UniformType.UNIFORM_BUFFER))
                throw new IllegalArgumentException("Native uniform block conflicts with draw binding: " + block);
        }
        for (String sampler : definition.samplers().keySet()) {
            if (bindings.add(sampler)) { extraSamplers.withUniform(sampler, UniformType.COMBINED_IMAGE_SAMPLER); extra++; }
        }
        if (extra > 0) builder.withBindGroupLayout(extraSamplers.build());
        Set<String> dependencies = new HashSet<>();
        var macros = new HashMap<>(state.getShaderDefines().values());
        state.getShaderDefines().flags().forEach(name -> macros.put(name, ""));
        macros.putAll(definition.getMacros(dependencies, this.preDefinitions));
        NativeShaderProcessor.addDeviceMacros(this.device, macros);
        GpuShaderSource.Processor processor = new NativeShaderProcessor(resources, this.preDefinitions, definition, macros,
                dependencies, 0, List.copyOf(this.processorRegistrars));
        try (var source = new GpuShaderSource(resources, this.sourceSet.getFolder(), "pinwheel/shaders/include", macros, processor)) {
            GpuShaderProgram program = new ShaderProgramImpl(this.device, builder.build(), source, workers, definition, dependencies);
            try { this.globalSetters.forEach(setter -> setter.accept(program)); return program; }
            catch (RuntimeException | Error failure) { program.close(); throw failure; }
        }
    }

    /** Resolve dynamic framebuffer views afresh after resize; samplers are cached/owned by the program. */
    public void bindTextures(Identifier shader, GpuShaderProgram program, ShaderTextureSource.Context context) {
        ProgramDefinition definition = this.definitions.get(shader);
        if (definition == null) throw new IllegalArgumentException("Unknown shader: " + shader);
        definition.samplers().forEach((name, source) -> {
            var texture = source.getTexture(context);
            if (source.filter() != null) program.setTexture(name, texture, source.filter());
            else if (source instanceof FramebufferSource framebuffer) {
                program.setTexture(name, texture, context.framebufferFilter(framebuffer.name(), framebuffer.sampler(), framebuffer.depth()));
            } else if (source instanceof LocationSource location) program.setTexture(name, texture, context.getSampler(location.location()));
        });
    }

    public void setGlobal(Consumer<GpuShaderProgram> setter) {
        this.globalSetters.add(java.util.Objects.requireNonNull(setter));
        this.programs.values().forEach(setter);
    }
    public ShaderSourceSet getSourceSet() { return this.sourceSet; }
    public Map<Identifier, ProgramDefinition> getDefinitions() { return Collections.unmodifiableMap(this.definitions); }
    /** Inspection observes only already compiled live variants; it never creates one. */
    public List<GpuShaderProgram> getPrograms() { return List.copyOf(this.programs.values()); }
    /** Reverse lookup is by the actual immutable native definition, never a backend integer ID. */
    public @org.jetbrains.annotations.Nullable GpuShaderProgram findShader(RenderPipeline pipeline) {
        for (GpuShaderProgram program : this.programs.values()) if (program.definition() == pipeline) return program;
        return null;
    }
    public boolean hasFeatures(ShaderFeature... features) { return java.util.Arrays.stream(features).allMatch(ShaderFeature::isSupported); }
    public void scheduleRecompile(Identifier shader) { this.dirty.add(shader); }
    public void scheduleRecompileForSource(int type, Identifier source) {
        this.definitions.forEach((name, definition) -> { if (source.equals(definition.shaders().get(type))) this.dirty.add(name); });
    }
    public void scheduleRecompileForInclude(Identifier include) {
        Identifier normalized = normalizeInclude(include);
        this.programs.forEach((key, program) -> {
            if (program.includedResources().stream().map(ShaderManager::normalizeInclude).anyMatch(normalized::equals)) this.dirty.add(key.name);
        });
    }
    private static Identifier normalizeInclude(Identifier id) {
        String path = id.getPath();
        return path.endsWith(".glsl") ? Identifier.fromNamespaceAndPath(id.getNamespace(), path.substring(0, path.length() - 5)) : id;
    }
    /** Release a draw owner's variant when its immutable render state is retired. */
    public void releaseShader(Identifier shader, RenderPipeline drawState) {
        GpuShaderProgram program = this.programs.remove(new ProgramKey(shader, drawState));
        if (program != null) program.close();
    }
    public boolean hasPendingRecompile() { return !this.dirty.isEmpty(); }
    public String getName() { return "Veil native shader program definitions"; }
    private void closePrograms() { this.programs.values().forEach(GpuShaderProgram::close); this.programs.clear(); this.dirty.clear(); }
    @Override public void close() { this.closePrograms(); this.definitions = Map.of(); this.resources = null; }
    private record ProgramKey(Identifier name, RenderPipeline state) { }
}
