package foundry.veil.api.client.render.post.stage;

import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.pipeline.BindGroupLayout;
import com.mojang.renderpearl.api.pipeline.ColorTargetState;
import com.mojang.renderpearl.api.pipeline.CompareOp;
import com.mojang.renderpearl.api.pipeline.DepthStencilState;
import com.mojang.renderpearl.api.pipeline.PrimitiveTopology;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import com.mojang.renderpearl.api.pipeline.UniformType;
import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import foundry.veil.api.client.registry.PostPipelineStageRegistry;
import foundry.veil.api.client.render.framebuffer.FramebufferManager;
import foundry.veil.api.client.render.framebuffer.VeilFramebuffers;
import foundry.veil.api.client.render.gpu.GpuFramebuffer;
import foundry.veil.api.client.render.gpu.GpuShaderProgram;
import foundry.veil.api.client.render.gpu.GpuUniformBuffer;
import foundry.veil.api.client.render.post.PostPipeline;
import foundry.veil.api.client.render.post.uniform.UniformValue;
import foundry.veil.api.client.render.texture.TextureFilter;
import foundry.veil.api.client.render.shader.ShaderManager;
import net.minecraft.resources.Identifier;
import org.jetbrains.annotations.Nullable;
import org.joml.Vector4f;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Resource-defined fullscreen triangle with native immutable render state.
 * Migrated shaders declare a matching std140 block (PostUniforms by default),
 * beginning with vec2 InSize, vec2 OutSize followed by configured fields sorted
 * by name. ShaderManager retains ownership of the cached native program.
 */
public final class BlitPostStage extends FramebufferPostStage {
    public static final MapCodec<BlitPostStage> CODEC = RecordCodecBuilder.<BlitPostStage>mapCodec(instance -> instance.group(
            Identifier.CODEC.fieldOf("shader").forGetter(BlitPostStage::getShaderId),
            Codec.unboundedMap(Codec.STRING, UniformValue.CODEC).optionalFieldOf("uniforms", Collections.emptyMap()).forGetter(BlitPostStage::getUniforms),
            FramebufferManager.FRAMEBUFFER_CODEC.optionalFieldOf("in").forGetter(stage -> Optional.ofNullable(stage.getIn())),
            FramebufferManager.FRAMEBUFFER_CODEC.optionalFieldOf("out", VeilFramebuffers.POST).forGetter(BlitPostStage::getOut),
            Codec.BOOL.optionalFieldOf("clear", true).forGetter(BlitPostStage::clearOut),
            Codec.STRING.optionalFieldOf("uniformBlock", "PostUniforms").forGetter(BlitPostStage::getUniformBlock)
    ).apply(instance, (shader, uniforms, in, out, clear, block) -> new BlitPostStage(shader, uniforms, in.orElse(null), out, clear, block)))
            .flatXmap(stage -> stage.getOut().equals(stage.getIn())
                    ? DataResult.error(() -> "Input and output targets cannot be the same") : DataResult.success(stage), DataResult::success);
    private final Identifier shader;
    private final Map<String, UniformValue> uniforms;
    private final String block;
    private final GpuUniformBuffer.Layout layout;
    private final Map<State, RenderPipeline> states = new HashMap<>();
    @Nullable private ShaderManager owner;

    public BlitPostStage(Identifier shader, Map<String, UniformValue> uniforms, @Nullable Identifier in, Identifier out, boolean clear) {
        this(shader, uniforms, in, out, clear, "PostUniforms");
    }
    public BlitPostStage(Identifier shader, Map<String, UniformValue> uniforms, @Nullable Identifier in, Identifier out, boolean clear, String block) {
        super(in, out, clear);
        this.shader = java.util.Objects.requireNonNull(shader);
        this.uniforms = new LinkedHashMap<>(uniforms);
        this.block = java.util.Objects.requireNonNull(block);
        var builder = GpuUniformBuffer.layoutBuilder()
                .field("InSize", GpuUniformBuffer.ValueType.VEC2).field("OutSize", GpuUniformBuffer.ValueType.VEC2);
        uniforms.entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(entry -> builder.field(entry.getKey(), entry.getValue().valueType()));
        this.layout = builder.build();
        this.layout.declaration(block);
    }

