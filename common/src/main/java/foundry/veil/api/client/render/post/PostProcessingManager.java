package foundry.veil.api.client.render.post;

import com.google.common.collect.Iterables;
import com.google.gson.JsonParser;
import com.google.gson.JsonSyntaxException;
import com.mojang.renderpearl.api.device.GpuDevice;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import com.mojang.serialization.JsonOps;
import foundry.veil.api.CodecReloadListener;
import foundry.veil.api.client.render.dynamicbuffer.DynamicBufferType;
import foundry.veil.api.client.render.framebuffer.FramebufferManager;
import foundry.veil.api.client.render.framebuffer.VeilFramebuffers;
import foundry.veil.api.client.render.post.stage.CompositePostPipeline;
import foundry.veil.api.client.render.shader.ShaderManager;
import foundry.veil.api.event.VeilRenderLevelStageEvent;
import foundry.veil.impl.client.render.pipeline.PostPipelineContext;
import net.minecraft.resources.FileToIdConverter;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.util.profiling.ProfilerFiller;
import org.jetbrains.annotations.Nullable;
import org.lwjgl.system.NativeResource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.io.IOException;
import java.io.Reader;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.function.Function;
import java.util.function.IntConsumer;
import java.util.function.IntSupplier;

/** Resource-driven post processing with native explicit GPU stages and transactional lifetimes. */
public final class PostProcessingManager extends CodecReloadListener<CompositePostPipeline> implements NativeResource {
    private static final Logger LOGGER = LoggerFactory.getLogger("Veil");
    private static final Comparator<ProfileEntry> SORTER = Comparator.comparingInt(ProfileEntry::getPriority).reversed();
    private final FramebufferManager framebuffers;
    private final PostPipelineContext context;
    private final IntSupplier availableDynamicBuffers;
    private final IntConsumer requestedDynamicBuffers;
    private final List<ProfileEntry> active = new ArrayList<>();
    private final Map<Identifier, CompositePostPipeline> pipelines = new HashMap<>();
    private boolean dirty;
    private int enabledBuffers;

    public PostProcessingManager(GpuDevice device, ResourceManager resources, ShaderManager shaders,
            FramebufferManager framebuffers, Executor workers, Function<DynamicBufferType, GpuTextureView> dynamicTextures,
            IntSupplier availableDynamicBuffers, IntConsumer requestedDynamicBuffers) {
        super(CompositePostPipeline.CODEC, FileToIdConverter.json("pinwheel/post"));
        this.framebuffers = Objects.requireNonNull(framebuffers);
        this.availableDynamicBuffers = Objects.requireNonNull(availableDynamicBuffers);
        this.requestedDynamicBuffers = Objects.requireNonNull(requestedDynamicBuffers);
        this.context = new PostPipelineContext(device, resources, shaders, framebuffers, workers, dynamicTextures, this::getPipeline);
    }
    public boolean isActive(Identifier id) { return this.active.stream().anyMatch(entry -> entry.pipeline.equals(id)); }
    public boolean add(Identifier id) { return this.add(1000, id); }
    public boolean add(int priority, Identifier id) {
        for (int index = 0; index < this.active.size(); index++) {
            ProfileEntry entry = this.active.get(index);
            if (!entry.pipeline.equals(id)) continue;
            if (entry.priority == priority) return false;
            this.active.set(index, new ProfileEntry(id, priority)); this.dirty = true; return true;
        }
        this.active.add(new ProfileEntry(id, priority)); this.dirty = true; return true;
    }
    public boolean remove(Identifier id) { return this.active.removeIf(entry -> entry.pipeline.equals(id)); }
    public @Nullable PostPipeline getPipeline(Identifier id) { return this.pipelines.get(id); }
    /** The renderer uses these requested outputs in the following extracted frame. */
    public void endFrame() { this.requestedDynamicBuffers.accept(this.enabledBuffers); this.enabledBuffers = 0; }

