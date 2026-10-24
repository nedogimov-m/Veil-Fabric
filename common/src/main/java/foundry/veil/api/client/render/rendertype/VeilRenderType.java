package foundry.veil.api.client.render.rendertype;

import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.renderpearl.api.pipeline.BlendFactor;
import com.mojang.renderpearl.api.pipeline.BlendFunction;
import com.mojang.renderpearl.api.pipeline.PrimitiveTopology;
import com.mojang.renderpearl.api.vertex.VertexFormat;
import foundry.veil.api.client.render.framebuffer.FramebufferManager;
import foundry.veil.api.client.registry.RenderTypeShardRegistry;
import foundry.veil.api.client.render.shader.ShaderManager;
import foundry.veil.api.event.VeilRenderLevelStageEvent;
import foundry.veil.impl.client.render.rendertype.DynamicRenderTypeManager;
import foundry.veil.impl.client.render.rendertype.NativeRenderTypeDraws;
import foundry.veil.impl.client.render.pipeline.VeilShaderBlockState;
import foundry.veil.mixin.rendertype.accessor.RenderTypeAccessor;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.rendertype.PreparedRenderType;
import net.minecraft.client.renderer.rendertype.RenderSetup;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.resources.Identifier;
import org.jetbrains.annotations.Nullable;

import java.util.*;
import java.util.concurrent.Executor;

/** Actual native RenderSetup/pipeline types, reloadable wrappers and same-buffer layer execution. */
public final class VeilRenderType {
    private static volatile DynamicRenderTypeManager manager;
    private static final Map<ParticleKey, RenderType> PARTICLES = new HashMap<>();
    private static final Map<ParticleKey, RenderType> TRAILS = new HashMap<>();
    private static final Identifier WHITE = Identifier.fromNamespaceAndPath("veil", "textures/special/white.png");

    public static RenderType create(String name, VertexFormat format, PrimitiveTopology topology, int bufferSize,
                                     boolean affectsCrumbling, boolean sort, boolean outline, VeilRenderTypeBuilder.State state) {
        final var pipeline = VeilRenderTypeBuilder.pipeline(Identifier.fromNamespaceAndPath("veil", "dynamic/" + Integer.toUnsignedString(name.hashCode(), 16)),
                state, format, topology, List.of(com.mojang.renderpearl.api.pipeline.ColorTargetState.DEFAULT.format()),
                com.mojang.renderpearl.api.GpuFormat.D32_FLOAT);
        final var setup = VeilRenderTypeBuilder.setup(pipeline, state, affectsCrumbling, sort, outline);
        final var type = RenderTypeShardRegistry.constructing(() -> RenderTypeAccessor.veil$create(name, setup));
        NativeRenderTypeDraws.register(type, state, bufferSize);
        RenderTypeShardRegistry.created(type);
        return type;
    }
    public static void setDynamicManager(DynamicRenderTypeManager manager) { VeilRenderType.manager = Objects.requireNonNull(manager); }
    public static void bindRuntime(ShaderManager shaders, FramebufferManager framebuffers, Executor workers) {
        NativeRenderTypeDraws.bindRuntime(shaders, framebuffers, workers);
    }
    public static void bindUniformBlocks(VeilShaderBlockState blocks) { NativeRenderTypeDraws.bindUniformBlocks(blocks); }
    public static void unbindRuntime() { NativeRenderTypeDraws.unbindRuntime(); }
    /** The owner invokes this at END_MAIN, after vanilla has closed its active render passes. */
    public static void drawPending(VeilRenderLevelStageEvent.Stage stage, VeilRenderLevelStageEvent.Context context) {
        NativeRenderTypeDraws.drawPending(stage, context);
    }
    public static @Nullable RenderType get(Identifier id, Object... params) {
        final var owner = manager;
        return owner != null ? owner.get(id, params) : null;
    }
    public static RenderTypeWrapper getWrapper(Identifier id) { return new RenderTypeWrapper(id); }
    public static String getName(RenderType type) { return ((RenderTypeAccessor) (Object) type).veil$getName(); }
    public static String getName(@Nullable Identifier target) { return target == null ? "current_target" : target.toString(); }
    public static VeilRenderTypeAccessor getShards(RenderType type) {
        final var setup = ((RenderTypeAccessor) (Object) type).veil$getState();
        final var state = NativeRenderTypeDraws.state(type);
        return new VeilRenderTypeAccessor() {
            @Override public RenderSetup renderSetup() { return setup; }
            @Override public com.mojang.renderpearl.api.pipeline.RenderPipeline pipeline() { return type.pipeline(); }
            @Override public Identifier veilShaderId() { return state != null ? state.veilShader() : null; }
            @Override public Identifier outputState() { return state != null ? state.output() : null; }
        };
    }
    public static RenderType layered(RenderType... layers) {
        if (layers.length == 0) throw new IllegalArgumentException("At least one render type must be specified");
        if (layers.length == 1) return layers[0];
        for (RenderType type : layers) {
            if (!layers[0].format().equals(type.format())) throw new IllegalArgumentException("Layer vertex formats differ");
            if (layers[0].primitiveTopology() != type.primitiveTopology()) throw new IllegalArgumentException("Layer primitive topologies differ");
        }
        return new LayeredRenderType(layers);
    }
    public static void release(RenderType type) {
        if (type instanceof LayeredRenderType layered) for (RenderType layer : layered.allLayers) release(layer);
        NativeRenderTypeDraws.release(type);
    }
    public static synchronized RenderType quasarParticle(Identifier texture, boolean additive) {
        return PARTICLES.computeIfAbsent(new ParticleKey(texture, additive), key -> create("veil:quasar_particle/" + key,
                foundry.veil.api.client.render.vertex.VeilVertexFormat.QUASAR_PARTICLE, PrimitiveTopology.QUADS,
                RenderType.SMALL_BUFFER_SIZE, false, true, false,
                new VeilRenderTypeBuilder().shaderState(Identifier.fromNamespaceAndPath("veil", "quasar/particle"), true)
                        .textureState(List.of(new VeilRenderTypeBuilder.Texture(texture, false, false)))
                        .transparencyState(Optional.of(additive ? new BlendFunction(BlendFactor.ONE, BlendFactor.ONE) : BlendFunction.TRANSLUCENT))
                        .lightmapState(true).create()));
    }
    public static RenderType quasarTrail(@Nullable Identifier texture) { return quasarTrail(texture, true); }
    public static synchronized RenderType quasarTrail(@Nullable Identifier texture, boolean additive) {
        return TRAILS.computeIfAbsent(new ParticleKey(texture, additive), key -> create("veil:quasar_trail/" + key,
                DefaultVertexFormat.ENTITY, PrimitiveTopology.TRIANGLE_STRIP, RenderType.TRANSIENT_BUFFER_SIZE,
                false, false, false, new VeilRenderTypeBuilder()
                        .shaderState(Identifier.withDefaultNamespace("rendertype_entity_translucent_emissive"), false)
                        .textureState(List.of(new VeilRenderTypeBuilder.Texture(texture == null ? WHITE : texture, false, false)))
                        .transparencyState(Optional.of(additive ? new BlendFunction(BlendFactor.ONE, BlendFactor.ONE) : BlendFunction.TRANSLUCENT))
                        .cullState(foundry.veil.api.client.render.rendertype.layer.CullLayer.CullFace.NONE).create()));
    }
    private record ParticleKey(@Nullable Identifier texture, boolean additive) {}

