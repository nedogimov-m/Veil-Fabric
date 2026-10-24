package foundry.veil.impl.client.render.pipeline;

import com.mojang.renderpearl.api.commands.CommandEncoder;
import com.mojang.renderpearl.api.device.GpuDevice;
import com.mojang.renderpearl.api.pipeline.ColorTargetState;
import com.mojang.renderpearl.api.pipeline.CompareOp;
import com.mojang.renderpearl.api.textures.FilterMode;
import com.mojang.renderpearl.api.textures.GpuSampler;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import foundry.veil.api.client.render.dynamicbuffer.DynamicBufferType;
import foundry.veil.api.client.render.framebuffer.FramebufferDefinition;
import foundry.veil.api.client.render.framebuffer.FramebufferManager;
import foundry.veil.api.client.render.framebuffer.VeilFramebuffers;
import foundry.veil.api.client.render.gpu.GpuFramebuffer;
import foundry.veil.api.client.render.gpu.GpuFullscreenPass;
import foundry.veil.api.client.render.gpu.GpuShaderProgram;
import foundry.veil.api.client.render.post.PostPipeline;
import foundry.veil.api.client.render.shader.ShaderManager;
import foundry.veil.api.client.render.texture.TextureFilter;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.ResourceManager;
import org.jetbrains.annotations.Nullable;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.Executor;
import java.util.function.Function;

/** Per-run explicit encoder/state/bindings. Borrowed textures never become context-owned. */
public final class PostPipelineContext implements PostPipeline.Context, AutoCloseable {
    private final GpuDevice device;
    private final ShaderManager shaders;
    private final FramebufferManager manager;
    private final Executor workers;
    private final Function<DynamicBufferType, GpuTextureView> dynamicTextures;
    private final Function<Identifier, PostPipeline> pipelines;
    private final Map<String, Texture> textures = new HashMap<>();
    private final Map<Identifier, GpuFramebuffer> framebuffers = new HashMap<>();
    private final Map<Identifier, FramebufferDefinition> definitions = new HashMap<>();
    private final Map<Boolean, GpuFullscreenPass> blitters = new HashMap<>();
    private ResourceManager resources;
    private CommandEncoder encoder;
    private foundry.veil.impl.client.render.dynamicbuffer.NativeDynamicBuffers.Scope captureScope;
    private int mask = ColorTargetState.WRITE_ALL;
    private CompareOp comparison = CompareOp.ALWAYS_PASS;
    private boolean depthWrite;

