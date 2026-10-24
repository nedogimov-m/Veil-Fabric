package foundry.veil.api.client.render.gpu;

import com.mojang.renderpearl.api.pipeline.ShaderSource;
import com.mojang.renderpearl.api.pipeline.ShaderType;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.ResourceManager;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/** Resource-pack shader source with owned native include-cache allocations. */
public final class GpuShaderSource implements ShaderSource {
    public static final String MANAGED_UNIFORMS = "VeilManagedUniforms";
    private final ResourceManager resources;
    private final String programFolder;
    private final String includeFolder;
    private final Map<String, String> macros;
    private final Processor processor;
    private final Map<ShaderKey, String> shaders = new HashMap<>();
    private final Map<ShaderKey, String> rawShaders = new HashMap<>();
    private final Map<Identifier, CachedIncludeSource> includes = new HashMap<>();
    private boolean closed;

    public GpuShaderSource(ResourceManager resources) { this(resources, null, "shaders/include", Map.of()); }

    public GpuShaderSource(ResourceManager resources, String programFolder, String includeFolder, Map<String, String> macros) {
        this(resources, programFolder, includeFolder, macros, (id, stage, source) -> source);
    }
    public GpuShaderSource(ResourceManager resources, String programFolder, String includeFolder, Map<String, String> macros, Processor processor) {
        this.resources = Objects.requireNonNull(resources);
        this.programFolder = programFolder;
        this.includeFolder = Objects.requireNonNull(includeFolder);
        this.macros = Map.copyOf(macros);
        this.processor = Objects.requireNonNull(processor);
        foundry.veil.api.client.render.shader.NativePipelineBridge.sourceLoaded(this, resources);
    }

    @Override
    public synchronized String getShader(Identifier id, ShaderType stage) {
        this.ensureOpen();
        return this.shaders.computeIfAbsent(new ShaderKey(id, stage), key -> this.processor.process(id, stage, this.rawShader(id, stage)));
    }
    /** Raw resource/macros before conditional/include/AST processing, for independently replayed native variants. */
    public synchronized String rawShader(Identifier id, ShaderType stage) {
        this.ensureOpen();
        return this.rawShaders.computeIfAbsent(new ShaderKey(id, stage), key -> {
            Identifier file = stage.idConverter().idToFile(id);
            if (this.programFolder != null) {
                Identifier custom = Identifier.fromNamespaceAndPath(id.getNamespace(), this.programFolder + "/" + id.getPath()
                        + (stage == ShaderType.VERTEX ? ".vsh" : ".fsh"));
                if (this.resources.getResource(custom).isPresent()) file = custom;
            }
            return this.addMacros(this.read(file));
        });
    }
    public Processor processor() { return this.processor; }
    /** Detach/process both managed roots together so shared typed uniforms have identical offsets in every stage. */
    public synchronized void prepare(com.mojang.renderpearl.api.pipeline.RenderPipeline pipeline) {
        this.ensureOpen();
        if (!(this.processor instanceof foundry.veil.impl.client.render.shader.processor.NativeShaderProcessor nativeProcessor)) return;
        if (pipeline.getShaders().entrySet().stream().allMatch(entry -> this.shaders.containsKey(new ShaderKey(entry.getValue(), entry.getKey())))) return;
        var processed = nativeProcessor.processProgram(pipeline.getShaders(), stage -> this.rawShader(pipeline.getShaders().get(stage), stage));
        processed.forEach((stage, source) -> this.shaders.put(new ShaderKey(pipeline.getShaders().get(stage), stage), source));
    }

    @Override
    public synchronized CachedIncludeSource getInclude(Identifier id) {
        this.ensureOpen();
        return this.includes.computeIfAbsent(id, key -> {
            try {
                Identifier file = Identifier.fromNamespaceAndPath(key.getNamespace(), this.includeFolder + "/" + key.getPath());
                if (this.resources.getResource(file).isEmpty()) file = Identifier.fromNamespaceAndPath(key.getNamespace(), "shaders/include/" + key.getPath());
                return CachedIncludeSource.create(key, this.read(file));
            } catch (RuntimeException failure) {
                return CachedIncludeSource.createError(failure.getMessage());
            }
        });
    }

    private String addMacros(String source) {
        StringBuilder defines = new StringBuilder();
        this.macros.forEach((name, value) -> {
            if (!name.matches("[A-Za-z_][A-Za-z0-9_]*") || value.contains("\n") || value.contains("\r")) {
                throw new IllegalArgumentException("Invalid shader definition: " + name);
            }
            defines.append("#define ").append(name).append(' ').append(value).append('\n');
        });
        int version = source.indexOf("#version");
        if (version < 0) return "#version 330\n#extension GL_ARB_separate_shader_objects : require\n" + defines + source;
        int end = source.indexOf('\n', version);
        if (end < 0) return source + "\n" + defines;
        return source.substring(0, end + 1) + defines + source.substring(end + 1);
    }

    private String read(Identifier file) {
        try {
            return this.resources.getResourceOrThrow(file).readAllAsString();
        } catch (IOException failure) {
            throw new UncheckedIOException("Cannot load GPU shader resource " + file, failure);
        }
    }

    private void ensureOpen() { if (this.closed) throw new IllegalStateException("Shader source is closed"); }
    /** Exact include IDs requested by the real compiler, retained by compiled program metadata. */
    public synchronized java.util.Set<Identifier> includedResources() {
        this.ensureOpen(); var dependencies = new java.util.HashSet<>(this.includes.keySet());
        dependencies.addAll(this.processor.includedResources()); return java.util.Set.copyOf(dependencies);
    }
    @FunctionalInterface public interface Processor {
        String process(Identifier id, ShaderType stage, String source);
        default java.util.Set<Identifier> includedResources() { return java.util.Set.of(); }
    }

    /** Must run after every asynchronous compilation using this source finishes. */
    @Override
    public synchronized void close() {
        if (this.closed) return;
        this.closed = true;
        for (CachedIncludeSource include : this.includes.values()) include.close();
        this.includes.clear();
        this.shaders.clear();
        this.rawShaders.clear();
    }

    private record ShaderKey(Identifier id, ShaderType stage) { }
}
