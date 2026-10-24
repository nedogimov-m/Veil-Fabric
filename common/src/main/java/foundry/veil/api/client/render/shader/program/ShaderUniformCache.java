package foundry.veil.api.client.render.shader.program;

import com.mojang.renderpearl.api.pipeline.UniformType;
import foundry.veil.api.client.render.gpu.GpuShaderProgram;
import foundry.veil.api.client.render.gpu.GpuUniformBuffer;
import org.jetbrains.annotations.Nullable;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;

/**
 * Native binding metadata from the program's explicit pipeline/UBO schemas.
 * Borrowed blocks report their known byte range; their field schema remains
 * unknown unless the owning program created a typed block. No GL reflection,
 * integer program locations or fabricated external field layouts.
 */
public final class ShaderUniformCache {
    private final GpuShaderProgram program;
    private Map<String, Uniform> uniforms = Map.of();
    private Map<String, UniformType> samplers = Map.of();
    private Map<String, UniformBlock> blocks = Map.of();
    public ShaderUniformCache(GpuShaderProgram program) { this.program = Objects.requireNonNull(program); }
    private void refresh() {
        var layouts = this.program.uniformLayouts();
        var sizes = this.program.uniformBlockSizes();
        var uniforms = new LinkedHashMap<String, Uniform>();
        var samplers = new LinkedHashMap<String, UniformType>();
        var blocks = new LinkedHashMap<String, UniformBlock>();
        this.program.bindingTypes().entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(entry -> {
            String name = entry.getKey();
            if (entry.getValue() != UniformType.UNIFORM_BUFFER) { samplers.put(name, entry.getValue()); return; }
            var layout = layouts.get(name);
            Optional<Map<String, Uniform>> fields = Optional.empty();
            if (layout != null) {
                var fieldsByName = new LinkedHashMap<String, Uniform>();
                layout.fields().forEach((fieldName, field) -> {
                    Uniform uniform = new Uniform(name, fieldName, field.baseOffset(), field.type(), field.arrayLength(), field.stride());
                    fieldsByName.put(fieldName, uniform); uniforms.put(name + "." + fieldName, uniform);
                });
                fields = Optional.of(Collections.unmodifiableMap(fieldsByName));
            }
            Long size = sizes.get(name);
            blocks.put(name, new UniformBlock(name, size != null ? OptionalLong.of(size) : OptionalLong.empty(), fields));
        });
        this.uniforms = Collections.unmodifiableMap(uniforms);
        this.samplers = Collections.unmodifiableMap(samplers);
        this.blocks = Collections.unmodifiableMap(blocks);
    }
    public @Nullable Uniform getUniform(String block, String field) { this.refresh(); return this.uniforms.get(block + "." + field); }
    public boolean hasUniform(String block, String field) { return this.getUniform(block, field) != null; }
    public @Nullable UniformBlock getUniformBlock(String name) { this.refresh(); return this.blocks.get(name); }
    public boolean hasUniformBlock(String name) { return this.getUniformBlock(name) != null; }
    public boolean hasSampler(String name) { this.refresh(); return this.samplers.containsKey(name); }
    public Map<String, Uniform> getUniforms() { this.refresh(); return this.uniforms; }
    public Map<String, UniformType> getSamplers() { this.refresh(); return this.samplers; }
    public Map<String, UniformBlock> getUniformBlocks() { this.refresh(); return this.blocks; }
    public record Uniform(String block, String name, int offset, GpuUniformBuffer.ValueType type, int arrayLength, int arrayStride) { }
    public record UniformBlock(String name, OptionalLong size, Optional<Map<String, Uniform>> fields) { }
}