    public PostPipelineContext(GpuDevice device, ResourceManager resources, ShaderManager shaders,
            FramebufferManager manager, Executor workers, Function<DynamicBufferType, GpuTextureView> dynamicTextures,
            Function<Identifier, PostPipeline> pipelines) {
        this.device = Objects.requireNonNull(device); this.resources = Objects.requireNonNull(resources);
        this.shaders = Objects.requireNonNull(shaders); this.manager = Objects.requireNonNull(manager);
        this.workers = Objects.requireNonNull(workers); this.dynamicTextures = Objects.requireNonNull(dynamicTextures);
        this.pipelines = Objects.requireNonNull(pipelines);
    }
    public void setResources(ResourceManager resources) {
        if (this.encoder != null) throw new IllegalStateException("Cannot reload an active post run");
        this.blitters.values().forEach(GpuFullscreenPass::close); this.blitters.clear();
        this.resources = Objects.requireNonNull(resources);
    }
    public void begin() {
        if (this.encoder != null) throw new IllegalStateException("Nested post run");
        this.manager.getFramebuffers().forEach(this::setFramebuffer);
        for (Identifier name : this.framebuffers.keySet()) {
            var definition = this.manager.getFramebufferDefinition(name);
            if (definition != null) this.definitions.put(name, definition);
        }
        this.resetState();
        this.encoder = this.device.createCommandEncoder();
        this.captureScope = foundry.veil.impl.client.render.dynamicbuffer.NativeDynamicBuffers.suspend();
    }
    /** Submit all recorded stages once, then release borrowed bindings even on failure. */
    public void end() {
        CommandEncoder commands = this.encoder();
        try { commands.submit(); }
        finally {
            this.encoder = null; this.textures.clear(); this.framebuffers.clear(); this.definitions.clear(); this.resetState();
            this.captureScope.close(); this.captureScope = null;
        }
    }
    public void resetState() { this.mask = ColorTargetState.WRITE_ALL; this.comparison = CompareOp.ALWAYS_PASS; this.depthWrite = false; }
    @Override public GpuDevice device() { return this.device; }
    @Override public CommandEncoder encoder() {
        if (this.encoder == null) throw new IllegalStateException("No active post run");
        return this.encoder;
    }
    @Override public Executor workers() { return this.workers; }
    @Override public ShaderManager shaders() { return this.shaders; }
    @Override public void setTexture(String name, GpuTextureView texture, GpuSampler sampler) {
        this.textures.put(name, new Texture(Objects.requireNonNull(texture), Objects.requireNonNull(sampler), null));
    }
    @Override public void setTexture(String name, GpuTextureView texture, TextureFilter filter) {
        this.textures.put(name, new Texture(Objects.requireNonNull(texture), null, Objects.requireNonNull(filter)));
    }
    @Override public void setFramebuffer(Identifier name, GpuFramebuffer framebuffer) { this.framebuffers.put(name, Objects.requireNonNull(framebuffer)); }
    @Override public void setFramebuffer(Identifier name, GpuFramebuffer framebuffer, FramebufferDefinition definition) {
        this.setFramebuffer(name, framebuffer); this.definitions.put(name, definition);
    }
    @Override public void applySamplers(GpuShaderProgram shader) {
        this.textures.forEach((name, texture) -> {
            if (!shader.hasUniform(name)) return;
            if (texture.filter != null) shader.setTexture(name, texture.view, texture.filter);
            else shader.setTexture(name, texture.view, texture.sampler);
        });
    }
    @Override public @Nullable GpuFramebuffer getFramebuffer(Identifier name) { return this.framebuffers.get(name); }
    @Override public GpuFramebuffer getDrawFramebuffer() {
        GpuFramebuffer post = this.framebuffers.get(VeilFramebuffers.POST);
        if (post != null) return post;
        return this.requireFramebuffer(VeilFramebuffers.MAIN);
    }
    @Override public TextureFilter framebufferFilter(Identifier name, int colorAttachment, boolean depth) {
        FramebufferDefinition definition = this.definitions.get(name);
        if (definition == null) return TextureFilter.CLAMP;
        var attachment = depth ? definition.depthBuffer() : definition.colorBuffers()[colorAttachment];
        if (attachment == null) throw new IllegalArgumentException("Framebuffer has no configured depth attachment: " + name);
        return attachment.filter();
    }
    @Override public void setColorMask(boolean red, boolean green, boolean blue, boolean alpha) {
        this.mask = (red ? ColorTargetState.WRITE_RED : 0) | (green ? ColorTargetState.WRITE_GREEN : 0)
                | (blue ? ColorTargetState.WRITE_BLUE : 0) | (alpha ? ColorTargetState.WRITE_ALPHA : 0);
    }
    @Override public int colorMask() { return this.mask; }
    @Override public void setDepthState(CompareOp comparison, boolean writeDepth) { this.comparison = Objects.requireNonNull(comparison); this.depthWrite = writeDepth; }
    @Override public CompareOp depthComparison() { return this.comparison; }
    @Override public boolean writeDepth() { return this.depthWrite; }
    @Override public GpuTextureView getDynamicTexture(DynamicBufferType type) {
        return Objects.requireNonNull(this.dynamicTextures.apply(type), "Dynamic buffer is unavailable: " + type);
    }
    @Override public @Nullable PostPipeline getPipeline(Identifier name) { return this.pipelines.apply(name); }
    @Override public void copy(GpuFramebuffer source, GpuFramebuffer target, boolean color, boolean depth, boolean linear) {
        if (source == target) throw new IllegalArgumentException("A post copy cannot target its own framebuffer");
        boolean sameSize = source.width() == target.width() && source.height() == target.height();
        if (depth && (!sameSize || !source.hasDepth() || !target.hasDepth()
                || source.depth().texture().getFormat() != target.depth().texture().getFormat())) {
            throw new IllegalArgumentException("Native depth copies require equal size and matching depth formats");
        }
        if (color && source.colorCount() != target.colorCount()) throw new IllegalArgumentException("Color copies require equal attachment counts");
        if (color) for (int index = 0; index < source.colorCount(); index++) {
            if (!linear && sameSize && source.color(index).texture().getFormat() == target.color(index).texture().getFormat()) {
                source.copyColorTo(this.encoder(), index, target, index);
            } else {
                GpuFullscreenPass blitter = this.blitters.computeIfAbsent(linear, key ->
                        new GpuFullscreenPass(this.device, this.resources, this.workers, key ? FilterMode.LINEAR : FilterMode.NEAREST, null));
                blitter.blit(this.encoder(), source.color(index).view(), target.color(index).view());
            }
        }
        if (depth) source.copyDepthTo(this.encoder(), target);
    }
    /** Composite temporary names/bindings are restored when its child stages finish. */
    @Override public Scope pushScope() {
        var oldTextures = new HashMap<>(this.textures);
        var oldFramebuffers = new HashMap<>(this.framebuffers);
        var oldDefinitions = new HashMap<>(this.definitions);
        int oldMask = this.mask; CompareOp oldComparison = this.comparison; boolean oldWrite = this.depthWrite;
        return () -> {
            this.textures.clear(); this.textures.putAll(oldTextures);
            this.framebuffers.clear(); this.framebuffers.putAll(oldFramebuffers);
            this.definitions.clear(); this.definitions.putAll(oldDefinitions);
            this.mask = oldMask; this.comparison = oldComparison; this.depthWrite = oldWrite;
        };
    }
    @Override public void close() {
        if (this.encoder != null) throw new IllegalStateException("Cannot close an active post context");
        this.blitters.values().forEach(GpuFullscreenPass::close); this.blitters.clear();
        this.textures.clear(); this.framebuffers.clear(); this.definitions.clear();
    }
    private record Texture(GpuTextureView view, @Nullable GpuSampler sampler, @Nullable TextureFilter filter) { }
}
