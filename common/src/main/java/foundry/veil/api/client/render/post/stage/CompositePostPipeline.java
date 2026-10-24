package foundry.veil.api.client.render.post.stage;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import foundry.veil.api.client.registry.PostPipelineStageRegistry;
import foundry.veil.api.client.render.dynamicbuffer.DynamicBufferType;
import foundry.veil.api.client.render.framebuffer.FramebufferDefinition;
import foundry.veil.api.client.render.gpu.GpuFramebuffer;
import foundry.veil.api.client.render.post.PostPipeline;
import foundry.veil.api.client.render.shader.texture.ShaderTextureSource;
import foundry.veil.api.client.render.shader.texture.FramebufferSource;
import foundry.veil.api.client.render.shader.texture.LocationSource;
import foundry.veil.api.client.render.texture.TextureFilter;
import foundry.veil.api.event.VeilRenderLevelStageEvent;
import net.minecraft.resources.Identifier;
import org.jetbrains.annotations.Nullable;
import org.joml.Vector4f;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/** Ordered native stages with owned temporary targets and scoped borrowed textures. */
public final class CompositePostPipeline implements PostPipeline {
    private static final Codec<Map<Identifier, FramebufferDefinition>> FRAMEBUFFER_CODEC = Codec.unboundedMap(
            Codec.STRING.xmap(name -> Identifier.fromNamespaceAndPath("temp", name), Identifier::getPath), FramebufferDefinition.CODEC);
    public static final Codec<CompositePostPipeline> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            PostPipeline.CODEC.listOf().fieldOf("stages").forGetter(pipeline -> Arrays.asList(pipeline.getStages())),
            Codec.unboundedMap(Codec.STRING, ShaderTextureSource.CODEC).optionalFieldOf("textures", Collections.emptyMap()).forGetter(CompositePostPipeline::getTextureSources),
            FRAMEBUFFER_CODEC.optionalFieldOf("framebuffers", Collections.emptyMap()).forGetter(CompositePostPipeline::getFramebuffers),
            VeilRenderLevelStageEvent.Stage.CODEC.optionalFieldOf("renderStage").forGetter(pipeline -> Optional.ofNullable(pipeline.getRenderStage())),
            DynamicBufferType.PACKED_LIST_CODEC.optionalFieldOf("dynamicBuffers", 0).forGetter(CompositePostPipeline::getDynamicBuffersMask),
            Codec.INT.optionalFieldOf("priority", 1000).forGetter(CompositePostPipeline::getPriority),
            Codec.BOOL.optionalFieldOf("replace", false).forGetter(CompositePostPipeline::isReplace)
    ).apply(instance, (pipelines, textures, framebuffers, renderStage, dynamicBuffers, priority, replace) ->
            new CompositePostPipeline(pipelines.toArray(PostPipeline[]::new), textures, framebuffers, renderStage.orElse(null), dynamicBuffers, priority, replace)));

    private final PostPipeline[] stages;
    private final Map<String, ShaderTextureSource> textureSources;
    private final Map<Identifier, FramebufferDefinition> definitions;
    private final Map<Identifier, GpuFramebuffer> framebuffers = new HashMap<>();
    @Nullable private final VeilRenderLevelStageEvent.Stage stage;
    private final int dynamicBuffersMask;
    private final int priority;
    private final boolean replace;
    private int width = -1;
    private int height = -1;

    public CompositePostPipeline(PostPipeline[] stages, Map<String, ShaderTextureSource> textures,
            Map<Identifier, FramebufferDefinition> definitions, @Nullable VeilRenderLevelStageEvent.Stage stage, int dynamicBuffers) {
        this(stages, textures, definitions, stage, dynamicBuffers, 1000, false);
    }
    public CompositePostPipeline(PostPipeline[] stages, Map<String, ShaderTextureSource> textures,
            Map<Identifier, FramebufferDefinition> definitions, @Nullable VeilRenderLevelStageEvent.Stage stage, int dynamicBuffers, int priority, boolean replace) {
        this.stages = stages.clone(); this.textureSources = Map.copyOf(textures); this.definitions = Map.copyOf(definitions);
        this.stage = stage; this.dynamicBuffersMask = dynamicBuffers; this.priority = priority; this.replace = replace;
    }
    @Override public void apply(Context context) {
        GpuFramebuffer draw = context.getDrawFramebuffer();
        if (draw.width() != this.width || draw.height() != this.height) this.allocate(context, draw.width(), draw.height());
        try (var ignored = context.pushScope()) {
            this.framebuffers.forEach((name, target) -> context.setFramebuffer(name, target, this.definitions.get(name)));
            for (DynamicBufferType type : this.getDynamicBuffers()) context.setTexture(type.getSourceName(), context.getDynamicTexture(type), TextureFilter.CLAMP);
            this.textureSources.forEach((name, source) -> {
                var view = source.getTexture(context);
                if (source.filter() != null) context.setTexture(name, view, source.filter());
                else if (source instanceof FramebufferSource framebuffer) context.setTexture(name, view,
                        context.framebufferFilter(framebuffer.name(), framebuffer.sampler(), framebuffer.depth()));
                else if (source instanceof LocationSource location) context.setTexture(name, view, context.getSampler(location.location()));
            });
            for (PostPipeline pipeline : this.stages) pipeline.apply(context);
        }
    }
    /** Failed allocations leave the complete previous temporary-target set intact. */
    private void allocate(Context context, int width, int height) {
        Map<Identifier, GpuFramebuffer> replacements = new HashMap<>();
        try {
            for (var entry : this.definitions.entrySet()) {
                var target = entry.getValue().createBuilder(width, height).setDebugLabel("Post temporary " + entry.getKey()).build(context.device());
                replacements.put(entry.getKey(), target);
                target.clear(context.encoder(), new Vector4f(), com.mojang.blaze3d.systems.RenderSystem.DEFAULT_DEPTH_CLEAR_VALUE);
            }
        } catch (RuntimeException | Error failure) {
            replacements.values().forEach(GpuFramebuffer::close); throw failure;
        }
        this.framebuffers.values().forEach(GpuFramebuffer::close); this.framebuffers.clear(); this.framebuffers.putAll(replacements);
        this.width = width; this.height = height;
    }
    @Override public void free() {
        for (PostPipeline pipeline : this.stages) pipeline.free();
        this.framebuffers.values().forEach(GpuFramebuffer::close); this.framebuffers.clear(); this.width = -1; this.height = -1;
    }
    @Override public PostPipelineStageRegistry.PipelineType<? extends PostPipeline> getType() {
        throw new UnsupportedOperationException("Composite pipelines use their own codec");
    }
    public PostPipeline[] getStages() { return this.stages.clone(); }
    public Map<String, ShaderTextureSource> getTextureSources() { return this.textureSources; }
    public Map<Identifier, FramebufferDefinition> getFramebuffers() { return this.definitions; }
    public DynamicBufferType[] getDynamicBuffers() { return DynamicBufferType.decode(this.dynamicBuffersMask); }
    public @Nullable VeilRenderLevelStageEvent.Stage getRenderStage() { return this.stage; }
    public int getDynamicBuffersMask() { return this.dynamicBuffersMask; }
    public int getPriority() { return this.priority; }
    public boolean isReplace() { return this.replace; }
}
