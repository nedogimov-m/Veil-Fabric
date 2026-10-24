package foundry.veil.api.client.render.rendertype.layer;

import com.mojang.serialization.MapCodec;
import foundry.veil.api.client.registry.RenderTypeLayerRegistry;
import foundry.veil.api.client.render.rendertype.VeilRenderTypeBuilder;
import net.minecraft.resources.Identifier;

public record VeilShaderLayer(LayerTemplateValue<Identifier> shaderId) implements RenderTypeLayer {

    public static final MapCodec<VeilShaderLayer> CODEC = LayerTemplateValue.LOCATION_CODEC
            .fieldOf("name")
            .xmap(VeilShaderLayer::new, VeilShaderLayer::shaderId);

    @Override
    public void addShard(VeilRenderTypeBuilder builder, Object... params) {
        builder.shaderState(this.shaderId.parse(params), true);
    }

    @Override
    public RenderTypeLayerRegistry.LayerType<?> getType() {
        return RenderTypeLayerRegistry.VEIL_SHADER.get();
    }
}