    @Override public void apply(Context context) {
        if (this.owner != null && this.owner != context.shaders()) this.free();
        this.owner = context.shaders();
        GpuFramebuffer input = this.getIn() != null ? context.requireFramebuffer(this.getIn()) : null;
        GpuFramebuffer output = context.getFramebufferOrDraw(this.getOut());
        if (input == output) throw new IllegalArgumentException("A post stage cannot sample its output framebuffer");
        State state = new State(java.util.stream.IntStream.range(0, output.colorCount())
                .mapToObj(index -> output.color(index).texture().getFormat()).toList(),
                output.hasDepth() ? output.depth().texture().getFormat() : null,
                context.colorMask(), context.depthComparison(), context.writeDepth());
        RenderPipeline drawState = this.states.computeIfAbsent(state, this::createState);
        GpuShaderProgram program = context.shaders().getShader(this.shader, drawState, context.workers());
        program.clearTextureBindings();
        context.shaders().bindTextures(this.shader, program, context);
        context.applySamplers(program);
        if (input != null) {
            for (int index = 0; index < input.colorCount(); index++) {
                String name = "DiffuseSampler" + index;
                if (program.hasUniform(name)) program.setTexture(name, input.color(index).view(),
                        context.framebufferFilter(this.getIn(), index, false));
            }
            if (input.hasDepth() && program.hasUniform("DiffuseDepthSampler")) {
                program.setTexture("DiffuseDepthSampler", input.depth().view(), context.framebufferFilter(this.getIn(), 0, true));
            }
        }
        for (int index = 0; index < output.colorCount(); index++) {
            if (program.usesTexture(output.color(index).texture())) throw new IllegalArgumentException("Post shader samples its color output: " + this.shader);
        }
        if (output.hasDepth() && program.usesTexture(output.depth().texture())) throw new IllegalArgumentException("Post shader samples its depth output: " + this.shader);
        GpuUniformBuffer uniforms = program.getUniformBlock(this.block);
        if (uniforms == null) uniforms = program.createUniformBlock(this.block, this.layout);
        else if (!uniforms.layout().equals(this.layout)) throw new IllegalStateException("Post uniform layout changed for cached program " + this.shader);
        uniforms.setVector("InSize", input != null ? input.width() : 1, input != null ? input.height() : 1);
        uniforms.setVector("OutSize", output.width(), output.height());
        for (var entry : this.uniforms.entrySet()) entry.getValue().apply(uniforms, entry.getKey());
        program.prepare(context.encoder());
        if (this.clearOut()) output.clear(context.encoder(), new Vector4f(), com.mojang.blaze3d.systems.RenderSystem.DEFAULT_DEPTH_CLEAR_VALUE);
        try (var pass = output.beginPass(context.encoder())) {
            program.bind(pass);
            pass.draw(3, 1, 0, 0);
        }
    }
    private RenderPipeline createState(State state) {
        var builder = RenderPipeline.builder().withLocation(this.shader)
                .withVertexShader(this.shader).withFragmentShader(this.shader)
                .withPrimitiveTopology(PrimitiveTopology.TRIANGLES).withCull(false)
                .withBindGroupLayout(BindGroupLayout.builder().withUniform(this.block, UniformType.UNIFORM_BUFFER).build())
                .withDepthStencilState(state.depth != null ? Optional.of(new DepthStencilState(state.comparison, state.writeDepth)) : Optional.empty());
        for (GpuFormat format : state.colors) builder.withColorTargetState(new ColorTargetState(Optional.empty(), format, state.colorMask));
        return builder.build();
    }
    /** Change values while retaining the shader's declared immutable std140 layout. */
    public void setUniform(String name, UniformValue value) {
        var field = this.layout.fields().get(name);
        if (field == null || name.equals("InSize") || name.equals("OutSize")) throw new IllegalArgumentException("Unknown configurable post uniform: " + name);
        if (value.valueType() != field.type()) throw new IllegalArgumentException("Post uniform type changed: " + name);
        this.uniforms.put(name, value);
    }
    public String getUniformBlock() { return this.block; }
    public GpuUniformBuffer.Layout getUniformLayout() { return this.layout; }
    public Identifier getShaderId() { return this.shader; }
    public Map<String, UniformValue> getUniforms() { return Collections.unmodifiableMap(this.uniforms); }
    @Override public PostPipelineStageRegistry.PipelineType<? extends PostPipeline> getType() { return PostPipelineStageRegistry.BLIT.get(); }
    @Override public void free() {
        if (this.owner != null) this.states.values().forEach(state -> this.owner.releaseShader(this.shader, state));
        this.owner = null; this.states.clear();
    }
    private record State(List<GpuFormat> colors, @Nullable GpuFormat depth, int colorMask, CompareOp comparison, boolean writeDepth) { }
}
