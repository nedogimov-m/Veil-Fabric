package foundry.veil.api.client.render.rendertype.layer;

import com.mojang.serialization.MapCodec;
import foundry.veil.api.client.registry.RenderTypeLayerRegistry;
import foundry.veil.api.client.render.rendertype.VeilRenderTypeBuilder;

public record CullLayer(LayerTemplateValue<CullFace> face) implements RenderTypeLayer {

    public static final MapCodec<CullLayer> CODEC = LayerTemplateValue.enumCodec(CullFace.class)
            .optionalFieldOf("face", LayerTemplateValue.raw(CullFace.BACK))
            .xmap(CullLayer::new, CullLayer::face);

    @Override
    public void addShard(VeilRenderTypeBuilder builder, Object... params) { builder.cullState(this.face.parse(params)); }

    @Override
    public RenderTypeLayerRegistry.LayerType<?> getType() {
        return RenderTypeLayerRegistry.CULL.get();
    }

    public enum CullFace { FRONT, BACK, FRONT_AND_BACK, NONE }
}
