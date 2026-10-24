package foundry.veil.impl.client.render.dynamicbuffer;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.api.device.GpuDevice;
import com.mojang.renderpearl.api.pipeline.*;
import foundry.veil.api.client.render.shader.NativePipelineBridge;
import net.minecraft.client.Minecraft;
import net.minecraft.util.Util;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

/** Owns real mask-specific native variants; ordinary Minecraft caches retain their base owners. */
public final class VanillaShaderCompiler implements AutoCloseable {
    private final GpuDevice device;
    private final Executor workers, renderThread;
    private final Map<CompiledRenderPipeline, Map<Key, Entry>> variants = new IdentityHashMap<>();
    private final List<CompletableFuture<Void>> retirements = new ArrayList<>();
    private boolean closed;
    public VanillaShaderCompiler() { this(RenderSystem.getDevice(), Util.backgroundExecutor(), Minecraft.getInstance()); }
    public VanillaShaderCompiler(GpuDevice device, Executor workers, Executor renderThread) {
        this.device = Objects.requireNonNull(device); this.workers = Objects.requireNonNull(workers); this.renderThread = Objects.requireNonNull(renderThread);
    }
    /** Detach actual source now and schedule SPIR-V work before opening the consuming pass. */
    public CompletableFuture<Void> prepare(CompiledRenderPipeline base, RenderPipeline definition, int mask) {
        return this.entry(base, definition, mask).pending.thenApply(ignored -> null);
    }
    /** Finish on the render thread; retain this exact compiled owner through submission. */
    public Lease retain(CompiledRenderPipeline base, RenderPipeline definition, int mask) {
        Entry entry = this.entry(base, definition, mask);
        CompiledRenderPipeline pipeline = entry.finish(); entry.references++;
        return new Lease(entry, pipeline);
    }
    private Entry entry(CompiledRenderPipeline base, RenderPipeline definition, int mask) {
        if (this.closed) throw new IllegalStateException("Native vanilla shader compiler is closed");
        Key key = new Key(mask, definition);
        Map<Key, Entry> existing = this.variants.get(base);
        if (existing != null && existing.containsKey(key)) return existing.get(key);
        if (base.isClosed()) throw new IllegalArgumentException("A retired native base cannot prepare new variants");
        Entry created = new Entry(base, definition, mask);
        this.variants.computeIfAbsent(base, ignored -> new HashMap<>()).put(key, created);
        return created;
    }
    /** Retire old resource-pack bases while queued variant leases remain usable. */
    public void removeRetiredBases() {
        var iterator = this.variants.entrySet().iterator();
        while (iterator.hasNext()) {
            var entry = iterator.next();
            if (entry.getKey().isClosed()) { entry.getValue().values().forEach(Entry::retire); iterator.remove(); }
        }
    }
    public boolean isCompilingShaders() {
        return this.variants.values().stream().flatMap(map -> map.values().stream()).anyMatch(entry -> !entry.pending.isDone());
    }
    public int variantCount() { return this.variants.values().stream().mapToInt(Map::size).sum(); }
    /** Completes after currently retired native owners, including queued leases/pending modules, are actually released. */
    public CompletableFuture<Void> retirement() {
        this.retirements.removeIf(future -> future.isDone() && !future.isCompletedExceptionally());
        return CompletableFuture.allOf(this.retirements.toArray(CompletableFuture[]::new));
    }
    public void clear() { this.variants.values().forEach(map -> map.values().forEach(Entry::retire)); this.variants.clear(); }
    @Override public void close() { if (!this.closed) { this.closed = true; this.clear(); } }

