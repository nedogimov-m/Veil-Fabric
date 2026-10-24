package foundry.veil.impl.client.render.pipeline;

import com.mojang.renderpearl.api.commands.CommandEncoder;
import foundry.veil.api.client.render.gpu.GpuShaderProgram;
import foundry.veil.api.client.render.shader.block.ShaderBlock;
import java.util.HashMap;
import java.util.Collection;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.WeakHashMap;

/** Named native block selection. Upload/binding is explicit for each actual draw program. */
public final class VeilShaderBlockState {
    private final Map<String, ShaderBlock<?>> selected = new HashMap<>();
    private final Set<String> used = new HashSet<>();
    private final Map<GpuShaderProgram, Set<String>> applied = new WeakHashMap<>();

    public void bind(CharSequence name, ShaderBlock<?> block) {
        String binding = name.toString();
        if (!binding.matches("[A-Za-z_][A-Za-z0-9_]*")) throw new IllegalArgumentException("Invalid shader block binding name: " + binding);
        this.selected.put(binding, Objects.requireNonNull(block)); this.used.add(binding);
    }
    public void unbind(ShaderBlock<?> block) { this.selected.values().removeIf(value -> value == block); }
    /** Capture selection and serialized values now, without issuing commands in the active vanilla pass. */
    public Map<String, ShaderBlock.Snapshot> snapshot(GpuShaderProgram program) {
        Map<String, ShaderBlock.Snapshot> snapshots = new HashMap<>();
        this.selected.forEach((name, block) -> {
            if (program.bindingTypes().get(name) == com.mojang.renderpearl.api.pipeline.UniformType.UNIFORM_BUFFER)
                snapshots.put(name, block.snapshot());
        });
        return Map.copyOf(snapshots);
    }
    /** Capture only explicitly requested named blocks, without inspecting or freezing native draw-owned bindings. */
    public Map<String, ShaderBlock.Snapshot> snapshot(Collection<String> requestedNames) {
        Map<String, ShaderBlock.Snapshot> snapshots = new HashMap<>();
        for (String name : requestedNames) {
            ShaderBlock<?> block = this.selected.get(name);
            if (block != null) snapshots.put(name, block.snapshot());
        }
        return Map.copyOf(snapshots);
    }
    /** Prepare detached draws without mutating the program's live external bindings. */
    public static Map<String, com.mojang.renderpearl.api.buffers.GpuBufferSlice> prepareSnapshots(
            CommandEncoder encoder, Map<String, ShaderBlock.Snapshot> snapshots) {
        Map<String, com.mojang.renderpearl.api.buffers.GpuBufferSlice> slices = new HashMap<>();
        snapshots.forEach((name, snapshot) -> slices.put(name, snapshot.prepare(encoder)));
        return Map.copyOf(slices);
    }
    /** Must run before opening the render pass, followed by the program's prepare. */
    public void prepare(CommandEncoder encoder, GpuShaderProgram program) {
        Set<String> bindings = new HashSet<>();
        this.selected.forEach((name, block) -> {
            if (program.bindingTypes().get(name) != com.mojang.renderpearl.api.pipeline.UniformType.UNIFORM_BUFFER) return;
            block.prepare(encoder); program.setUniformBlock(name, block.slice()); bindings.add(name);
        });
        Set<String> previous = this.applied.put(program, bindings);
        if (previous != null) for (String name : previous) {
            if (!bindings.contains(name)) program.removeExternalUniformBlock(name);
        }
    }
    /** Retain this frame's selected blocks; discard names no longer requested next frame. */
    public void clearUsedBindings() {
        this.selected.keySet().removeIf(name -> !this.used.contains(name)); this.used.clear();
    }
    public void clear() {
        this.applied.forEach((program, names) -> {
            if (!program.isClosed()) names.forEach(program::removeExternalUniformBlock);
        });
        this.applied.clear(); this.selected.clear(); this.used.clear();
    }
}
