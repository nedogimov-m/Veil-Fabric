package foundry.veil.api.client.render.framebuffer;

import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import com.mojang.renderpearl.api.GpuFormat;
import foundry.veil.api.client.render.texture.TextureFilter;
import org.jetbrains.annotations.Nullable;

import java.util.Optional;

import static org.lwjgl.opengl.GL11C.*;
import static org.lwjgl.opengl.GL14C.*;
import static org.lwjgl.opengl.GL21C.GL_SRGB8;
import static org.lwjgl.opengl.GL21C.GL_SRGB8_ALPHA8;
import static org.lwjgl.opengl.GL30C.*;
import static org.lwjgl.opengl.GL31C.*;
import static org.lwjgl.opengl.GL33C.GL_RGB10_A2UI;
import static org.lwjgl.opengl.GL41C.GL_RGB565;

/**
 * Represents a framebuffer attachment that can be turned into a real framebuffer.
 *
 * @param type   The type of attachment this is
 * @param format The internal format of the data
 * @param depth  Whether this is a color or depth attachment
 * @param filter The texture filtering to apply. Only applies to texture buffers
 * @param levels The number of mipmaps for textures and samples for render buffers
 * @param name   The custom name to use when uploading this as a sampler to shaders
 * @author Ocelot
 * @see AdvancedFbo
 * @see FramebufferManager
 * @see foundry.veil.api.client.render.gpu.GpuFramebuffer
 */
