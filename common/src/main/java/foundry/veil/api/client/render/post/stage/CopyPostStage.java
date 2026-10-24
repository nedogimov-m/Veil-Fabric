package foundry.veil.api.client.render.post.stage;

import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import foundry.veil.api.client.registry.PostPipelineStageRegistry;
import foundry.veil.api.client.render.framebuffer.FramebufferManager;
import foundry.veil.api.client.render.post.PostPipeline;
import net.minecraft.resources.Identifier;

/** Exact native copies, or a real fullscreen color draw for scaled/filtered copies. */
public final class CopyPostStage extends FramebufferPostStage {
    public static final MapCodec<CopyPostStage> CODEC = RecordCodecBuilder.mapCodec(instance -> instance.group(
            FramebufferManager.FRAMEBUFFER_CODEC.fieldOf("in").forGetter(CopyPostStage::getIn),
            FramebufferManager.FRAMEBUFFER_CODEC.fieldOf("out").forGetter(CopyPostStage::getOut),
            Codec.BOOL.optionalFieldOf("color", true).forGetter(CopyPostStage::copyColor),
            Codec.BOOL.optionalFieldOf("depth", false).forGetter(CopyPostStage::copyDepth),
            Codec.BOOL.optionalFieldOf("linear", false).forGetter(CopyPostStage::isLinear)
    ).apply(instance, CopyPostStage::new));
    private final boolean color;
    private final boolean depth;
    private final boolean linear;
    public CopyPostStage(Identifier in, Identifier out, boolean color, boolean depth, boolean linear) {
        super(java.util.Objects.requireNonNull(in), out, false);
        this.color = color; this.depth = depth; this.linear = linear;
    }
    @Override public void apply(Context context) {
        context.copy(context.requireFramebuffer(this.getIn()), context.requireFramebuffer(this.getOut()), this.color, this.depth, this.linear);
    }
    @Override public Identifier getIn() { return java.util.Objects.requireNonNull(super.getIn()); }
    public boolean copyColor() { return this.color; }
    public boolean copyDepth() { return this.depth; }
    public boolean isLinear() { return this.linear; }
    @Override public PostPipelineStageRegistry.PipelineType<? extends PostPipeline> getType() { return PostPipelineStageRegistry.COPY.get(); }
}
