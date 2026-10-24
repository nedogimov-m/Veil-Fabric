package foundry.veil.api.client.render.gpu;

import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.commands.CommandEncoder;
import com.mojang.renderpearl.api.device.GpuDevice;
import com.mojang.renderpearl.api.pipeline.BindGroupLayout;
import com.mojang.renderpearl.api.pipeline.BlendFunction;
import com.mojang.renderpearl.api.pipeline.ColorTargetState;
import com.mojang.renderpearl.api.pipeline.PrimitiveTopology;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import com.mojang.renderpearl.api.pipeline.UniformType;
import com.mojang.renderpearl.api.textures.AddressMode;
import com.mojang.renderpearl.api.textures.FilterMode;
import com.mojang.renderpearl.api.textures.GpuSampler;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.ResourceManager;
import org.jetbrains.annotations.Nullable;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.concurrent.Executor;

/** Fullscreen, optionally scaled color blits with native shaders, samplers and pipelines. */
public final class GpuFullscreenPass implements AutoCloseable {
    private static final Identifier SHADER = Identifier.fromNamespaceAndPath("veil", "core/gpu_blit");
    private static final BindGroupLayout INPUT = BindGroupLayout.builder()
            .withUniform("InSampler", UniformType.COMBINED_IMAGE_SAMPLER).build();
    private final GpuDevice device;
    private final ResourceManager resources;
    private final Executor workers;
    private final Optional<BlendFunction> blend;
    private final GpuSampler sampler;
    private final Map<GpuFormat, GpuShaderProgram> pipelines = new HashMap<>();
    private boolean closed;

    public GpuFullscreenPass(GpuDevice device, ResourceManager resources, Executor workers,
                             FilterMode filtering, @Nullable BlendFunction blend) {
        this.device = Objects.requireNonNull(device);
        this.resources = Objects.requireNonNull(resources);
        this.workers = Objects.requireNonNull(workers);
        this.blend = Optional.ofNullable(blend);
        this.sampler = device.createSampler(AddressMode.CLAMP_TO_EDGE, AddressMode.CLAMP_TO_EDGE,
                filtering, filtering, 1, OptionalDouble.empty());
    }

    /** Record a real three-vertex fullscreen draw. The caller submits its encoder. */
    public void blit(CommandEncoder encoder, GpuTextureView source, GpuTextureView target) {
        this.ensureOpen();
        if (source.isClosed() || target.isClosed()) throw new IllegalStateException("Cannot blit closed texture views");
        if (source.texture() == target.texture()) throw new IllegalArgumentException("A blit cannot sample its own render attachment");
        requireFloatColor(source.texture().getFormat());
        requireFloatColor(target.texture().getFormat());
        GpuShaderProgram pipeline = this.pipelines.computeIfAbsent(target.texture().getFormat(), this::compile);
        pipeline.setTexture("InSampler", source, this.sampler);
        pipeline.prepare(encoder);
        try (var pass = encoder.createRenderPass(() -> "Veil fullscreen blit", target, Optional.empty())) {
            pipeline.bind(pass);
            pass.draw(3, 1, 0, 0);
        }
    }

    private GpuShaderProgram compile(GpuFormat format) {
        RenderPipeline definition = RenderPipeline.builder()
                .withLocation(Identifier.fromNamespaceAndPath("veil", "pipeline/gpu_blit_" + format.name().toLowerCase(java.util.Locale.ROOT)))
                .withVertexShader(SHADER).withFragmentShader(SHADER)
                .withBindGroupLayout(INPUT).withPrimitiveTopology(PrimitiveTopology.TRIANGLES)
                .withCull(false).withDepthStencilState(Optional.empty())
                .withColorTargetState(new ColorTargetState(this.blend, format, ColorTargetState.WRITE_ALL)).build();
        try (var source = new GpuShaderSource(this.resources)) {
            return new GpuShaderProgram(this.device, definition, source, this.workers);
        }
    }

    private static void requireFloatColor(GpuFormat format) {
        if (!format.hasColorAspect()) throw new IllegalArgumentException("Blits require color formats: " + format);
        // sampler2D / vec4 output supports normalized and float formats. Integer
        // attachments need a distinct isampler/usampler pipeline, never implicit conversion.
        String type = format.componentType().name();
        if (type.startsWith("UINT_") || type.startsWith("SINT_")) throw new IllegalArgumentException("Integer color blit needs a typed integer shader: " + format);
    }

    /** Drop compiled shaders on resource reload; samplers remain valid for this device. */
    public void invalidatePipelines() {
        this.ensureOpen();
        for (GpuShaderProgram pipeline : this.pipelines.values()) pipeline.close();
        this.pipelines.clear();
    }
    private void ensureOpen() { if (this.closed) throw new IllegalStateException("Fullscreen pass is closed"); }
    @Override public void close() {
        if (this.closed) return;
        this.invalidatePipelines();
        this.closed = true;
        this.sampler.close();
    }
}
