package foundry.veil.impl.client.render.dynamicbuffer;

import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.commands.CommandEncoder;
import com.mojang.renderpearl.api.commands.RenderPassDescriptor;
import com.mojang.renderpearl.api.device.GpuDevice;
import com.mojang.renderpearl.api.pipeline.*;
import com.mojang.renderpearl.api.textures.*;
import com.mojang.renderpearl.api.vertex.VertexFormat;
import foundry.veil.api.client.render.dynamicbuffer.DynamicBufferType;
import foundry.veil.api.client.render.gpu.GpuFramebuffer;
import foundry.veil.api.client.render.gpu.GpuShaderProgram;
import foundry.veil.api.client.render.gpu.GpuShaderSource;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.ResourceManager;
import org.joml.Vector4f;
import org.joml.Vector4fc;

import java.util.*;
import java.util.function.Function;

/**
 * Owns exact generic vec4 OIT property accumulation and packed produced-coverage weights.
 * Native vanilla colour/coefficient targets remain borrowed. All lifecycle calls belong to the render thread.
 */
public final class NativeDynamicOit implements AutoCloseable {
    private final GpuDevice device;
    private final ResourceManager resources;
    private Generation current;
    private boolean closed;
    public NativeDynamicOit(GpuDevice device, ResourceManager resources, int width, int height, int mask) {
        this.device = Objects.requireNonNull(device); this.resources = Objects.requireNonNull(resources);
        this.current = new Generation(width, height, mask);
    }
    public static int weightTargetCount(int mask) { return (DynamicBufferType.decode(mask).length + 3) / 4; }
    public static int extraTargetCount(int mask) { return DynamicBufferType.decode(mask).length + weightTargetCount(mask); }
    public void configure(int width, int height, int mask) {
        this.ensureOpen();
        if (this.current.width == width && this.current.height == height && this.current.mask == mask) return;
        Generation replacement = new Generation(width, height, mask);
        Generation previous = this.current; this.current = replacement; previous.release();
    }
    /** Concurrent/nested frames get independent storage; previous generations survive resize/disposal through their leases. */
    public Frame retain() {
        this.ensureOpen();
        if (this.current.references != 1) {
            Generation replacement = new Generation(this.current.width, this.current.height, this.current.mask);
            Generation previous = this.current; this.current = replacement; previous.release();
        }
        this.current.references++; return new Frame(this.current);
    }
    private void ensureOpen() { if (this.closed) throw new IllegalStateException("Native OIT owner is closed"); }
    @Override public void close() { if (!this.closed) { this.closed = true; this.current.release(); } }