public record FramebufferAttachmentDefinition(FramebufferAttachmentDefinition.Type type,
                                              FramebufferAttachmentDefinition.Format format,
                                              boolean depth,
                                              TextureFilter filter,
                                              int levels,
                                              @Nullable String name) {

    public static final Codec<FramebufferAttachmentDefinition> COLOR_CODEC =
            RecordCodecBuilder.create(instance -> instance.group(
                    Type.CODEC.optionalFieldOf("type", Type.TEXTURE)
                            .forGetter(FramebufferAttachmentDefinition::type),
                    Format.CODEC.optionalFieldOf("format", Format.RGBA8)
                            .forGetter(FramebufferAttachmentDefinition::format),
                    TextureFilter.CLAMP_DEFAULT_CODEC.optionalFieldOf("filter", TextureFilter.CLAMP)
                            .forGetter(FramebufferAttachmentDefinition::filter),
                    Codec.intRange(1, Integer.MAX_VALUE).optionalFieldOf("levels", 1)
                            .forGetter(FramebufferAttachmentDefinition::levels),
                    Codec.STRING.optionalFieldOf("name")
                            .forGetter(attachment -> Optional.ofNullable(attachment.name()))
            ).apply(instance, (type, format, filter, levels, name) ->
                    new FramebufferAttachmentDefinition(type, format, false, filter, levels, name.orElse(null))));
    public static final Codec<FramebufferAttachmentDefinition> DEPTH_CODEC =
            RecordCodecBuilder.create(instance -> instance.group(
                    Type.CODEC.optionalFieldOf("type", Type.TEXTURE)
                            .forGetter(FramebufferAttachmentDefinition::type),
                    Format.CODEC.optionalFieldOf("format", Format.DEPTH_COMPONENT)
                            .forGetter(FramebufferAttachmentDefinition::format),
                    TextureFilter.CLAMP_DEFAULT_CODEC.optionalFieldOf("filter", TextureFilter.CLAMP)
                            .forGetter(FramebufferAttachmentDefinition::filter),
                    Codec.intRange(1, Integer.MAX_VALUE).optionalFieldOf("levels", 1)
                            .forGetter(FramebufferAttachmentDefinition::levels),
                    Codec.STRING.optionalFieldOf("name")
                            .forGetter(attachment -> Optional.ofNullable(attachment.name()))
            ).apply(instance, (type, format, filter, levels, name) ->
                    new FramebufferAttachmentDefinition(type, format, true, filter, levels, name.orElse(null))));

    /**
     * @return Whether this attachment can be represented as <code>"depth": true</code> in the JSON
     */
    public boolean isCompactDepthAttachment() {
        return this.type == Type.TEXTURE && this.format == Format.DEPTH_COMPONENT && this.filter.equals(TextureFilter.CLAMP) && this.levels == 1 && this.name == null;
    }

    /**
     * The type of attachments.
     */
    public enum Type {

        TEXTURE("Texture"),
        RENDER_BUFFER("Render Buffer");

        public static final Type[] VALUES = Type.values();
        public static final Codec<Type> CODEC = Codec.STRING.flatXmap(name -> {
            for (Type type : VALUES) {
                if (type.name().equalsIgnoreCase(name)) {
                    return DataResult.success(type);
                }
            }
            return DataResult.error(() -> "Unknown attachment type: " + name);
        }, type -> DataResult.success(type.name()));

        private final String displayName;

        Type(String displayName) {
            this.displayName = displayName;
        }

        public String getDisplayName() {
            return this.displayName;
        }
    }

    /**
     * The formats for attachments.
     */
    public enum Format {

        RED(GL_RED, GL_RED),
        RG(GL_RG, GL_RG),
        RGB(GL_RGB, GL_RGB),
        BGR(GL_BGR, GL_BGR),
        RGBA(GL_RGBA, GL_RGBA),
        BGRA(GL_BGRA, GL_BGRA),
        DEPTH_COMPONENT(GL_DEPTH_COMPONENT, GL_DEPTH_COMPONENT),
        DEPTH_STENCIL(GL_DEPTH_STENCIL, GL_DEPTH_STENCIL),
        R8(GL_RED, GL_R8),
        R8_SNORM(GL_RED, GL_R8_SNORM),
        R16(GL_RED, GL_R16),
        R16_SNORM(GL_RED, GL_R16_SNORM),
        RG8(GL_RG, GL_RG8),
        RG8_SNORM(GL_RG, GL_RG8_SNORM),
        RG16(GL_RG, GL_RG16),
        RG16_SNORM(GL_RG, GL_RG16_SNORM),
        R3_G3_B2(GL_RGB, GL_R3_G3_B2),
        RGB4(GL_RGB, GL_RGB4),
        RGB5(GL_RGB, GL_RGB5),
        RGB565(GL_RGB, GL_RGB565),
        RGB8(GL_RGB, GL_RGB8),
        RGB8_SNORM(GL_RGB, GL_RGB8_SNORM),
        RGB10(GL_RGB, GL_RGB10),
        RGB12(GL_RGB, GL_RGB12),
        RGB16(GL_RGB, GL_RGB16),
        RGB16_SNORM(GL_RGB, GL_RGB16_SNORM),
        RGBA2(GL_RGBA, GL_RGBA2),
        RGBA4(GL_RGBA, GL_RGBA4),
        RGB5_A1(GL_RGBA, GL_RGB5_A1),
        RGBA8(GL_RGBA, GL_RGBA8),
        RGBA8_SNORM(GL_RGBA, GL_RGBA8_SNORM),
        RGB10_A2(GL_RGBA, GL_RGB10_A2),
        RGB10_A2UI(GL_RGBA_INTEGER, GL_RGB10_A2UI),
        RGBA12(GL_RGBA, GL_RGBA12),
        RGBA16(GL_RGBA, GL_RGBA16),
        RGBA16_SNORM(GL_RGBA, GL_RGBA16_SNORM),
        SRGB(GL_RGB, GL_SRGB),
        SRGB8(GL_RGB, GL_SRGB8),
        SRGB_ALPHA(GL_RGBA, GL_SRGB_ALPHA),
        SRGB8_ALPHA8(GL_RGBA, GL_SRGB8_ALPHA8),
        COMPRESSED_SRGB(GL_RGB, GL_COMPRESSED_SRGB),
        COMPRESSED_SRGB_ALPHA(GL_RGBA, GL_COMPRESSED_SRGB_ALPHA),
        R16F(GL_RED, GL_R16F),
        RG16F(GL_RG, GL_RG16F),
        RGB16F(GL_RGB, GL_RGB16F),
        RGBA16F(GL_RGBA, GL_RGBA16F),
        R32F(GL_RED, GL_R32F),
        RG32F(GL_RG, GL_R32F),
        RGB32F(GL_RGB, GL_RGB32F),
        RGB9_E5(GL_RGB, GL_RGB9_E5),
        RGBA32F(GL_RGBA, GL_RGBA32F),
        R11F_G11F_B10F(GL_RGBA, GL_R11F_G11F_B10F),
        R8I(GL_RED_INTEGER, GL_R8I),
        R8UI(GL_RED_INTEGER, GL_R8UI),
        R16I(GL_RED_INTEGER, GL_R16I),
        R16UI(GL_RED_INTEGER, GL_R16UI),
        R32I(GL_RED_INTEGER, GL_R32I),
        R32UI(GL_RED_INTEGER, GL_R32UI),
        RG8I(GL_RG_INTEGER, GL_RG8I),
        RG8UI(GL_RG_INTEGER, GL_RG8UI),
        RG16I(GL_RG_INTEGER, GL_RG16I),
        RG16UI(GL_RG_INTEGER, GL_RG16UI),
        RG32I(GL_RG_INTEGER, GL_RG32I),
        RG32UI(GL_RG_INTEGER, GL_RG32UI),
        RGB8I(GL_RGB_INTEGER, GL_RGB8I),
        RGB8UI(GL_RGB_INTEGER, GL_RGB8UI),
        RGB16I(GL_RGB_INTEGER, GL_RGB16I),
        RGB16UI(GL_RGB_INTEGER, GL_RGB16UI),
        RGB32I(GL_RGB_INTEGER, GL_RGB32I),
        RGB32UI(GL_RGB_INTEGER, GL_RGB32UI),
        RGBA8I(GL_RGBA_INTEGER, GL_RGBA8I),
        RGBA8UI(GL_RGBA_INTEGER, GL_RGBA8UI),
        RGBA16I(GL_RGBA_INTEGER, GL_RGBA16I),
        RGBA16UI(GL_RGBA_INTEGER, GL_RGBA16UI),
        RGBA32I(GL_RGBA_INTEGER, GL_RGBA32I),
        RGBA32UI(GL_RGBA_INTEGER, GL_RGBA32UI),
        DEPTH_COMPONENT16(GL_DEPTH_COMPONENT, GL_DEPTH_COMPONENT16),
        DEPTH_COMPONENT24(GL_DEPTH_COMPONENT, GL_DEPTH_COMPONENT24),
        DEPTH_COMPONENT32(GL_DEPTH_COMPONENT, GL_DEPTH_COMPONENT32),
        DEPTH_COMPONENT32F(GL_DEPTH_COMPONENT, GL_DEPTH_COMPONENT32F),
        DEPTH24_STENCIL8(GL_DEPTH_STENCIL, GL_DEPTH24_STENCIL8),
        DEPTH32F_STENCIL8(GL_DEPTH_STENCIL, GL_DEPTH32F_STENCIL8);

        public static final Format[] VALUES = Format.values();
        public static final Codec<Format> CODEC = Codec.STRING.flatXmap(name -> {
            for (Format type : VALUES) {
                if (type.name().equalsIgnoreCase(name)) {
                    return DataResult.success(type);
                }
            }
            return DataResult.error(() -> "Unknown attachment format: " + name);
        }, type -> DataResult.success(type.name()));

        private final int id;
        private final int internalId;

        Format(int id, int internalId) {
            this.id = id;
            this.internalId = internalId;
        }

        /** Exact native storage formats; packed/sRGB formats are never silently reinterpreted. */
        public GpuFormat gpuFormat() {
            return switch (this) {
                case RED -> GpuFormat.R8_UNORM;
                case RG -> GpuFormat.RG8_UNORM;
                case RGB -> GpuFormat.RGB8_UNORM;
                case RGBA -> GpuFormat.RGBA8_UNORM;
                case DEPTH_COMPONENT, DEPTH_COMPONENT32F -> GpuFormat.D32_FLOAT;
                case DEPTH_COMPONENT16 -> GpuFormat.D16_UNORM;
                case DEPTH_STENCIL, DEPTH24_STENCIL8 -> GpuFormat.D24_UNORM_S8_UINT;
                case DEPTH32F_STENCIL8 -> GpuFormat.D32_FLOAT_S8_UINT;
                case RGB10_A2 -> GpuFormat.RGB10A2_UNORM;
                case RGB10_A2UI -> GpuFormat.RGB10A2_UINT;
                case R11F_G11F_B10F -> GpuFormat.RG11B10_FLOAT;
                case R8 -> GpuFormat.R8_UNORM;
                case R8_SNORM -> GpuFormat.R8_SNORM;
                case R16 -> GpuFormat.R16_UNORM;
                case R16_SNORM -> GpuFormat.R16_SNORM;
                case R16F -> GpuFormat.R16_FLOAT;
                case R32F -> GpuFormat.R32_FLOAT;
                case R8I -> GpuFormat.R8_SINT;
                case R8UI -> GpuFormat.R8_UINT;
                case R16I -> GpuFormat.R16_SINT;
                case R16UI -> GpuFormat.R16_UINT;
                case R32I -> GpuFormat.R32_SINT;
                case R32UI -> GpuFormat.R32_UINT;
                case RG8 -> GpuFormat.RG8_UNORM;
                case RG8_SNORM -> GpuFormat.RG8_SNORM;
                case RG16 -> GpuFormat.RG16_UNORM;
                case RG16_SNORM -> GpuFormat.RG16_SNORM;
                case RG16F -> GpuFormat.RG16_FLOAT;
                case RG32F -> GpuFormat.RG32_FLOAT;
                case RG8I -> GpuFormat.RG8_SINT;
                case RG8UI -> GpuFormat.RG8_UINT;
                case RG16I -> GpuFormat.RG16_SINT;
                case RG16UI -> GpuFormat.RG16_UINT;
                case RG32I -> GpuFormat.RG32_SINT;
                case RG32UI -> GpuFormat.RG32_UINT;
                case RGB8 -> GpuFormat.RGB8_UNORM;
                case RGB8_SNORM -> GpuFormat.RGB8_SNORM;
                case RGB16 -> GpuFormat.RGB16_UNORM;
                case RGB16_SNORM -> GpuFormat.RGB16_SNORM;
                case RGB16F -> GpuFormat.RGB16_FLOAT;
                case RGB32F -> GpuFormat.RGB32_FLOAT;
                case RGB8I -> GpuFormat.RGB8_SINT;
                case RGB8UI -> GpuFormat.RGB8_UINT;
                case RGB16I -> GpuFormat.RGB16_SINT;
                case RGB16UI -> GpuFormat.RGB16_UINT;
                case RGB32I -> GpuFormat.RGB32_SINT;
                case RGB32UI -> GpuFormat.RGB32_UINT;
                case RGBA8 -> GpuFormat.RGBA8_UNORM;
                case RGBA8_SNORM -> GpuFormat.RGBA8_SNORM;
                case RGBA16 -> GpuFormat.RGBA16_UNORM;
                case RGBA16_SNORM -> GpuFormat.RGBA16_SNORM;
                case RGBA16F -> GpuFormat.RGBA16_FLOAT;
                case RGBA32F -> GpuFormat.RGBA32_FLOAT;
                case RGBA8I -> GpuFormat.RGBA8_SINT;
                case RGBA8UI -> GpuFormat.RGBA8_UINT;
                case RGBA16I -> GpuFormat.RGBA16_SINT;
                case RGBA16UI -> GpuFormat.RGBA16_UINT;
                case RGBA32I -> GpuFormat.RGBA32_SINT;
                case RGBA32UI -> GpuFormat.RGBA32_UINT;
                default -> throw new UnsupportedOperationException("No exact RenderPearl format for legacy framebuffer storage: " + this);
            };
        }

        /**
         * @return The OpenGL id of this format
         */
        public int getFormat() {
            return this.id;
        }

        /**
         * @return The OpenGL id of this internal format
         */
        public int getInternalFormat() {
            return this.internalId;
        }
    }
}
