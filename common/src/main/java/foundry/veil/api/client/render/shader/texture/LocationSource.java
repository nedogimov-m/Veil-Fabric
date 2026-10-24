package foundry.veil.api.client.render.shader.texture;

import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import foundry.veil.api.client.render.texture.TextureFilter;
import net.minecraft.resources.Identifier;
import org.jetbrains.annotations.Nullable;

import java.util.Optional;

/**
 * Source of a shader texture using a registered texture.
 *
 * @param location The location of the texture
 * @param filter   The texture filter to use
 * @author Ocelot
 */
public record LocationSource(Identifier location, @Nullable TextureFilter filter) implements ShaderTextureSource {

    public static final MapCodec<LocationSource> CODEC = RecordCodecBuilder.mapCodec(instance -> instance.group(
            Identifier.CODEC.fieldOf("location").forGetter(LocationSource::location),
            TextureFilter.REPEAT_DEFAULT_CODEC.optionalFieldOf("filter").forGetter(source -> Optional.ofNullable(source.filter))
    ).apply(instance, (location, filter) -> new LocationSource(location, filter.orElse(null))));

    @Override
    public com.mojang.renderpearl.api.textures.GpuTextureView getTexture(Context context) {
        return context.getTexture(this.location);
    }

    @Override
    public Type type() {
        return Type.LOCATION;
    }
}
