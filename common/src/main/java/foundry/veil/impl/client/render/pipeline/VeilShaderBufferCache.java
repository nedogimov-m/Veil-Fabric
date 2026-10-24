package foundry.veil.impl.client.render.pipeline;

import com.mojang.renderpearl.api.device.GpuDevice;
import foundry.veil.api.client.render.VeilShaderBufferLayout;
import foundry.veil.api.client.render.gpu.GpuShaderProgram;
import foundry.veil.api.client.render.shader.block.ShaderBlock;
import foundry.veil.impl.client.render.LayoutSerializer;
import foundry.veil.impl.client.render.shader.block.LayoutShaderBlockImpl;
import net.minecraft.resources.Identifier;
import java.util.Collection;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;
import org.lwjgl.system.NativeResource;

/** Registered object block cache. Immutable std140 schemas survive native shader reload. */
public final class VeilShaderBufferCache implements NativeResource {
    private final Supplier<GpuDevice> device;
    private final Supplier<? extends Collection<VeilShaderBufferLayout<?>>> layouts;
    private final VeilShaderBlockState state;
    private final Map<VeilShaderBufferLayout<?>, LayoutShaderBlockImpl<?>> values = new IdentityHashMap<>();

    public VeilShaderBufferCache(Supplier<GpuDevice> device, Supplier<? extends Collection<VeilShaderBufferLayout<?>>> layouts,
            VeilShaderBlockState state) {
        this.device = Objects.requireNonNull(device); this.layouts = Objects.requireNonNull(layouts); this.state = Objects.requireNonNull(state);
    }
    private void validate(VeilShaderBufferLayout<?> layout) {
        if (this.layouts.get().stream().noneMatch(registered -> registered == layout)) {
            throw new IllegalArgumentException("Attempted to use unregistered buffer layout: " + layout.name());
        }
    }
    @SuppressWarnings("unchecked")
    public <T> ShaderBlock<T> getBlock(VeilShaderBufferLayout<T> layout) {
        this.validate(layout);
        return (ShaderBlock<T>) this.values.computeIfAbsent(layout, ignored -> LayoutSerializer.create(layout, this.device.get()));
    }
    public void onShaderCompile(Map<Identifier, GpuShaderProgram> programs) {
        for (var layout : this.layouts.get()) {
            for (var entry : programs.entrySet()) {
                if (entry.getValue().bindingTypes().get(layout.name()) != com.mojang.renderpearl.api.pipeline.UniformType.UNIFORM_BUFFER) continue;
                this.getBlock(layout);
                this.values.get(layout).getReferencedShaders().add(entry.getKey());
            }
        }
    }
    /** Select all existing global blocks; actual upload occurs for the draw's program. */
    public void bind() { this.values.forEach((layout, block) -> this.state.bind(layout.name(), block)); }
    public void bind(VeilShaderBufferLayout<?> layout) { this.state.bind(layout.name(), this.getBlock(layout)); }
    public void unbind(VeilShaderBufferLayout<?> layout) {
        this.validate(layout); var block = this.values.get(layout);
        if (block != null) this.state.unbind(block);
    }
    @Override public void free() {
        this.values.values().forEach(block -> { this.state.unbind(block); block.free(); }); this.values.clear();
    }
}
