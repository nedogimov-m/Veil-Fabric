package foundry.veil.impl.client.render.shader.processor;

import com.mojang.renderpearl.api.pipeline.ShaderType;
import com.mojang.renderpearl.api.device.GpuDevice;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import com.mojang.renderpearl.api.vertex.VertexFormat;
import foundry.veil.api.client.render.gpu.GpuShaderSource;
import foundry.veil.api.client.render.shader.ShaderPreDefinitions;
import foundry.veil.api.client.render.shader.processor.*;
import foundry.veil.api.client.render.shader.program.ProgramDefinition;
import foundry.veil.api.event.VeilAddShaderPreProcessorsEvent;
import io.github.ocelot.glslprocessor.api.GlslParser;
import io.github.ocelot.glslprocessor.api.node.GlslTree;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.ResourceProvider;
import java.util.*;

/** Runs the real extensible GLSL AST processors before native pipeline compilation. */
public final class NativeShaderProcessor implements GpuShaderSource.Processor {
    private final ShaderProcessorList processors;
    private final ResourceProvider resources;
    private final List<VeilAddShaderPreProcessorsEvent> registrars;
    private final ShaderPreDefinitions definitions;
    private final ProgramDefinition definition;
    private final RenderPipeline minecraftPipeline;
    private final Set<String> dependencies;
    private final Set<Identifier> includes = new HashSet<>();
    private final Map<String, Object> programData = new HashMap<>();
    private final Map<String, String> macros;
    private final int activeBuffers;
    private final Map<Identifier, foundry.veil.api.client.render.VeilShaderBufferLayout<?>> blockLayouts;
    private final Set<String> generatedUniformBlocks = new HashSet<>();
    private final Set<String> nativeUniformBlocks = new HashSet<>();
    private Map<String, foundry.veil.api.client.render.gpu.GpuUniformBuffer.Layout> automaticUniforms = Map.of();
    private Set<String> automaticSamplers = Set.of();
    public NativeShaderProcessor(ResourceProvider resources, ShaderPreDefinitions definitions, ProgramDefinition definition,
            Map<String, String> macros, Set<String> dependencies, int activeBuffers, List<VeilAddShaderPreProcessorsEvent> registrars) {
        this(resources, definitions, definition, null, macros, dependencies, activeBuffers, registrars,
                foundry.veil.api.client.render.shader.NativeShaderBlocks.snapshot());
    }
    private NativeShaderProcessor(ResourceProvider resources, ShaderPreDefinitions definitions, ProgramDefinition definition, RenderPipeline minecraftPipeline,
            Map<String, String> macros, Set<String> dependencies, int activeBuffers, List<VeilAddShaderPreProcessorsEvent> registrars,
            Map<Identifier, foundry.veil.api.client.render.VeilShaderBufferLayout<?>> blockLayouts) {
        this.resources = resources; this.registrars = List.copyOf(registrars);
        this.blockLayouts = Map.copyOf(blockLayouts);
        this.processors = new ShaderProcessorList(resources); this.definitions = definitions; this.definition = definition;
        this.minecraftPipeline = minecraftPipeline;
        this.macros = new HashMap<>(macros); this.dependencies = dependencies; this.activeBuffers = activeBuffers;
        for (var type : foundry.veil.api.client.render.dynamicbuffer.DynamicBufferType.values()) this.macros.remove(type.getMacroName());
        foundry.veil.api.client.render.dynamicbuffer.DynamicBufferType.addMacros(activeBuffers, this.macros);
        this.processors.addPreprocessor(new ShaderImportProcessor());
        this.processors.addPreprocessor(new ShaderVersionProcessor());
        registrars.forEach(registrar -> registrar.onRegisterShaderPreProcessors(resources, this.processors));
        this.processors.addPreprocessor(new ShaderBufferProcessor(this.blockLayouts, this.generatedUniformBlocks::add), true);
        this.processors.addPreprocessor(new NativeShaderDefaultsProcessor(this.generatedUniformBlocks::add, this.nativeUniformBlocks::add), false);
    }
    /** Fresh importer/data/processor instances replay the original managed definition before adding native variant processors. */
    public NativeShaderProcessor forVariant(RenderPipeline original, int buffers, List<VeilAddShaderPreProcessorsEvent> extra) {
        var combined = new ArrayList<>(this.registrars); combined.addAll(extra);
        return new NativeShaderProcessor(this.resources, this.definitions, this.definition, original, this.macros,
                new HashSet<>(this.dependencies), buffers, combined, this.blockLayouts);
    }
    public synchronized Set<String> generatedUniformBlocks() { return Set.copyOf(this.generatedUniformBlocks); }
    public synchronized Set<String> nativeUniformBlocks() { return Set.copyOf(this.nativeUniformBlocks); }
    public synchronized Map<String, foundry.veil.api.client.render.gpu.GpuUniformBuffer.Layout> automaticUniformLayouts() { return this.automaticUniforms; }
    public synchronized Set<String> automaticSamplerNames() { return this.automaticSamplers; }
    /** Process the genuine stage pair before fixing shared owned uniform offsets in either stage. */
    public synchronized Map<ShaderType, String> processProgram(Map<ShaderType, Identifier> shaders, java.util.function.Function<ShaderType, String> raw) {
        var trees = new LinkedHashMap<ShaderType, GlslTree>();
        for (ShaderType stage : List.of(ShaderType.VERTEX, ShaderType.FRAGMENT)) {
            Identifier id = shaders.get(stage); if (id == null) continue;
            String source = raw.apply(stage); if (source != null) trees.put(stage, this.processTree(id, stage, source));
        }
        try {
            this.automaticSamplers = this.definition == null ? Set.of() : NativeManagedUniforms.samplers(trees.values());
            this.automaticUniforms = this.definition == null ? Map.of() : NativeManagedUniforms.rewrite(trees.values());
        }
        catch (java.io.IOException failure) { throw new IllegalArgumentException("Cannot migrate managed native uniform declarations", failure); }
        var result = new EnumMap<ShaderType, String>(ShaderType.class);
        trees.forEach((stage, tree) -> result.put(stage, NativeGlslTreeWriter.source(tree))); return Map.copyOf(result);
    }
    /** Match the native shaderc compiler options before removing any conditional branches. */
    public static void addDeviceMacros(GpuDevice device, Map<String, String> macros) {
        var info = device.getDeviceInfo();
        if (info.isZZeroToOne()) macros.put("RENDERPEARL_DEPTH_IS_ZERO_TO_ONE", "");
        if (info.hintsAndWorkarounds().isExplicitDepthRequired()) macros.put("RENDERPEARL_EXPLICIT_DEPTH_INVARIANCE", "");
        if (info.features().shaderDrawParameters()) macros.put("RENDERPEARL_INSTANCE_INDEX_INCLUDES_BASE_INSTANCE", "");
    }
    public static NativeShaderProcessor forMinecraft(GpuDevice device, ResourceProvider resources, ShaderPreDefinitions definitions, RenderPipeline pipeline,
            int activeBuffers, List<VeilAddShaderPreProcessorsEvent> registrars) {
        return forMinecraftProgram(device, resources, definitions, null, pipeline, activeBuffers, registrars);
    }
    /** Resource-defined replacements retain their Veil definition while using the actual native pipeline contract. */
    public static NativeShaderProcessor forMinecraftProgram(GpuDevice device, ResourceProvider resources, ShaderPreDefinitions definitions,
            ProgramDefinition definition, RenderPipeline pipeline, int activeBuffers, List<VeilAddShaderPreProcessorsEvent> registrars) {
        Map<String, String> macros = new HashMap<>(definitions.getStaticDefinitions());
        macros.putAll(pipeline.getShaderDefines().values()); pipeline.getShaderDefines().flags().forEach(name -> macros.put(name, ""));
        addDeviceMacros(device, macros);
        return new NativeShaderProcessor(resources, definitions, definition, pipeline, macros, new HashSet<>(), activeBuffers, registrars,
                foundry.veil.api.client.render.shader.NativeShaderBlocks.snapshot());
    }
    @Override public synchronized String process(Identifier id, ShaderType stage, String source) {
        return NativeGlslTreeWriter.source(this.processTree(id, stage, source));
    }
    private GlslTree processTree(Identifier id, ShaderType stage, String source) {
        this.processors.getShaderImporter().reset();
        try {
            this.processors.getProcessor().prepare();
            Map<String, String> stageMacros = new HashMap<>(this.macros);
            GlslTree tree = GlslParser.parse(this.expandIncludes(stage, stageMacros, source));
            tree = NativeSodiumShaderProcessor.prepare(this.context(id, stage, stageMacros, true), tree);
            this.processors.getProcessor().modify(this.context(id, stage, stageMacros, true), tree);
            GlslTree.stripGLMacros(stageMacros); tree.getMacros().putAll(stageMacros);
            this.includes.addAll(this.processors.getShaderImporter().addedImports());
            return tree;
        } catch (Exception failure) { throw new IllegalArgumentException("Cannot preprocess native " + stage + " shader " + id, failure); }
    }
    @Override public synchronized Set<Identifier> includedResources() { return Set.copyOf(this.includes); }
    private String expandIncludes(ShaderType stage, Map<String, String> macros, String source) throws java.io.IOException, io.github.ocelot.glslprocessor.api.GlslSyntaxException, io.github.ocelot.glslprocessor.lib.anarres.cpp.LexerException {
        return new NativeShaderIncludes(this.processors.getShaderImporter()).preprocess(source, macros,
                (id, includeMacros, tree) -> this.processors.getImportProcessor().modify(this.context(id, stage, includeMacros, false), tree));
    }
    private ShaderPreProcessor.Context context(Identifier id, ShaderType stage, Map<String, String> macros, boolean source) {
        if (this.minecraftPipeline == null) return new Context(this, id, stage, macros, source);
        return this.definition == null ? new MinecraftContext(this, id, stage, macros, source)
                : new ManagedMinecraftContext(this, id, stage, macros, source);
    }
    private GlslTree include(Identifier id, ShaderType stage, Map<String, String> macros, String source) throws java.io.IOException, io.github.ocelot.glslprocessor.api.GlslSyntaxException, io.github.ocelot.glslprocessor.lib.anarres.cpp.LexerException {
        GlslTree tree = GlslParser.parse(this.expandIncludes(stage, macros, source));
        this.processors.getImportProcessor().modify(this.context(id, stage, macros, false), tree); return tree;
    }
    private record Context(NativeShaderProcessor owner, Identifier name, ShaderType stage, Map<String, String> macros, boolean isSourceFile) implements ShaderPreProcessor.VeilContext {
        @Override public GlslTree modifyInclude(Identifier name, String source) throws java.io.IOException, io.github.ocelot.glslprocessor.api.GlslSyntaxException, io.github.ocelot.glslprocessor.lib.anarres.cpp.LexerException {
            return this.owner.include(name, this.stage, this.macros, source);
        }
        @Override public Map<String, Object> customProgramData() { return this.owner.programData; }
        @Override public int activeBuffers() { return this.owner.activeBuffers; }
        @Override public int type() { return this.stage == ShaderType.VERTEX ? org.lwjgl.opengl.GL20C.GL_VERTEX_SHADER : org.lwjgl.opengl.GL20C.GL_FRAGMENT_SHADER; }
        @Override public ShaderImporter shaderImporter() { return this.owner.processors.getShaderImporter(); }
        @Override public ShaderPreDefinitions preDefinitions() { return this.owner.definitions; }
        @Override public void addUniformBinding(String name, int binding) { throw new UnsupportedOperationException("Native uniform buffers are declared by name in uniform_blocks, not numeric global bindings"); }
        @Override public void addDefinitionDependency(String name) { this.owner.dependencies.add(name); }
        @Override public boolean isDynamic() { return false; }
        @Override public ProgramDefinition definition() { return this.owner.definition; }
    }
    private record MinecraftContext(NativeShaderProcessor owner, Identifier name, ShaderType stage, Map<String, String> macros, boolean isSourceFile) implements ShaderPreProcessor.MinecraftContext {
        @Override public GlslTree modifyInclude(Identifier name, String source) throws java.io.IOException, io.github.ocelot.glslprocessor.api.GlslSyntaxException, io.github.ocelot.glslprocessor.lib.anarres.cpp.LexerException { return this.owner.include(name, this.stage, this.macros, source); }
        @Override public Map<String, Object> customProgramData() { return this.owner.programData; }
        @Override public int activeBuffers() { return this.owner.activeBuffers; }
        @Override public int type() { return this.stage == ShaderType.VERTEX ? org.lwjgl.opengl.GL20C.GL_VERTEX_SHADER : org.lwjgl.opengl.GL20C.GL_FRAGMENT_SHADER; }
        @Override public ShaderImporter shaderImporter() { return this.owner.processors.getShaderImporter(); }
        @Override public ShaderPreDefinitions preDefinitions() { return this.owner.definitions; }
        @Override public String shaderInstance() { return this.owner.minecraftPipeline.getLocation().toString(); }
        @Override public RenderPipeline pipeline() { return this.owner.minecraftPipeline; }
        @Override public VertexFormat vertexFormat() { return this.pipeline().getVertexFormatBindings().isEmpty() ? null : this.pipeline().getVertexFormatBinding(0); }
    }
    /** Managed sources retain their real definition contract while native variants expose the actual vertex/pipeline state too. */
    private record ManagedMinecraftContext(NativeShaderProcessor owner, Identifier name, ShaderType stage, Map<String, String> macros, boolean isSourceFile)
            implements ShaderPreProcessor.MinecraftContext, ShaderPreProcessor.VeilContext {
        @Override public GlslTree modifyInclude(Identifier name, String source) throws java.io.IOException, io.github.ocelot.glslprocessor.api.GlslSyntaxException, io.github.ocelot.glslprocessor.lib.anarres.cpp.LexerException { return this.owner.include(name, this.stage, this.macros, source); }
        @Override public Map<String, Object> customProgramData() { return this.owner.programData; }
        @Override public int activeBuffers() { return this.owner.activeBuffers; }
        @Override public int type() { return this.stage == ShaderType.VERTEX ? org.lwjgl.opengl.GL20C.GL_VERTEX_SHADER : org.lwjgl.opengl.GL20C.GL_FRAGMENT_SHADER; }
        @Override public ShaderImporter shaderImporter() { return this.owner.processors.getShaderImporter(); }
        @Override public ShaderPreDefinitions preDefinitions() { return this.owner.definitions; }
        @Override public String shaderInstance() { return this.owner.minecraftPipeline.getLocation().toString(); }
        @Override public RenderPipeline pipeline() { return this.owner.minecraftPipeline; }
        @Override public VertexFormat vertexFormat() { return this.pipeline().getVertexFormatBindings().isEmpty() ? null : this.pipeline().getVertexFormatBinding(0); }
        @Override public void addUniformBinding(String name, int binding) { throw new UnsupportedOperationException("Native uniform buffers are declared by name, not global numeric bindings"); }
        @Override public void addDefinitionDependency(String name) { this.owner.dependencies.add(name); }
        @Override public boolean isDynamic() { return false; }
        @Override public ProgramDefinition definition() { return this.owner.definition; }
    }
}