    public final class Frame implements AutoCloseable {
        private final Generation generation;
        private boolean closed, composed;
        private Frame(Generation generation) { this.generation = generation; }
        public int mask() { this.ensureOpen(); return this.generation.mask; }
        public List<DynamicBufferType> types() { this.ensureOpen(); return this.generation.types; }
        public GpuTextureView texture(DynamicBufferType type) { return this.generation.accum.color(this.index(type)).view(); }
        public GpuTextureView producedWeights(int group) {
            this.ensureOpen();
            if (group < 0 || group >= weightTargetCount(this.generation.mask)) throw new IllegalArgumentException("Unknown OIT weight group");
            return this.generation.accum.color(this.generation.types.size() + group).view();
        }
        public void clear(CommandEncoder encoder) {
            this.ensureOpen();
            if (this.generation.accum != null) this.generation.accum.clear(encoder, new Vector4f(0), 0);
            this.composed = false;
        }
        public RenderPassDescriptor extend(RenderPassDescriptor caller) {
            this.ensureOpen();
            if (this.generation.mask == 0) return caller;
            validateAttachmentCount(caller.colorAttachments().size() + extraTargetCount(this.generation.mask));
            for (var attachment : caller.colorAttachments()) if (attachment != null) this.validate(attachment.textureView());
            if (caller.depthAttachment() != null) this.validate(caller.depthAttachment().textureView());
            var colors = new ArrayList<>(caller.colorAttachments());
            for (int index = 0; index < extraTargetCount(this.generation.mask); index++)
                colors.add(new RenderPassDescriptor.Attachment<Optional<Vector4fc>>(this.generation.accum.color(index).view(), Optional.empty()));
            return new RenderPassDescriptor(caller.label(), colors, caller.depthAttachment(), caller.renderArea());
        }
        /** Appends additive float targets to a genuine native ACCUMULATE pipeline, retaining all original immutable state. */
        public RenderPipeline pipeline(RenderPipeline original) {
            this.ensureOpen();
            if (this.generation.mask == 0) return original;
            if (!original.getShaderDefines().flags().contains("OIT_ACCUMULATE") && !original.getShaderDefines().values().containsKey("OIT_ACCUMULATE"))
                throw new IllegalArgumentException("OIT properties require the native ACCUMULATE stage");
            validateAttachmentCount(original.getColorTargetStates().size() + extraTargetCount(this.generation.mask));
            var colors = new ArrayList<>(original.getColorTargetStates());
            Optional<BlendFunction> nativeBlend = colors.stream().filter(Objects::nonNull).findFirst().orElseThrow().blendFunction();
            if (nativeBlend.isEmpty()) throw new IllegalArgumentException("Native OIT accumulation must use additive blending");
            for (int index = 0; index < extraTargetCount(this.generation.mask); index++)
                colors.add(new ColorTargetState(nativeBlend, GpuFormat.RGBA16_FLOAT, ColorTargetState.WRITE_ALL));
            var snippet = new RenderPipeline.Snippet(original.getShaders(), Optional.of(original.getShaderDefines()), Optional.of(original.getBindGroupLayouts()),
                    colors.toArray(ColorTargetState[]::new), colors.size(), Optional.ofNullable(original.getDepthStencilState()),
                    Optional.of(original.getPolygonMode()), Optional.of(original.isCull()), original.getVertexFormatBindings().toArray(VertexFormat[]::new),
                    Optional.of(original.getPrimitiveTopology()), original.pushConstantSize());
            return RenderPipeline.builder(snippet).withLocation(original.getLocation()).build();
        }
        /**
         * Record after the actual native accumulation stage. Copy each opaque property before resolving it, avoiding feedback.
         * Coefficients and vanillaAccum provide native coverage/normalization; depthBounds is validated but final property passes never write depth.
         * Keep this frame and every borrowed view live until the caller submits the encoder.
         */
        public void compose(CommandEncoder encoder, Function<DynamicBufferType, GpuTextureView> finalViews,
                            GpuTextureView vanillaAccum, GpuTextureView depthBounds, GpuTextureView[] coefficients) {
            this.ensureOpen();
            if (this.composed) throw new IllegalStateException("OIT properties already composed for this frame");
            if (this.generation.mask == 0) { this.composed = true; return; }
            this.validate(vanillaAccum); this.validate(depthBounds);
            if (coefficients.length != LevelRenderer.OIT_TRANSMITTANCE_TARGET_COUNT) throw new IllegalArgumentException("Native OIT coefficient target count differs");
            for (GpuTextureView coefficient : coefficients) this.validate(coefficient);
            var destinations = new ArrayList<GpuTextureView>();
            for (int index = 0; index < this.generation.types.size(); index++) {
                DynamicBufferType type = this.generation.types.get(index);
                GpuTextureView destination = Objects.requireNonNull(finalViews.apply(type)); this.validate(destination);
                if (destination.texture().getFormat() != type.gpuFormat()) throw new IllegalArgumentException("Final OIT property format differs: " + type);
                if ((destination.texture().usage() & (GpuTexture.USAGE_COPY_SRC | GpuTexture.USAGE_RENDER_ATTACHMENT)) != (GpuTexture.USAGE_COPY_SRC | GpuTexture.USAGE_RENDER_ATTACHMENT))
                    throw new IllegalArgumentException("Final OIT property needs copy-source and render-attachment usage");
                GpuTextureView snapshot = this.generation.opaque.color(index).view();
                if (destination.texture() == snapshot.texture()) throw new IllegalArgumentException("OIT opaque snapshot aliases its destination");
                encoder.copyTextureToTexture(destination.texture(), snapshot.texture(), 0, 0, 0, 0, 0, this.generation.width, this.generation.height);
                destinations.add(destination);
            }
            for (int index = 0; index < this.generation.types.size(); index++) {
                GpuShaderProgram program = this.generation.composers.get(index);
                program.setUniformBlock("Projection", com.mojang.blaze3d.systems.RenderSystem.getProjectionMatrixBuffer());
                program.setTexture("OpaqueProperty", this.generation.opaque.color(index).view(), this.generation.sampler);
                program.setTexture("PropertyAccum", this.generation.accum.color(index).view(), this.generation.sampler);
                program.setTexture("PropertyWeights", this.producedWeights(index / 4), this.generation.sampler);
                program.setTexture("VanillaAccum", vanillaAccum, this.generation.sampler);
                for (int coefficient = 0; coefficient < coefficients.length; coefficient++) program.setTexture("Coeff" + coefficient, coefficients[coefficient], this.generation.sampler);
                try (var pass = encoder.createRenderPass(() -> "Resolve native OIT property", destinations.get(index), Optional.empty())) {
                    program.bind(pass); pass.draw(3, 1, 0, 0);
                }
            }
            this.composed = true;
        }
        private int index(DynamicBufferType type) { this.ensureOpen(); int index = this.generation.types.indexOf(type); if (index < 0) throw new IllegalArgumentException("OIT property not selected: " + type); return index; }
        private void validate(GpuTextureView view) {
            if (view == null || view.isClosed() || view.texture().isClosed() || view.getWidth(0) != this.generation.width || view.getHeight(0) != this.generation.height)
                throw new IllegalArgumentException("Native OIT borrowed views must be live and match frame dimensions");
        }
        private void ensureOpen() { if (this.closed) throw new IllegalStateException("Native OIT frame is closed"); }
        @Override public void close() { if (!this.closed) { this.closed = true; this.generation.release(); } }
    }
    private void validateAttachmentCount(int count) {
        int limit = Math.min(ColorTargetState.MAX_COLOR_TARGETS, this.device.getDeviceInfo().limits().maxColorAttachments());
        if (count > limit) throw new IllegalArgumentException("Native OIT dynamic payloads/weights need " + count + " color attachments, device supports " + limit);
    }
    private final class Generation {
        final int width, height, mask;
        final List<DynamicBufferType> types;
        final GpuFramebuffer accum, opaque;
        final List<GpuShaderProgram> composers;
        final GpuSampler sampler;
        int references = 1;
        Generation(int width, int height, int mask) {
            int known = (1 << DynamicBufferType.values().length) - 1;
            if ((mask & ~known) != 0 || width <= 0 || height <= 0) throw new IllegalArgumentException("Invalid native OIT dimensions/mask");
            validateAttachmentCount(extraTargetCount(mask) + 1);
            this.width = width; this.height = height; this.mask = mask; this.types = List.of(DynamicBufferType.decode(mask));
            GpuFramebuffer accumulation = null, snapshots = null;
            GpuSampler ownedSampler = null;
            var programs = new ArrayList<GpuShaderProgram>();
            try {
                if (mask != 0) {
                    var outputs = GpuFramebuffer.builder("Native OIT dynamic accumulation", width, height);
                    for (int index = 0; index < extraTargetCount(mask); index++) outputs.color(GpuFormat.RGBA16_FLOAT);
                    accumulation = outputs.build(NativeDynamicOit.this.device);
                    var copies = GpuFramebuffer.builder("Native OIT opaque property snapshots", width, height);
                    this.types.forEach(type -> copies.color(type.gpuFormat())); snapshots = copies.build(NativeDynamicOit.this.device);
                    ownedSampler = NativeDynamicOit.this.device.createSampler(AddressMode.CLAMP_TO_EDGE, AddressMode.CLAMP_TO_EDGE, FilterMode.NEAREST, FilterMode.NEAREST, 1, OptionalDouble.empty());
                    for (int index = 0; index < this.types.size(); index++) programs.add(compile(this.types.get(index).gpuFormat(), index % 4));
                }
            } catch (RuntimeException | Error failure) {
                programs.forEach(GpuShaderProgram::close); if (ownedSampler != null) ownedSampler.close();
                if (snapshots != null) snapshots.close(); if (accumulation != null) accumulation.close(); throw failure;
            }
            this.accum = accumulation; this.opaque = snapshots; this.sampler = ownedSampler; this.composers = List.copyOf(programs);
        }
        void release() {
            if (--this.references != 0) return;
            this.composers.forEach(GpuShaderProgram::close);
            if (this.sampler != null) this.sampler.close(); if (this.opaque != null) this.opaque.close(); if (this.accum != null) this.accum.close();
        }
    }
    private GpuShaderProgram compile(GpuFormat format, int channel) {
        var bindings = BindGroupLayout.builder();
        // The genuine native include declares Projection; native reflection retains its declaration for this resolve too.
        bindings.withUniform("Projection", UniformType.UNIFORM_BUFFER);
        for (String sampler : List.of("OpaqueProperty", "PropertyAccum", "PropertyWeights", "VanillaAccum")) bindings.withUniform(sampler, UniformType.COMBINED_IMAGE_SAMPLER);
        for (int index = 0; index < LevelRenderer.OIT_TRANSMITTANCE_TARGET_COUNT; index++) bindings.withUniform("Coeff" + index, UniformType.COMBINED_IMAGE_SAMPLER);
        RenderPipeline pipeline = RenderPipeline.builder().withLocation(Identifier.parse("veil:pipeline/dynamic_oit_" + format.name().toLowerCase(Locale.ROOT) + "_" + channel))
                .withVertexShader(Identifier.parse("veil:core/dynamic_oit_composite")).withFragmentShader(Identifier.parse("veil:core/dynamic_oit_composite"))
                .withPrimitiveTopology(PrimitiveTopology.TRIANGLES).withCull(false).withDepthStencilState(Optional.empty())
                .withBindGroupLayout(bindings.build()).withShaderDefine("OIT").withShaderDefine("OIT_COEFF_COUNT", LevelRenderer.OIT_COEFFICIENT_COUNT)
                .withShaderDefine("OIT_WAVELET_RANK", LevelRenderer.OIT_WAVELET_RANK).withShaderDefine("VEIL_OIT_WEIGHT_CHANNEL", channel)
                .withColorTargetState(new ColorTargetState(Optional.empty(), format, ColorTargetState.WRITE_ALL)).build();
        try (var source = new GpuShaderSource(this.resources)) { return new GpuShaderProgram(this.device, pipeline, source, Runnable::run); }
    }
}
