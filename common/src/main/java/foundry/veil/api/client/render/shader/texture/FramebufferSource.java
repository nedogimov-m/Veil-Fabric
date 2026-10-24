package foundry.veil.api.client.render.shader.texture;

import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import foundry.veil.api.client.render.gpu.GpuFramebuffer;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import foundry.veil.api.client.render.texture.TextureFilter;
import net.minecraft.resources.Identifier;
import org.jetbrains.annotations.Nullable;

import java.util.Optional;


/**
 * Source of a shader texture using a framebuffer.
 *
 * @param name    The location of the framebuffer
 * @param sampler The sampler to use. Ignored if {@link #depth} is <code>true</code>
 * @param depth   Whether to sample the depth texture or not
 * @param filter  The texture filter to use
 * @author Ocelot
 */
public record FramebufferSource(
        Identifier name,
        int sampler,
        boolean depth,
        @Nullable TextureFilter filter
) implements ShaderTextureSource {

    public static final MapCodec<FramebufferSource> CODEC = RecordCodecBuilder.mapCodec(instance -> instance.group(
            Codec.STRING.fieldOf("name").forGetter(source -> source.name.toString() + (source.depth ? ":depth" : "")),
            Codec.INT.optionalFieldOf("sampler", 0).forGetter(FramebufferSource::sampler),
            TextureFilter.REPEAT_DEFAULT_CODEC.optionalFieldOf("filter").forGetter(source -> Optional.ofNullable(source.filter))
    ).apply(instance, (name, sampler, filter) -> {
        boolean depth = name.endsWith(":depth");
        String path = depth ? name.substring(0, name.length() - 6) : name;
        Identifier location = path.contains(":") ? Identifier.parse(path) : Identifier.fromNamespaceAndPath("temp", path);
        if (sampler < 0) throw new IllegalArgumentException("Framebuffer sampler index must be nonnegative");
        return new FramebufferSource(location, depth ? 0 : sampler, depth, filter.orElse(null));
    }));

    @Override
    public GpuTextureView getTexture(Context context) {
        GpuFramebuffer framebuffer = context.getFramebuffer(this.name);
        if (framebuffer == null) throw new IllegalStateException("Shader framebuffer is unavailable: " + this.name);
        return this.depth ? framebuffer.depth().view() : framebuffer.color(this.sampler).view();
    }

    @Override
    public Type type() {
        return Type.FRAMEBUFFER;
    }
}
