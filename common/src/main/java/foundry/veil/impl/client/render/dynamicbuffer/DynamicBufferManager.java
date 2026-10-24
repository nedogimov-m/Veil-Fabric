package foundry.veil.impl.client.render.dynamicbuffer;

import com.mojang.renderpearl.api.commands.CommandEncoder;
import com.mojang.renderpearl.api.commands.RenderPassDescriptor;
import com.mojang.renderpearl.api.device.GpuDevice;
import com.mojang.renderpearl.api.pipeline.CompiledRenderPipeline;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import foundry.veil.api.client.render.dynamicbuffer.DynamicBufferType;
import foundry.veil.api.client.render.dynamicbuffer.DynamicBuffersChange;
import foundry.veil.api.client.render.shader.NativePipelineBridge;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.ResourceManager;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * Render-thread owner of requested native world outputs. Buffer requests from independent owners
 * are combined; changing a request or dimensions replaces storage transactionally. A World lease
 * freezes the exact mask/storage used by an extracted world and survives subsequent changes.
 */
public final class DynamicBufferManager implements AutoCloseable {
    private final GpuDevice device;
    private final ResourceManager resources;
    private final VanillaShaderCompiler compiler;
    private final Consumer<DynamicBuffersChange> changes;
    private final Map<Identifier, Integer> requests = new HashMap<>();
    private final NativeDynamicBufferTarget target;
    private NativeDynamicOit oitOwner;
    private NativeDynamicCoverage coverageOwner;
    private int width, height, activeBuffers;
    private boolean closed;

    public DynamicBufferManager(GpuDevice device, ResourceManager resources, int width, int height, VanillaShaderCompiler compiler,
                                Consumer<DynamicBuffersChange> changes) {
        this.device = Objects.requireNonNull(device);
        this.resources = Objects.requireNonNull(resources);
        this.compiler = Objects.requireNonNull(compiler);
        this.changes = Objects.requireNonNull(changes);
        this.target = new NativeDynamicBufferTarget(device, "Veil world dynamic buffers", width, height, 0);
        this.width = width; this.height = height;
    }
    public int getActiveBuffers(Identifier owner) { this.ensureOpen(); return this.requests.getOrDefault(owner, 0); }
    GpuDevice device() { return this.device; }
    public int getActiveBuffers() { this.ensureOpen(); return this.activeBuffers; }
    /** Returns whether the combined mask changed; individual requests are retained even when redundant. */
    public boolean setActiveBuffers(Identifier owner, int mask) {
        this.ensureOpen(); Objects.requireNonNull(owner);
        int known = (1 << DynamicBufferType.values().length) - 1;
        if ((mask & ~known) != 0) throw new IllegalArgumentException("Unknown dynamic buffer mask: " + mask);
        if (this.requests.getOrDefault(owner, 0) == mask) return false;
        int combined = mask;
        for (var request : this.requests.entrySet()) if (!request.getKey().equals(owner)) combined |= request.getValue();
        // Allocation can fail without modifying either the combined mask or its source requests.
        this.target.configure(this.width, this.height, combined);
        if (mask == 0) this.requests.remove(owner); else this.requests.put(owner, mask);
        if (combined == this.activeBuffers) return false;
        int previous = this.activeBuffers; this.activeBuffers = combined;
        this.changes.accept(new DynamicBuffersChange(previous, combined));
        return true;
    }
    /** Borrowed view, valid until the next request/resize. Retain a World for queued GPU work. */
    public GpuTextureView getBufferTexture(DynamicBufferType type) {
        this.ensureOpen();
        World scoped = NativeDynamicBuffers.scopedWorld(this);
        if (scoped != null) return scoped.texture(type);
        try (var snapshot = this.target.retain()) { return snapshot.texture(type); }
    }
    /** Mask of the selected world, including when post-processing temporarily suspends capture. */
    public int getAvailableBuffers() {
        this.ensureOpen(); World scoped = NativeDynamicBuffers.scopedWorld(this);
        return scoped == null ? this.activeBuffers : scoped.mask();
    }
    public void resizeFramebuffers(int width, int height) {
        this.ensureOpen(); this.target.configure(width, height, this.activeBuffers);
        this.width = width; this.height = height;
    }
    /** Prepare before any world pass opens; the returned selection must outlive its encoder submissions. */
    public World beginWorld(GpuTextureView color, GpuTextureView depth) {
        return this.beginWorld(color, depth, false);
    }
    World beginWorld(GpuTextureView color, GpuTextureView depth, boolean independent) {
        this.ensureOpen(); Objects.requireNonNull(color);
        NativeDynamicBufferTarget.Snapshot snapshot;
        if (independent) {
            try (var isolated = new NativeDynamicBufferTarget(this.device, "Veil independent world dynamic buffers",
                    color.getWidth(0), color.getHeight(0), this.activeBuffers)) { snapshot = isolated.retain(); }
        } else {
            this.resizeFramebuffers(color.getWidth(0), color.getHeight(0)); snapshot = this.target.retain();
        }
        return new World(snapshot, color, depth);
    }
    public void endFrame() { this.ensureOpen(); this.compiler.removeRetiredBases(); }
    private NativeDynamicOit.Frame retainOit(int width, int height, int mask) {
        this.ensureOpen();
        if (this.oitOwner == null) this.oitOwner = new NativeDynamicOit(this.device, this.resources, width, height, mask);
        else this.oitOwner.configure(width, height, mask);
        return this.oitOwner.retain();
    }
    private NativeDynamicCoverage.Frame retainCoverage(int width, int height, int mask) {
        this.ensureOpen();
        if (this.coverageOwner == null) this.coverageOwner = new NativeDynamicCoverage(this.device, this.resources, width, height, mask);
        else this.coverageOwner.configure(width, height, mask);
        return this.coverageOwner.retain();
    }
    private void ensureOpen() { if (this.closed) throw new IllegalStateException("Dynamic buffer manager is closed"); }
    @Override public void close() {
        if (this.closed) return;
        this.closed = true; this.requests.clear(); this.target.close();
        if (this.oitOwner != null) this.oitOwner.close();
        if (this.coverageOwner != null) this.coverageOwner.close();
    }