    public final class Lease implements AutoCloseable {
        private final Entry entry;
        private final CompiledRenderPipeline pipeline;
        private boolean closed;
        private Lease(Entry entry, CompiledRenderPipeline pipeline) { this.entry = entry; this.pipeline = pipeline; }
        public CompiledRenderPipeline pipeline() { this.ensureOpen(); return this.pipeline; }
        public int activeBuffers() { this.ensureOpen(); return this.entry.mask; }
        public void bind(RenderPass pass) { pass.setPipeline(this.pipeline()); }
        private void ensureOpen() { if (this.closed || this.pipeline.isClosed()) throw new IllegalStateException("Native vanilla shader lease is closed"); }
        @Override public void close() { if (!this.closed) { this.closed = true; this.entry.release(); } }
    }
    private final class Entry {
        final int mask;
        final CompletableFuture<CompiledRenderPipeline.Pending> pending;
        final CompletableFuture<Void> disposed = new CompletableFuture<>();
        CompiledRenderPipeline compiled;
        int references = 1;
        boolean retired;
        Entry(CompiledRenderPipeline base, RenderPipeline definition, int mask) {
            this.mask = mask;
            DynamicBufferProcessor processor = new DynamicBufferProcessor();
            int outputBase = NativePipelineBridge.originalDefinition(base).orElseThrow().getColorTargetStates().size();
            var registrars = new ArrayList<foundry.veil.api.event.VeilAddShaderPreProcessorsEvent>();
            registrars.add((resources, registry) -> registry.addPreprocessor(processor, false));
            boolean accumulation = definition.getShaderDefines().flags().contains("OIT_ACCUMULATE");
            boolean coverage = !accumulation && definition.getColorTargetStates().size() == outputBase + NativeDynamicCoverage.extraTargetCount(mask);
            if (accumulation)
                registrars.add((resources, registry) -> registry.addPreprocessor(new NativeDynamicOitProcessor(), false));
            else if (coverage)
                registrars.add((resources, registry) -> registry.addPreprocessor(new NativeDynamicCoverageProcessor(), false));
            this.pending = NativePipelineBridge.compileVariant(VanillaShaderCompiler.this.device, base, definition, mask,
                    registrars, VanillaShaderCompiler.this.workers,
                    state -> maskUnproducedOutputs(state, mask, processor.producedBuffers(), outputBase, coverage));
        }
        CompiledRenderPipeline finish() {
            if (this.compiled == null) {
                this.compiled = this.pending.join().finishCompile();
                if (this.compiled == null || this.compiled.isClosed()) throw new IllegalStateException("Native variant compilation returned no usable pipeline");
            }
            return this.compiled;
        }
        void retire() {
            if (!this.retired) { this.retired = true; VanillaShaderCompiler.this.retirements.add(this.disposed); this.release(); }
        }
        void release() {
            if (--this.references != 0) return;
            // Consume/dispose the genuine Pending on the render thread even if its cache was cleared while workers ran.
            this.pending.whenComplete((value, failure) -> {
                if (failure != null) { this.disposed.complete(null); return; }
                VanillaShaderCompiler.this.renderThread.execute(() -> {
                    try { if (this.references == 0) this.finish().close(); this.disposed.complete(null); }
                    catch (Throwable cleanupFailure) { this.disposed.completeExceptionally(cleanupFailure); }
                });
            });
        }
    }
    private static RenderPipeline maskUnproducedOutputs(RenderPipeline definition, int requested, int produced, int base, boolean coverage) {
        if ((requested & produced) == requested) return definition;
        var types = foundry.veil.api.client.render.dynamicbuffer.DynamicBufferType.decode(requested);
        var colors = new ArrayList<>(definition.getColorTargetStates());
        if (base + types.length > colors.size()) throw new IllegalArgumentException("Dynamic variant is missing its attachment states");
        for (int index = 0; index < types.length; index++) {
            if ((produced & types[index].getMask()) == 0) {
                ColorTargetState original = colors.get(base + index);
                colors.set(base + index, new ColorTargetState(original.blendFunction(), original.format(), coverage ? ColorTargetState.WRITE_ALPHA : ColorTargetState.WRITE_NONE));
            }
        }
        if (coverage) {
            for (int group = 0; group < NativeDynamicCoverage.packedTargetCount(requested); group++) {
                int writes = ColorTargetState.WRITE_ALPHA;
                for (int component = 0; component < 3; component++) {
                    int property = group * 3 + component;
                    if (property < types.length && (produced & types[property].getMask()) != 0) writes |= 1 << component;
                }
                int index = base + types.length + group;
                ColorTargetState original = colors.get(index);
                colors.set(index, new ColorTargetState(original.blendFunction(), original.format(), writes));
            }
        }
        var snippet = new RenderPipeline.Snippet(definition.getShaders(), Optional.of(definition.getShaderDefines()), Optional.of(definition.getBindGroupLayouts()),
                colors.toArray(ColorTargetState[]::new), colors.size(), Optional.ofNullable(definition.getDepthStencilState()),
                Optional.of(definition.getPolygonMode()), Optional.of(definition.isCull()), definition.getVertexFormatBindings().toArray(com.mojang.renderpearl.api.vertex.VertexFormat[]::new),
                Optional.of(definition.getPrimitiveTopology()), definition.pushConstantSize());
        return RenderPipeline.builder(snippet).withLocation(definition.getLocation()).build();
    }
    /** Compare all immutable native state, preserving holes in color attachment layouts. */
    private record Key(int mask, Map<ShaderType, net.minecraft.resources.Identifier> shaders,
                       net.minecraft.client.renderer.ShaderDefines defines, List<BindGroupLayout> bindings,
                       List<ColorTargetState> colors, DepthStencilState depth, PolygonMode polygon, boolean cull,
                       List<com.mojang.renderpearl.api.vertex.VertexFormat> vertices, PrimitiveTopology topology, int pushConstants) {
        Key(int mask, RenderPipeline definition) {
            this(mask, definition.getShaders(), definition.getShaderDefines(), definition.getBindGroupLayouts(),
                    Collections.unmodifiableList(new ArrayList<>(definition.getColorTargetStates())), definition.getDepthStencilState(),
                    definition.getPolygonMode(), definition.isCull(), definition.getVertexFormatBindings(), definition.getPrimitiveTopology(), definition.pushConstantSize());
        }
    }
}
