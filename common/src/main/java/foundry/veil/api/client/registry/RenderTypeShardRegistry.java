package foundry.veil.api.client.registry;

import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import foundry.veil.api.client.render.rendertype.VeilRenderType;
import foundry.veil.api.client.render.rendertype.VeilRenderTypeBuilder;
import foundry.veil.impl.client.render.rendertype.NativeRenderTypeDraws;
import foundry.veil.mixin.rendertype.accessor.RenderTypeAccessor;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.resources.Identifier;

import java.util.*;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.function.UnaryOperator;

/** Native setup transforms for existing and future concrete render types. No opaque global GL callbacks. */
public final class RenderTypeShardRegistry {
    private static final Map<String, List<UnaryOperator<VeilRenderTypeBuilder>>> SHARDS = new LinkedHashMap<>();
    private static final List<Generic> GENERIC = new ArrayList<>();
    private static final Set<RenderType> CREATED = Collections.newSetFromMap(new WeakHashMap<>());
    private static final ThreadLocal<Integer> CONSTRUCTING = ThreadLocal.withInitial(() -> 0);
    private static long revision;

    @SafeVarargs public static synchronized void addShard(RenderType type, UnaryOperator<VeilRenderTypeBuilder>... operations) {
        requireConcrete(type);
        apply(type, List.of(operations));
    }
    @SafeVarargs public static synchronized void addShard(String name, UnaryOperator<VeilRenderTypeBuilder>... operations) {
        if (operations.length == 0) throw new IllegalArgumentException("No render-type transforms supplied");
        final var values = List.of(operations);
        SHARDS.computeIfAbsent(Objects.requireNonNull(name), key -> new ArrayList<>()).addAll(values);
        for (var type : List.copyOf(CREATED)) if (VeilRenderType.getName(type).equals(name)) apply(type, values);
    }
    @SafeVarargs public static synchronized void addGenericShard(Predicate<RenderType> filter, UnaryOperator<VeilRenderTypeBuilder>... operations) {
        if (operations.length == 0) throw new IllegalArgumentException("No render-type transforms supplied");
        final var values = List.of(operations);
        GENERIC.add(new Generic(Objects.requireNonNull(filter), values));
        for (var type : List.copyOf(CREATED)) if (filter.test(type)) apply(type, values);
    }
    /** Constructor hook; wrappers/layer containers retain their explicit delegated setup semantics. */
    public static synchronized void created(RenderType type) {
        if (CONSTRUCTING.get() != 0 || type.getClass() != RenderType.class || !CREATED.add(type)) return;
        final var operations = new ArrayList<>(SHARDS.getOrDefault(VeilRenderType.getName(type), List.of()));
        for (var generic : GENERIC) if (generic.filter.test(type)) operations.addAll(generic.operations);
        apply(type, operations);
    }
    /** Used while constructing a managed type before attaching its real shader/output metadata. */
    public static <T> T constructing(Supplier<T> create) {
        CONSTRUCTING.set(CONSTRUCTING.get() + 1);
        try { return create.get(); }
        finally { CONSTRUCTING.set(CONSTRUCTING.get() - 1); }
    }
    private static void requireConcrete(RenderType type) {
        if (type.getClass() != RenderType.class) throw new IllegalArgumentException("Apply native transforms to the resolved concrete render type, not its wrapper or layer container");
    }
    private static void apply(RenderType type, List<UnaryOperator<VeilRenderTypeBuilder>> operations) {
        if (operations.isEmpty()) return;
        final var access = (RenderTypeAccessor) (Object) type;
        final var metadata = NativeRenderTypeDraws.state(type);
        var builder = metadata != null ? VeilRenderTypeBuilder.from(metadata) : VeilRenderTypeBuilder.from(access.veil$getState());
        for (var operation : operations) builder = Objects.requireNonNull(operation.apply(builder), "A render-type transform returned null");
        final var state = builder.create();
        final var colors = new ArrayList<GpuFormat>();
        for (var target : type.pipeline().getColorTargetStates()) colors.add(target == null ? null : target.format());
        final RenderPipeline pipeline = VeilRenderTypeBuilder.pipeline(Identifier.fromNamespaceAndPath("veil", "modifier/" + revision++),
                state, type.format(), type.primitiveTopology(), colors, type.pipeline().getDepthStencilState() == null ? null : GpuFormat.D32_FLOAT);
        final var setup = VeilRenderTypeBuilder.setup(pipeline, state, type.affectsCrumbling(), type.sortOnUpload(), type.outline().isPresent());
        final var replacement = constructing(() -> RenderTypeAccessor.veil$create(VeilRenderType.getName(type), setup));
        access.veil$setState(setup);
        access.veil$setHasBlending(replacement.hasBlending());
        access.veil$setOutline(replacement.outline());
        NativeRenderTypeDraws.replace(type, state);
    }
    private record Generic(Predicate<RenderType> filter, List<UnaryOperator<VeilRenderTypeBuilder>> operations) {}
    private RenderTypeShardRegistry() {}
}
