package foundry.veil.api.client.render.rendertype.layer;

import com.mojang.serialization.MapCodec;
import foundry.veil.api.client.registry.RenderTypeLayerRegistry;
import foundry.veil.api.client.render.rendertype.VeilRenderTypeBuilder;

public record VanillaShaderLayer(LayerTemplateValue<String> shaderName) implements RenderTypeLayer {

    public static final MapCodec<VanillaShaderLayer> CODEC = LayerTemplateValue.STRING_CODEC
            .fieldOf("name")
            .xmap(VanillaShaderLayer::new, VanillaShaderLayer::shaderName);

    @Override
    public void addShard(VeilRenderTypeBuilder builder, Object... params) {
        builder.shaderState(net.minecraft.resources.Identifier.parse(this.shaderName.parse(params)), false);
    }

    @Override
    public RenderTypeLayerRegistry.LayerType<?> getType() {
        return RenderTypeLayerRegistry.VANILLA_SHADER.get();
    }
}
