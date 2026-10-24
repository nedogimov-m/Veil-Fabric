package foundry.veil.api.client.render.shader.texture;

import com.mojang.datafixers.util.Either;
import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import com.mojang.renderpearl.api.textures.GpuSampler;
import foundry.veil.api.client.render.gpu.GpuFramebuffer;
import foundry.veil.api.client.render.texture.TextureFilter;
import foundry.veil.api.util.EnumCodec;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.AbstractTexture;
import net.minecraft.resources.Identifier;
import org.jetbrains.annotations.Nullable;

/** Resource-defined texture views; resolution never exposes backend integer IDs. */
public sealed interface ShaderTextureSource permits LocationSource, FramebufferSource {
    Codec<ShaderTextureSource> CODEC = Codec.either(Identifier.CODEC,
                    Type.CODEC.<ShaderTextureSource>dispatch(ShaderTextureSource::type, Type::codec))
            .xmap(either -> either.map(name -> new LocationSource(name, null), source -> source),
                    source -> source instanceof LocationSource location && location.filter() == null
                            ? Either.left(location.location()) : Either.right(source));
    GpuTextureView getTexture(Context context);
    @Nullable TextureFilter filter();
    Type type();

    enum Type {
        LOCATION(LocationSource.CODEC), FRAMEBUFFER(FramebufferSource.CODEC);
        private final MapCodec<? extends ShaderTextureSource> codec;
        public static final Codec<Type> CODEC = EnumCodec.<Type>builder("texture type").values(Type.class).build();
        Type(MapCodec<? extends ShaderTextureSource> codec) { this.codec = codec; }
        public MapCodec<? extends ShaderTextureSource> codec() { return this.codec; }
    }

    @FunctionalInterface
    interface Context {
        @Nullable GpuFramebuffer getFramebuffer(Identifier name);
        default AbstractTexture getTextureResource(Identifier name) {
            return Minecraft.getInstance().getTextureManager().getTexture(name);
        }
        default GpuTextureView getTexture(Identifier name) { return this.getTextureResource(name).getTextureView(); }
        default GpuSampler getSampler(Identifier name) { return this.getTextureResource(name).getSampler(); }
        /** Framebuffer-specific sampling settings are supplied by its resource manager. */
        default TextureFilter framebufferFilter(Identifier name, int colorAttachment, boolean depth) { return TextureFilter.CLAMP; }
    }
}