    public void runDefaultPipeline(@Nullable VeilRenderLevelStageEvent.Stage stage) {
        if (stage != null && stage != VeilRenderLevelStageEvent.Stage.END_MAIN) {
            throw new IllegalStateException("Post passes must run after vanilla render passes close at END_MAIN");
        }
        if (this.active.isEmpty()) return;
        this.context.begin();
        try {
            for (ProfileEntry entry : this.getActivePipelines()) {
                CompositePostPipeline pipeline = this.pipelines.get(entry.pipeline);
                if (pipeline == null || pipeline.getRenderStage() != stage || !this.requestBuffers(pipeline)) continue;
                try { pipeline.apply(this.context); this.resolvePost(); }
                catch (RuntimeException failure) { LOGGER.error("Error running native post pipeline {}", entry.pipeline, failure); }
                finally { this.context.resetState(); }
            }
        } finally { this.context.end(); }
    }
    private boolean requestBuffers(PostPipeline pipeline) {
        if (!(pipeline instanceof CompositePostPipeline composite)) return true;
        this.enabledBuffers |= composite.getDynamicBuffersMask();
        return (this.availableDynamicBuffers.getAsInt() & composite.getDynamicBuffersMask()) == composite.getDynamicBuffersMask();
    }
    public void runPipeline(PostPipeline pipeline) { this.runPipeline(pipeline, true); }
    /** Run only outside an existing GPU pass; failures propagate to the caller. */
    public void runPipeline(PostPipeline pipeline, boolean resolvePost) {
        if (!this.requestBuffers(pipeline)) return;
        this.context.begin();
        try { pipeline.apply(this.context); if (resolvePost) this.resolvePost(); }
        finally { this.context.end(); }
    }
    private void resolvePost() {
        var post = this.framebuffers.getFramebuffer(VeilFramebuffers.POST);
        var main = this.framebuffers.getFramebuffer(VeilFramebuffers.MAIN);
        if (post != null && main != null && post != main) this.context.copy(post, main, true, false, false);
    }
    private CompositePostPipeline loadPipeline(Resource resource) throws IOException {
        try (Reader reader = resource.openAsReader()) {
            return this.codec.parse(JsonOps.INSTANCE, JsonParser.parseReader(reader))
                    .getOrThrow(message -> new JsonSyntaxException(message));
        }
    }
    @Override protected Map<Identifier, CompositePostPipeline> prepare(ResourceManager resources, ProfilerFiller profiler) {
        Map<Identifier, CompositePostPipeline> data = new HashMap<>();
        this.converter.listMatchingResourceStacks(resources).forEach((file, stack) -> {
            Identifier id = this.converter.fileToId(file);
            List<CompositePostPipeline> loaded = new ArrayList<>();
            for (Resource resource : stack) {
                try { loaded.add(this.loadPipeline(resource)); }
                catch (Exception failure) { LOGGER.error("Cannot parse post pipeline {} from {}", id, file, failure); }
            }
            if (loaded.isEmpty()) return;
            if (loaded.size() == 1) { data.put(id, Iterables.getOnlyElement(loaded)); return; }
            loaded.sort(Comparator.comparingInt(CompositePostPipeline::getPriority));
            int buffers = 0;
            for (int index = 0; index < loaded.size(); index++) {
                CompositePostPipeline pipeline = loaded.get(index);
                buffers |= pipeline.getDynamicBuffersMask();
                if (pipeline.isReplace()) { loaded = loaded.subList(0, index + 1); break; }
            }
            data.put(id, new CompositePostPipeline(loaded.toArray(PostPipeline[]::new), Map.of(), Map.of(), loaded.getFirst().getRenderStage(), buffers));
        });
        return data;
    }
    @Override protected void apply(Map<Identifier, CompositePostPipeline> data, ResourceManager resources, ProfilerFiller profiler) {
        this.pipelines.values().forEach(PostPipeline::free); this.pipelines.clear();
        this.context.setResources(resources); this.pipelines.putAll(data);
        LOGGER.info("Loaded {} native post pipelines", data.size());
    }
    @Override public void free() {
        this.pipelines.values().forEach(PostPipeline::free); this.pipelines.clear(); this.context.close();
    }
    public PostPipeline.Context getPostPipelineContext() { return this.context; }
    public Set<Identifier> getPipelines() { return Collections.unmodifiableSet(this.pipelines.keySet()); }
    public List<ProfileEntry> getActivePipelines() {
        if (this.dirty) { this.active.sort(SORTER); this.dirty = false; }
        return Collections.unmodifiableList(this.active);
    }
    public static final class ProfileEntry {
        private final Identifier pipeline;
        private int priority;
        public ProfileEntry(Identifier pipeline, int priority) { this.pipeline = Objects.requireNonNull(pipeline); this.priority = priority; }
        public Identifier getPipeline() { return this.pipeline; }
        public int getPriority() { return this.priority; }
        public void setPriority(int priority) { this.priority = priority; }
        @Override public boolean equals(Object other) { return other instanceof ProfileEntry entry && this.pipeline.equals(entry.pipeline) && this.priority == entry.priority; }
        @Override public int hashCode() { return Objects.hash(this.pipeline, this.priority); }
    }
}
