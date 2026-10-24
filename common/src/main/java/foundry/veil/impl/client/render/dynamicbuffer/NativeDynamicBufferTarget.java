package foundry.veil.impl.client.render.dynamicbuffer;

import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.commands.CommandEncoder;
import com.mojang.renderpearl.api.commands.RenderPassDescriptor;
import com.mojang.renderpearl.api.device.GpuDevice;
import com.mojang.renderpearl.api.pipeline.*;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import com.mojang.renderpearl.api.vertex.VertexFormat;
import foundry.veil.api.client.render.dynamicbuffer.DynamicBufferType;
import foundry.veil.api.client.render.gpu.GpuFramebuffer;
import org.joml.Vector4f;
import org.joml.Vector4fc;

import java.util.*;

/** Owned dynamic MRT storage with immutable per-submission selections and borrowed caller attachments. */
public final class NativeDynamicBufferTarget implements AutoCloseable {
    private final GpuDevice device;
    private final String label;
    private Generation current;
    private boolean closed;

    public NativeDynamicBufferTarget(GpuDevice device, String label, int width, int height, int mask) {
        this.device = Objects.requireNonNull(device); this.label = Objects.requireNonNull(label);
        this.current = this.allocate(width, height, mask);
    }
    /** Allocate first, retaining the previous generation if allocation fails or a submitted selection still owns it. */
    public void configure(int width, int height, int mask) {
        this.ensureOpen();
        validateMask(mask);
        if (this.current.width == width && this.current.height == height && this.current.mask == mask) return;
        Generation replacement = this.allocate(width, height, mask);
        Generation old = this.current; this.current = replacement; old.release();
    }
    public Snapshot retain() { this.ensureOpen(); this.current.references++; return new Snapshot(this.current); }
    public int mask() { this.ensureOpen(); return this.current.mask; }
    private Generation allocate(int width, int height, int mask) {
        validateMask(mask);
        if (width <= 0 || height <= 0) throw new IllegalArgumentException("Dynamic attachment dimensions must be positive");
        DynamicBufferType[] types = DynamicBufferType.decode(mask);
        GpuFramebuffer target = null;
        if (types.length != 0) {
            var builder = GpuFramebuffer.builder(this.label, width, height);
            for (DynamicBufferType type : types) builder.color(type.gpuFormat());
            target = builder.build(this.device);
        }
        return new Generation(width, height, mask, List.of(types), target);
    }
    private static void validateMask(int mask) {
        int known = (1 << DynamicBufferType.values().length) - 1;
        if ((mask & ~known) != 0) throw new IllegalArgumentException("Unknown dynamic attachment mask: " + mask);
    }
    private void ensureOpen() { if (this.closed) throw new IllegalStateException("Dynamic attachment owner is closed"); }
    @Override public void close() { if (!this.closed) { this.closed = true; this.current.release(); } }

    /** Keep this selection alive through the encoder submission which consumes its texture views. */
    public final class Snapshot implements AutoCloseable {
        private final Generation generation;
        private boolean closed;
        private Snapshot(Generation generation) { this.generation = generation; }
        public int mask() { this.ensureOpen(); return this.generation.mask; }
        public List<DynamicBufferType> types() { this.ensureOpen(); return this.generation.types; }
        public GpuTextureView texture(DynamicBufferType type) {
            this.ensureOpen(); int index = this.generation.types.indexOf(type);
            if (index < 0) throw new IllegalArgumentException("Dynamic buffer was not selected: " + type);
            return this.generation.target.color(index).view();
        }
        public void clear(CommandEncoder encoder) {
            this.ensureOpen();
            if (this.generation.target != null) this.generation.target.clear(encoder, new Vector4f(0), 0);
        }
        /** Preserve caller color/depth views, load/clear values, render area and label, appending only selected outputs. */
        public RenderPassDescriptor extend(RenderPassDescriptor caller) {
            this.ensureOpen(); Objects.requireNonNull(caller);
            int count = caller.colorAttachments().size() + this.generation.types.size();
            int limit = Math.min(ColorTargetState.MAX_COLOR_TARGETS, NativeDynamicBufferTarget.this.device.getDeviceInfo().limits().maxColorAttachments());
            if (count > limit) throw new IllegalArgumentException("Native dynamic MRT needs " + count + " color attachments, device supports " + limit);
            for (var attachment : caller.colorAttachments()) if (attachment != null) this.validateView(attachment.textureView());
            if (caller.depthAttachment() != null) this.validateView(caller.depthAttachment().textureView());
            var attachments = new ArrayList<>(caller.colorAttachments());
            for (DynamicBufferType type : this.generation.types) attachments.add(new RenderPassDescriptor.Attachment<Optional<Vector4fc>>(this.texture(type), Optional.empty()));
            return new RenderPassDescriptor(caller.label(), attachments, caller.depthAttachment(), caller.renderArea());
        }
        /** A genuine immutable pipeline variant, retaining every original shader/define/layout/state field. */
        public RenderPipeline pipeline(RenderPipeline original) {
            this.ensureOpen();
            int count = original.getColorTargetStates().size() + this.generation.types.size();
            int limit = Math.min(ColorTargetState.MAX_COLOR_TARGETS, NativeDynamicBufferTarget.this.device.getDeviceInfo().limits().maxColorAttachments());
            if (count > limit) throw new IllegalArgumentException("Pipeline dynamic attachments exceed native device limits");
            var colors = new ArrayList<>(original.getColorTargetStates());
            Optional<BlendFunction> blend = colors.isEmpty() || colors.getFirst() == null ? Optional.empty() : colors.getFirst().blendFunction();
            for (DynamicBufferType type : this.generation.types) colors.add(new ColorTargetState(blend, type.gpuFormat(), ColorTargetState.WRITE_ALL));
            var snippet = new RenderPipeline.Snippet(original.getShaders(), Optional.of(original.getShaderDefines()), Optional.of(original.getBindGroupLayouts()),
                    colors.toArray(ColorTargetState[]::new), colors.size(), Optional.ofNullable(original.getDepthStencilState()),
                    Optional.of(original.getPolygonMode()), Optional.of(original.isCull()), original.getVertexFormatBindings().toArray(VertexFormat[]::new),
                    Optional.of(original.getPrimitiveTopology()), original.pushConstantSize());
            return RenderPipeline.builder(snippet).withLocation(original.getLocation()).build();
        }
        private void validateView(GpuTextureView view) {
            if (view.isClosed() || view.texture().isClosed() || view.getWidth(0) != this.generation.width || view.getHeight(0) != this.generation.height)
                throw new IllegalArgumentException("Caller attachments must be live and match dynamic dimensions");
        }
        private void ensureOpen() { if (this.closed) throw new IllegalStateException("Dynamic attachment snapshot is closed"); }
        @Override public void close() { if (!this.closed) { this.closed = true; this.generation.release(); } }
    }
    private static final class Generation {
        final int width, height, mask;
        final List<DynamicBufferType> types;
        final GpuFramebuffer target;
        int references = 1;
        Generation(int width, int height, int mask, List<DynamicBufferType> types, GpuFramebuffer target) {
            this.width = width; this.height = height; this.mask = mask; this.types = types; this.target = target;
        }
        void release() { if (--this.references == 0 && this.target != null) this.target.close(); }
    }
}
