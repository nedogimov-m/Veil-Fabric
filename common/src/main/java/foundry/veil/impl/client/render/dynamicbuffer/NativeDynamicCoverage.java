package foundry.veil.impl.client.render.dynamicbuffer;

import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.commands.CommandEncoder;
import com.mojang.renderpearl.api.commands.RenderPassDescriptor;
import com.mojang.renderpearl.api.device.GpuDevice;
import com.mojang.renderpearl.api.pipeline.*;
import com.mojang.renderpearl.api.textures.*;
import foundry.veil.api.client.render.dynamicbuffer.DynamicBufferType;
import foundry.veil.api.client.render.gpu.*;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.ResourceManager;
import org.joml.Vector4fc;

import java.util.*;
import java.util.function.Function;

/** Independent native geometry coverage and generic vec4 payloads for ordinary opaque/translucent draws. */
public final class NativeDynamicCoverage implements AutoCloseable {
    private final GpuDevice device;
    private final ResourceManager resources;
    private Generation current;
    private boolean closed;
    public NativeDynamicCoverage(GpuDevice device, ResourceManager resources, int width, int height, int mask) {
        this.device = Objects.requireNonNull(device); this.resources = Objects.requireNonNull(resources);
        this.current = new Generation(width, height, mask);
    }
    public static int packedTargetCount(int mask) { return (DynamicBufferType.decode(mask).length + 2) / 3; }
    public static int extraTargetCount(int mask) { return DynamicBufferType.decode(mask).length + packedTargetCount(mask); }
    public void configure(int width, int height, int mask) {
        this.ensureOpen();
        if (this.current.width == width && this.current.height == height && this.current.mask == mask) return;
        Generation replacement = new Generation(width, height, mask), previous = this.current;
        this.current = replacement; previous.release();
    }
    public Frame retain() {
        this.ensureOpen();
        if (this.current.references != 1) {
            Generation replacement = new Generation(this.current.width, this.current.height, this.current.mask), previous = this.current;
            this.current = replacement; previous.release();
        }
        this.current.references++; return new Frame(this.current);
    }
    private void ensureOpen() { if (this.closed) throw new IllegalStateException("Native property coverage owner is closed"); }
    @Override public void close() { if (!this.closed) { this.closed = true; this.current.release(); } }

