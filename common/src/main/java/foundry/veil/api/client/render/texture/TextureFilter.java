package foundry.veil.api.client.render.texture;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import foundry.veil.api.client.color.Color;
import com.mojang.renderpearl.api.device.GpuDevice;
import com.mojang.renderpearl.api.textures.AddressMode;
import com.mojang.renderpearl.api.textures.FilterMode;
import com.mojang.renderpearl.api.textures.GpuSampler;
import foundry.veil.api.util.EnumCodec;
import it.unimi.dsi.fastutil.ints.Int2ObjectArrayMap;
import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import it.unimi.dsi.fastutil.ints.Int2ObjectMaps;
import org.jetbrains.annotations.Nullable;


import java.util.Optional;
import java.util.OptionalDouble;

import static org.lwjgl.opengl.GL11C.*;
import static org.lwjgl.opengl.GL12C.GL_CLAMP_TO_EDGE;
import static org.lwjgl.opengl.GL13C.GL_CLAMP_TO_BORDER;
import static org.lwjgl.opengl.GL14C.*;
import static org.lwjgl.opengl.GL44C.GL_MIRROR_CLAMP_TO_EDGE;

/**
 * Specifies the full texture filter state of a texture or sampler object.
 *
 * @param blur            Whether to blur the image
 * @param mipmap          Whether to respect the mipmap levels of the image
 * @param anisotropy      Sets the anisotropic filtering value. Any value >1 is considered to be enabled. Set to {@link Float#MAX_VALUE} to set to the platform maximum
 * @param compareFunction The depth compare function to use or <code>null</code> for default
 * @param wrapX           The clamping for the X axis on the texture
 * @param wrapY           The clamping for the Y axis on the texture
 * @param wrapZ           The clamping for the Z axis on the texture
 * @param borderColor     The color to get when sampling the texture out of bounds when using {@link TextureFilter.Wrap#CLAMP_TO_BORDER}
 * @param borderType      The type of data the border color should be referenced in
 * @param seamless        Whether the texture should be considered seamless if using a cubemap
 * @author Ocelot
 */