    public static final class LayeredRenderType extends RenderType {
        private final RenderType[] allLayers;
        private LayeredRenderType(RenderType[] layers) {
            super("Veil layered " + Arrays.toString(layers), ((RenderTypeAccessor) (Object) layers[0]).veil$getState());
            this.allLayers = layers.clone();
        }
        @Override public PreparedRenderType prepare() {
            final var prepared = Arrays.stream(this.allLayers).map(RenderType::prepare).toList();
            // Legacy layered types define ordered blending. Disable the first native type's OIT
            // multipass container so every layer reuses the staged geometry exactly once.
            final var first = prepared.getFirst();
            final var combined = new PreparedRenderType(first.name(), first.pipeline(), null, first.dynamicTransforms(), first.scissorState(), first.textures());
            NativeRenderTypeDraws.layered(combined, prepared);
            return combined;
        }
        @Override public boolean sortOnUpload() { return Arrays.stream(this.allLayers).anyMatch(RenderType::sortOnUpload); }
        public RenderType[] getLayers() { return Arrays.copyOfRange(this.allLayers, 1, this.allLayers.length); }
        public RenderType[] getAllLayers() { return this.allLayers.clone(); }
    }
    public static final class RenderTypeWrapper extends RenderType {
        private final Identifier id;
        private Object[] params = new Object[0];
        private RenderTypeWrapper(Identifier id) {
            super(id.toString(), RenderSetup.builder(RenderPipelines.DEBUG_QUADS).createRenderSetup());
            this.id = Objects.requireNonNull(id);
        }
        public void setParams(Object... params) { this.params = params.clone(); }
        public @Nullable RenderType get() { return VeilRenderType.get(this.id, this.params); }
        private RenderType require() {
            final var type = this.get();
            if (type == null) throw new IllegalStateException("Dynamic render type could not be resolved: " + this.id);
            return type;
        }
        @Override public PreparedRenderType prepare() { return require().prepare(); }
        @Override public VertexFormat format() { return require().format(); }
        @Override public PrimitiveTopology primitiveTopology() { return require().primitiveTopology(); }
        @Override public com.mojang.renderpearl.api.pipeline.RenderPipeline pipeline() { return require().pipeline(); }
        @Override public Optional<RenderType> outline() { return require().outline(); }
        @Override public boolean isOutline() { return require().isOutline(); }
        @Override public boolean affectsCrumbling() { return require().affectsCrumbling(); }
        @Override public boolean sortOnUpload() { return require().sortOnUpload(); }
        @Override public boolean canConsolidateConsecutiveGeometry() { return require().canConsolidateConsecutiveGeometry(); }
        @Override public boolean hasBlending() { return require().hasBlending(); }
        @Override public boolean forceSolidModelPhase() { return require().forceSolidModelPhase(); }
    }
    private VeilRenderType() {}
}