    /** Immutable world selection. Retain independently for each encoder using the appended attachments. */
    public final class World implements AutoCloseable {
        private final NativeDynamicBufferTarget.Snapshot snapshot;
        private final GpuTextureView color, depth;
        private NativeDynamicOit.Frame oit;
        private NativeDynamicCoverage.Frame coverage;
        private int references = 1;
        private World(NativeDynamicBufferTarget.Snapshot snapshot, GpuTextureView color, GpuTextureView depth) {
            this.snapshot = snapshot; this.color = color; this.depth = depth;
        }
        public int mask() { this.ensureLive(); return this.snapshot.mask(); }
        DynamicBufferManager owner() { return DynamicBufferManager.this; }
        public GpuTextureView texture(DynamicBufferType type) { this.ensureLive(); return this.snapshot.texture(type); }
        public void clear(CommandEncoder encoder) { this.ensureLive(); this.snapshot.clear(encoder); }
        public World retain() { this.ensureLive(); this.references++; return this; }
        public boolean matches(RenderPassDescriptor descriptor) {
            this.ensureLive();
            return this.mask() != 0 && descriptor.colorAttachments().size() == 1
                    && descriptor.colorAttachments().getFirst() != null
                    && descriptor.colorAttachments().getFirst().textureView().texture() == this.color.texture()
                    && (descriptor.depthAttachment() == null || this.depth != null
                        && descriptor.depthAttachment().textureView().texture() == this.depth.texture());
        }
        public RenderPassDescriptor extend(RenderPassDescriptor descriptor) { this.ensureLive(); return this.snapshot.extend(descriptor); }
        RenderPassDescriptor extendCoverage(CommandEncoder encoder, RenderPassDescriptor descriptor) {
            this.ensureLive();
            if (this.coverage == null) this.coverage = DynamicBufferManager.this.retainCoverage(this.color.getWidth(0), this.color.getHeight(0), this.mask());
            this.coverage.initialize(encoder, this.snapshot::texture, this.color, descriptor.colorAttachments().getFirst().clearValue());
            return this.coverage.extend(descriptor);
        }
        void resolveCoverage(CommandEncoder encoder) { this.ensureLive(); this.coverage.resolve(encoder, this.snapshot::texture); }
        RenderPassDescriptor extendOit(CommandEncoder encoder, RenderPassDescriptor descriptor) {
            this.ensureLive();
            if (this.oit == null) {
                this.oit = DynamicBufferManager.this.retainOit(this.color.getWidth(0), this.color.getHeight(0), this.mask());
                this.oit.clear(encoder);
            }
            return this.oit.extend(descriptor);
        }
        public VanillaShaderCompiler.Lease pipeline(CompiledRenderPipeline base) {
            return this.pipeline(base, false);
        }
        VanillaShaderCompiler.Lease pipeline(CompiledRenderPipeline base, boolean accumulation) {
            this.ensureLive();
            RenderPipeline definition = NativePipelineBridge.actualDefinition(base)
                    .or(() -> NativePipelineBridge.originalDefinition(base))
                    .orElseThrow(() -> new IllegalArgumentException("World pipeline has no native source metadata: " + base));
            RenderPipeline variant = accumulation ? Objects.requireNonNull(this.oit).pipeline(definition)
                    : this.coverage == null ? this.snapshot.pipeline(definition) : this.coverage.pipeline(definition);
            return DynamicBufferManager.this.compiler.retain(base, variant, this.mask());
        }
        boolean hasOit() { return this.oit != null; }
        void composeOit(CommandEncoder encoder, GpuTextureView nativeAccumulation, GpuTextureView depthBounds, GpuTextureView[] coefficients) {
            this.ensureLive(); this.oit.compose(encoder, this.snapshot::texture, nativeAccumulation, depthBounds, coefficients);
        }
        private void ensureLive() { if (this.references == 0) throw new IllegalStateException("Dynamic world selection is closed"); }
        @Override public void close() {
            this.ensureLive();
            if (--this.references == 0) {
                try { if (this.oit != null) this.oit.close(); }
                finally { try { if (this.coverage != null) this.coverage.close(); } finally { this.snapshot.close(); } }
            }
        }
    }
}
