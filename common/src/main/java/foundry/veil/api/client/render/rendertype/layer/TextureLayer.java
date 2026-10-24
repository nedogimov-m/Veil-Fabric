package foundry.veil.api.client.render.rendertype.layer;

import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import foundry.veil.api.client.registry.RenderTypeLayerRegistry;
import foundry.veil.api.client.render.rendertype.VeilRenderTypeBuilder;
import net.minecraft.resources.Identifier;

public record TextureLayer(LayerTemplateValue<Identifier> texture,
                           boolean blur,
                           boolean mipmap) implements RenderTypeLayer {

    public static final MapCodec<TextureLayer> CODEC = RecordCodecBuilder.mapCodec(instance -> instance.group(
            LayerTemplateValue.LOCATION_CODEC.fieldOf("texture").forGetter(TextureLayer::texture),
            Codec.BOOL.optionalFieldOf("blur", false).forGetter(TextureLayer::blur),
            Codec.BOOL.optionalFieldOf("mipmap", false).forGetter(TextureLayer::mipmap)
    ).apply(instance, TextureLayer::new));

    @Override
    public void addShard(VeilRenderTypeBuilder builder, Object... params) {
        builder.textureState(java.util.List.of(new VeilRenderTypeBuilder.Texture(this.texture.parse(params), this.blur, this.mipmap)));
    }

    @Override
    public RenderTypeLayerRegistry.LayerType<?> getType() {
        return RenderTypeLayerRegistry.TEXTURE.get();
    }
}