public record TextureFilter(boolean blur,
                            boolean mipmap,
                            float anisotropy,
                            @Nullable CompareFunction compareFunction,
                            Wrap wrapX,
                            Wrap wrapY,
                            Wrap wrapZ,
                            int borderColor,
                            EdgeType borderType,
                            boolean seamless) {

    public static final Codec<TextureFilter> REPEAT_DEFAULT_CODEC = RecordCodecBuilder.create(instance -> instance.group(
            Codec.BOOL.optionalFieldOf("blur", false).forGetter(TextureFilter::blur),
            Codec.BOOL.optionalFieldOf("mipmap", false).forGetter(TextureFilter::mipmap),
            Codec.FLOAT.optionalFieldOf("anisotropy", 1.0F).forGetter(TextureFilter::anisotropy),
            CompareFunction.CODEC.optionalFieldOf("compareFunction").forGetter(options -> Optional.ofNullable(options.compareFunction())),
            Wrap.CODEC.optionalFieldOf("wrapX", Wrap.REPEAT).forGetter(TextureFilter::wrapX),
            Wrap.CODEC.optionalFieldOf("wrapY", Wrap.REPEAT).forGetter(TextureFilter::wrapY),
            Wrap.CODEC.optionalFieldOf("wrapZ", Wrap.REPEAT).forGetter(TextureFilter::wrapZ),
            Color.ARGB_INT_CODEC.optionalFieldOf("borderColor", 0xFF000000).forGetter(TextureFilter::borderColor),
            EdgeType.CODEC.optionalFieldOf("borderType", EdgeType.FLOAT).forGetter(TextureFilter::borderType),
            Codec.BOOL.optionalFieldOf("seamless", false).forGetter(TextureFilter::seamless)
    ).apply(instance, (blur, mipmap, anisotropy, compareFunction, wrapX, wrapY, wrapZ, edgeColor, edgeType, seamless) ->
            new TextureFilter(blur, mipmap, anisotropy, compareFunction.orElse(null), wrapX, wrapY, wrapZ, edgeColor, edgeType, seamless)));

    public static final Codec<TextureFilter> CLAMP_DEFAULT_CODEC = RecordCodecBuilder.create(instance -> instance.group(
            Codec.BOOL.optionalFieldOf("blur", false).forGetter(TextureFilter::blur),
            Codec.BOOL.optionalFieldOf("mipmap", false).forGetter(TextureFilter::mipmap),
            Codec.FLOAT.optionalFieldOf("anisotropy", 1.0F).forGetter(TextureFilter::anisotropy),
            CompareFunction.CODEC.optionalFieldOf("compareFunction").forGetter(options -> Optional.ofNullable(options.compareFunction())),
            Wrap.CODEC.optionalFieldOf("wrapX", Wrap.CLAMP_TO_EDGE).forGetter(TextureFilter::wrapX),
            Wrap.CODEC.optionalFieldOf("wrapY", Wrap.CLAMP_TO_EDGE).forGetter(TextureFilter::wrapY),
            Wrap.CODEC.optionalFieldOf("wrapZ", Wrap.CLAMP_TO_EDGE).forGetter(TextureFilter::wrapZ),
            Color.ARGB_INT_CODEC.optionalFieldOf("borderColor", 0xFF000000).forGetter(TextureFilter::borderColor),
            EdgeType.CODEC.optionalFieldOf("borderType", EdgeType.FLOAT).forGetter(TextureFilter::borderType),
            Codec.BOOL.optionalFieldOf("seamless", false).forGetter(TextureFilter::seamless)
    ).apply(instance, (blur, mipmap, anisotropy, compareFunction, wrapX, wrapY, wrapZ, edgeColor, edgeType, seamless) ->
            new TextureFilter(blur, mipmap, anisotropy, compareFunction.orElse(null), wrapX, wrapY, wrapZ, edgeColor, edgeType, seamless)));

    public static final TextureFilter REPEAT = new TextureFilter(
            false,
            false,
            1.0F,
            null,
            Wrap.REPEAT,
            Wrap.REPEAT,
            Wrap.REPEAT,
            0xFF000000,
            EdgeType.FLOAT,
            false);
    public static final TextureFilter CLAMP = new TextureFilter(
            false,
            false,
            1.0F,
            null,
            Wrap.CLAMP_TO_EDGE,
            Wrap.CLAMP_TO_EDGE,
            Wrap.CLAMP_TO_EDGE,
            0xFF000000,
            EdgeType.FLOAT,
            false);

    /** Creates an independent native sampler; the caller owns and closes it. */
    public GpuSampler createSampler(GpuDevice device) {
        if (!Float.isFinite(this.anisotropy) || this.anisotropy < 1) {
            throw new IllegalArgumentException("Sampler anisotropy must be finite and at least one");
        }
        if (this.compareFunction != null) {
            throw new UnsupportedOperationException("RenderPearl has no hardware comparison-sampler parameter; use an explicit depth-comparison shader");
        }
        if (this.seamless) {
            throw new UnsupportedOperationException("Cubemap seam filtering needs the cubemap pipeline migration");
        }
        int anisotropy = Math.min(device.getDeviceInfo().limits().maxAnisotropy(), (int) this.anisotropy);
        return device.createSampler(this.wrapX.addressMode(), this.wrapY.addressMode(),
                this.blur ? FilterMode.LINEAR : FilterMode.NEAREST,
                this.blur ? FilterMode.LINEAR : FilterMode.NEAREST,
                Math.max(1, anisotropy), this.mipmap ? OptionalDouble.empty() : OptionalDouble.of(0));
    }

    /** Legacy serialized filter constants; native sampling uses createSampler. */
    public int minFilter() {
        return this.blur ? (this.mipmap ? GL_LINEAR_MIPMAP_LINEAR : GL_LINEAR)
                : (this.mipmap ? GL_NEAREST_MIPMAP_LINEAR : GL_NEAREST);
    }

    public int magFilter() { return this.blur ? GL_LINEAR : GL_NEAREST; }

    /**
     * Depth-texture compare functions.
     */
    public enum CompareFunction {
        NEVER(GL_NEVER),
        ALWAYS(GL_ALWAYS),
        LESS(GL_LESS),
        LEQUAL(GL_LEQUAL),
        EQUAL(GL_EQUAL),
        NOT_EQUAL(GL_NOTEQUAL),
        GEQUAL(GL_GEQUAL),
        GREATER(GL_GREATER);

        public static final Codec<CompareFunction> CODEC = EnumCodec.<CompareFunction>builder("Compare Function")
                .values(CompareFunction.class)
                .build();

        private final int id;

        CompareFunction(int id) {
            this.id = id;
        }

        /**
         * @return The OpenGL id of this compare function
         */
        public int getId() {
            return this.id;
        }
    }

    /**
     * Texture wrap modes.
     */
    public enum Wrap {
        CLAMP_TO_EDGE(GL_CLAMP_TO_EDGE),
        CLAMP_TO_BORDER(GL_CLAMP_TO_BORDER),
        MIRRORED_REPEAT(GL_MIRRORED_REPEAT),
        REPEAT(GL_REPEAT),
        MIRROR_CLAMP_TO_EDGE(GL_MIRROR_CLAMP_TO_EDGE);

        public static final Codec<Wrap> CODEC = EnumCodec.<Wrap>builder("Texture Wrap")
                .values(Wrap.class)
                .build();
        public static final Int2ObjectMap<Wrap> BY_GL_ID;

        static {
            Int2ObjectMap<Wrap> map = new Int2ObjectArrayMap<>();
            for (Wrap filter : values()) {
                map.put(filter.id, filter);
            }
            BY_GL_ID = Int2ObjectMaps.unmodifiable(map);
        }

        private final int id;

        Wrap(int id) {
            this.id = id;
        }

        /**
         * @return The OpenGL id of this wrap mode
         */
        public int getId() {
            return this.id;
        }
        public AddressMode addressMode() {
            return switch (this) {
                case CLAMP_TO_EDGE -> AddressMode.CLAMP_TO_EDGE;
                case REPEAT -> AddressMode.REPEAT;
                default -> throw new UnsupportedOperationException("RenderPearl sampler address mode requires shader emulation: " + this);
            };
        }
    }

    /**
     * Edge color data types.
     */
    public enum EdgeType {
        FLOAT, INT, UINT;

        public static final Codec<EdgeType> CODEC = EnumCodec.<EdgeType>builder("Edge Data Type")
                .values(EdgeType.class)
                .build();
    }
}