    public final class Frame implements AutoCloseable {
        private final Generation generation;
        private boolean closed;
        private Frame(Generation generation) { this.generation = generation; }
        public int mask() { this.ensureOpen(); return this.generation.mask; }
        public GpuTextureView payload(DynamicBufferType type) { this.ensureOpen(); return this.generation.storage.color(this.index(type)).view(); }
        public GpuTextureView packedW(int group) {
            this.ensureOpen();
            if (group < 0 || group >= packedTargetCount(this.mask())) throw new IllegalArgumentException("Unknown native property W group");
            return this.generation.storage.color(this.generation.types.size() + group).view();
        }
        /** Run before opening the native world pass, so external post/OIT edits and typed-format quantization are preserved. */
        public void initialize(CommandEncoder encoder, Function<DynamicBufferType, GpuTextureView> values,
                               GpuTextureView primaryColor, Optional<Vector4fc> clearColor) {
            this.ensureOpen(); if (this.mask() == 0) return;
            this.validate(primaryColor);
            this.generation.initializer.setTexture("NativeColor", primaryColor, this.generation.sampler);
            var settings = java.nio.ByteBuffer.allocateDirect(16).order(java.nio.ByteOrder.nativeOrder());
            settings.putFloat(clearColor.isPresent() ? 1 : 0).putFloat(clearColor.map(Vector4fc::w).orElse(0f)).putFloat(0).putFloat(0).flip();
            this.generation.initializer.setUniformBlock("CoverageInit", encoder.transientMemory().uploadGpu(settings,
                    NativeDynamicCoverage.this.device.getDeviceInfo().limits().minUniformOffsetAlignment(), com.mojang.renderpearl.api.buffers.GpuBuffer.USAGE_UNIFORM));
            for (int index = 0; index < this.generation.types.size(); index++) {
                GpuTextureView view = Objects.requireNonNull(values.apply(this.generation.types.get(index))); this.validate(view);
                if (view.texture() == this.payload(this.generation.types.get(index)).texture()) throw new IllegalArgumentException("Native property coverage input aliases storage");
                this.generation.initializer.setTexture("Input" + index, view, this.generation.sampler);
            }
            try (var pass = this.generation.storage.beginPass(encoder)) { this.generation.initializer.bind(pass); pass.draw(3, 1, 0, 0); }
        }
        public RenderPassDescriptor extend(RenderPassDescriptor caller) {
            this.ensureOpen();
            NativeDynamicCoverage.this.validateCount(caller.colorAttachments().size() + extraTargetCount(this.mask()));
            for (var attachment : caller.colorAttachments()) if (attachment != null) this.validate(attachment.textureView());
            if (caller.depthAttachment() != null) this.validate(caller.depthAttachment().textureView());
            var colors = new ArrayList<>(caller.colorAttachments());
            for (int index = 0; index < extraTargetCount(this.mask()); index++)
                colors.add(new RenderPassDescriptor.Attachment<Optional<Vector4fc>>(this.generation.storage.color(index).view(), Optional.empty()));
            return new RenderPassDescriptor(caller.label(), colors, caller.depthAttachment(), caller.renderArea());
        }
        public RenderPipeline pipeline(RenderPipeline original) {
            this.ensureOpen(); if (this.mask() == 0) return original;
            NativeDynamicCoverage.this.validateCount(original.getColorTargetStates().size() + extraTargetCount(this.mask()));
            var colors = new ArrayList<>(original.getColorTargetStates());
            Optional<BlendFunction> blend = colors.stream().filter(Objects::nonNull).findFirst().orElseThrow().blendFunction();
            // W occupies RGB in the packed targets, so it receives the identical color equation and geometry alpha as XYZ.
            for (int index = 0; index < extraTargetCount(this.mask()); index++)
                colors.add(new ColorTargetState(blend, GpuFormat.RGBA16_FLOAT, ColorTargetState.WRITE_ALL));
            var snippet = new RenderPipeline.Snippet(original.getShaders(), Optional.of(original.getShaderDefines()), Optional.of(original.getBindGroupLayouts()),
                    colors.toArray(ColorTargetState[]::new), colors.size(), Optional.ofNullable(original.getDepthStencilState()),
                    Optional.of(original.getPolygonMode()), Optional.of(original.isCull()), original.getVertexFormatBindings().toArray(com.mojang.renderpearl.api.vertex.VertexFormat[]::new),
                    Optional.of(original.getPrimitiveTopology()), original.pushConstantSize());
            return RenderPipeline.builder(snippet).withLocation(original.getLocation()).build();
        }
        /** Record after closing the actual native pass; output is available immediately to subsequent post/OIT consumers. */
        public void resolve(CommandEncoder encoder, Function<DynamicBufferType, GpuTextureView> values) {
            this.ensureOpen();
            for (int index = 0; index < this.generation.types.size(); index++) {
                DynamicBufferType type = this.generation.types.get(index);
                GpuTextureView destination = Objects.requireNonNull(values.apply(type)); this.validate(destination);
                if (destination.texture().getFormat() != type.gpuFormat()) throw new IllegalArgumentException("Native property resolve output format differs: " + type);
                GpuShaderProgram program = this.generation.resolvers.get(index);
                program.setTexture("Payload", this.payload(type), this.generation.sampler);
                program.setTexture("PackedW", this.packedW(index / 3), this.generation.sampler);
                try (var pass = encoder.createRenderPass(() -> "Resolve native generic property coverage", destination, Optional.empty())) {
                    program.bind(pass); pass.draw(3, 1, 0, 0);
                }
            }
        }
        private int index(DynamicBufferType type) { int index = this.generation.types.indexOf(type); if (index < 0) throw new IllegalArgumentException("Native property not selected: " + type); return index; }
        private void validate(GpuTextureView view) {
            if (view.isClosed() || view.texture().isClosed() || view.getWidth(0) != this.generation.width || view.getHeight(0) != this.generation.height)
                throw new IllegalArgumentException("Native property coverage views must be live and match frame dimensions");
        }
        private void ensureOpen() { if (this.closed) throw new IllegalStateException("Native property coverage frame is closed"); }
        @Override public void close() { if (!this.closed) { this.closed = true; this.generation.release(); } }
    }
    private void validateCount(int count) {
        int limit = Math.min(ColorTargetState.MAX_COLOR_TARGETS, this.device.getDeviceInfo().limits().maxColorAttachments());
        if (count > limit) throw new IllegalArgumentException("Native property coverage needs " + count + " color attachments, device supports " + limit);
    }
    private final class Generation {
        final int width, height, mask;
        final List<DynamicBufferType> types;
        final GpuFramebuffer storage;
        final GpuSampler sampler;
        final GpuShaderProgram initializer;
        final List<GpuShaderProgram> resolvers;
        int references = 1;
        Generation(int width, int height, int mask) {
            if ((mask & ~((1 << DynamicBufferType.values().length) - 1)) != 0 || width <= 0 || height <= 0) throw new IllegalArgumentException("Invalid native property coverage dimensions/mask");
            validateCount(1 + extraTargetCount(mask));
            this.width = width; this.height = height; this.mask = mask; this.types = List.of(DynamicBufferType.decode(mask));
            GpuFramebuffer target = null; GpuSampler sampled = null; GpuShaderProgram initialize = null;
            var compiled = new ArrayList<GpuShaderProgram>();
            try {
                if (mask != 0) {
                    var builder = GpuFramebuffer.builder("Native generic property coverage storage", width, height);
                    for (int index = 0; index < extraTargetCount(mask); index++) builder.color(GpuFormat.RGBA16_FLOAT);
                    target = builder.build(NativeDynamicCoverage.this.device);
                    sampled = NativeDynamicCoverage.this.device.createSampler(AddressMode.CLAMP_TO_EDGE, AddressMode.CLAMP_TO_EDGE, FilterMode.NEAREST, FilterMode.NEAREST, 1, OptionalDouble.empty());
                    var bindings = BindGroupLayout.builder().withUniform("NativeColor", UniformType.COMBINED_IMAGE_SAMPLER)
                            .withUniform("CoverageInit", UniformType.UNIFORM_BUFFER);
                    for (int index = 0; index < this.types.size(); index++) bindings.withUniform("Input" + index, UniformType.COMBINED_IMAGE_SAMPLER);
                    var state = base("initialize").withBindGroupLayout(bindings.build()).withShaderDefine("VEIL_CAPTURE_COUNT", this.types.size())
                            .withShaderDefine("VEIL_CAPTURE_SECOND_LOCATION", this.types.size() + 1);
                    for (int index = 0; index < extraTargetCount(mask); index++) state.withColorTargetState(index, new ColorTargetState(Optional.empty(), GpuFormat.RGBA16_FLOAT, ColorTargetState.WRITE_ALL));
                    initialize = compile(state.withFragmentShader(Identifier.parse("veil:core/dynamic_coverage_initialize")).build());
                    for (int index = 0; index < this.types.size(); index++) {
                        var resolveBindings = BindGroupLayout.builder().withUniform("Payload", UniformType.COMBINED_IMAGE_SAMPLER).withUniform("PackedW", UniformType.COMBINED_IMAGE_SAMPLER).build();
                        compiled.add(compile(base("resolve_" + this.types.get(index).getName()).withFragmentShader(Identifier.parse("veil:core/dynamic_coverage_resolve"))
                                .withBindGroupLayout(resolveBindings).withShaderDefine("VEIL_CAPTURE_W_CHANNEL", index % 3)
                                .withColorTargetState(new ColorTargetState(Optional.empty(), this.types.get(index).gpuFormat(), ColorTargetState.WRITE_ALL)).build()));
                    }
                }
            } catch (RuntimeException | Error failure) {
                compiled.forEach(GpuShaderProgram::close); if (initialize != null) initialize.close(); if (sampled != null) sampled.close(); if (target != null) target.close(); throw failure;
            }
            this.storage = target; this.sampler = sampled; this.initializer = initialize; this.resolvers = List.copyOf(compiled);
        }
        void release() {
            if (--this.references != 0) return;
            this.resolvers.forEach(GpuShaderProgram::close); if (this.initializer != null) this.initializer.close();
            if (this.sampler != null) this.sampler.close(); if (this.storage != null) this.storage.close();
        }
    }
    private RenderPipeline.Builder base(String name) {
        return RenderPipeline.builder().withLocation(Identifier.parse("veil:pipeline/dynamic_coverage_" + name))
                .withVertexShader(Identifier.parse("veil:core/dynamic_coverage")).withPrimitiveTopology(PrimitiveTopology.TRIANGLES)
                .withCull(false).withDepthStencilState(Optional.empty());
    }
    private GpuShaderProgram compile(RenderPipeline definition) {
        try (var source = new GpuShaderSource(this.resources)) { return new GpuShaderProgram(this.device, definition, source, Runnable::run); }
    }
}
