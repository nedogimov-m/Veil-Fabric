package foundry.veil.api.client.render.rendertype.layer;

import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import foundry.veil.api.client.registry.RenderTypeLayerRegistry;
import foundry.veil.api.client.render.rendertype.VeilRenderTypeBuilder;

public record WriteMaskLayer(boolean color, boolean depth) implements RenderTypeLayer {

    public static final MapCodec<WriteMaskLayer> CODEC = RecordCodecBuilder.mapCodec(instance -> instance.group(
            Codec.BOOL.optionalFieldOf("color", true).forGetter(WriteMaskLayer::color),
            Codec.BOOL.optionalFieldOf("depth", true).forGetter(WriteMaskLayer::depth)
    ).apply(instance, WriteMaskLayer::new));

    @Override
    public void addShard(VeilRenderTypeBuilder builder, Object... params) {
        builder.writeMaskState(this.color, this.depth);
    }

    @Override
    public RenderTypeLayerRegistry.LayerType<?> getType() {
        return RenderTypeLayerRegistry.WRITE_MASK.get();
    }
}
