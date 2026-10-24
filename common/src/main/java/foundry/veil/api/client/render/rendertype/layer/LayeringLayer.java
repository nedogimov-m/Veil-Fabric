package foundry.veil.api.client.render.rendertype.layer;

import com.mojang.serialization.MapCodec;
import foundry.veil.api.client.registry.RenderTypeLayerRegistry;
import foundry.veil.api.client.render.rendertype.VeilRenderTypeBuilder;

public record LayeringLayer(LayerTemplateValue<LayeringState> mode) implements RenderTypeLayer {

    public static final MapCodec<LayeringLayer> CODEC = LayerTemplateValue.enumCodec(LayeringState.class)
            .optionalFieldOf("mode", LayerTemplateValue.raw(LayeringState.POLYGON_OFFSET))
            .xmap(LayeringLayer::new, LayeringLayer::mode);

    @Override
    public void addShard(VeilRenderTypeBuilder builder, Object... params) {
        builder.layeringState(this.mode.parse(params));
    }

    @Override
    public RenderTypeLayerRegistry.LayerType<?> getType() {
        return RenderTypeLayerRegistry.LAYERING.get();
    }

    public enum LayeringState { NONE, POLYGON_OFFSET, VIEW_OFFSET }
}
