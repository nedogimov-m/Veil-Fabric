package foundry.veil.api.client.util;

import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.datafixers.util.Either;
import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.pipeline.PrimitiveTopology;
import com.mojang.renderpearl.api.vertex.VertexFormat;
import com.mojang.renderpearl.api.vertex.VertexFormatElement;
import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.client.renderer.rendertype.RenderType;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/** Resource vertex declarations expressed through actual RenderPearl attribute offsets and GPU formats. */
public final class VertexFormatCodec {
    private static final Map<String, VertexFormat> DEFAULT_FORMATS = new LinkedHashMap<>();
    private static final Map<String, VertexFormatElement> DEFAULT_ELEMENTS = new LinkedHashMap<>();
    private static final Map<String, Integer> DEFAULT_BUFFER_SIZES = Map.of(
            "BIG", RenderType.BIG_BUFFER_SIZE, "SMALL", RenderType.SMALL_BUFFER_SIZE, "TRANSIENT", RenderType.TRANSIENT_BUFFER_SIZE);
    static {
        DEFAULT_FORMATS.put("BLOCK", DefaultVertexFormat.BLOCK);
        DEFAULT_FORMATS.put("BLIT_SCREEN", DefaultVertexFormat.POSITION_TEX);
        DEFAULT_FORMATS.put("NEW_ENTITY", DefaultVertexFormat.ENTITY);
        DEFAULT_FORMATS.put("ENTITY", DefaultVertexFormat.ENTITY);
        DEFAULT_FORMATS.put("PARTICLE", DefaultVertexFormat.PARTICLE);
        DEFAULT_FORMATS.put("POSITION", DefaultVertexFormat.POSITION);
        DEFAULT_FORMATS.put("POSITION_COLOR", DefaultVertexFormat.POSITION_COLOR);
        DEFAULT_FORMATS.put("POSITION_COLOR_NORMAL", DefaultVertexFormat.POSITION_COLOR_NORMAL);
        DEFAULT_FORMATS.put("POSITION_COLOR_LIGHTMAP", DefaultVertexFormat.POSITION_COLOR_LIGHTMAP);
        DEFAULT_FORMATS.put("POSITION_TEX", DefaultVertexFormat.POSITION_TEX);
        DEFAULT_FORMATS.put("POSITION_TEX_COLOR", DefaultVertexFormat.POSITION_TEX_COLOR);
        DEFAULT_FORMATS.put("POSITION_COLOR_TEX_LIGHTMAP", DefaultVertexFormat.POSITION_COLOR_TEX_LIGHTMAP);
        DEFAULT_FORMATS.put("POSITION_TEX_LIGHTMAP_COLOR", DefaultVertexFormat.POSITION_TEX_LIGHTMAP_COLOR);
        DEFAULT_FORMATS.put("POSITION_TEX_COLOR_NORMAL", DefaultVertexFormat.POSITION_TEX_COLOR_NORMAL);
        DEFAULT_FORMATS.put("POSITION_COLOR_LINE_WIDTH", DefaultVertexFormat.POSITION_COLOR_LINE_WIDTH);
        DEFAULT_FORMATS.put("POSITION_COLOR_NORMAL_LINE_WIDTH", DefaultVertexFormat.POSITION_COLOR_NORMAL_LINE_WIDTH);
        DEFAULT_FORMATS.values().forEach(format -> format.getElements().forEach(element ->
                DEFAULT_ELEMENTS.putIfAbsent(element.name().toUpperCase(Locale.ROOT), element)));
        DEFAULT_ELEMENTS.put("UV", DEFAULT_ELEMENTS.get("UV0"));
    }
    private static <T extends Enum<T>> Codec<T> enumCodec(Class<T> type) {
        return Codec.STRING.comapFlatMap(name -> {
            try { return DataResult.success(Enum.valueOf(type, name.toUpperCase(Locale.ROOT))); }
            catch (IllegalArgumentException exception) { return DataResult.error(() -> "Unknown " + type.getSimpleName() + ": " + name); }
        }, value -> value.name().toLowerCase(Locale.ROOT));
    }
    public static final Codec<PrimitiveTopology> MODE_CODEC = enumCodec(PrimitiveTopology.class);
    public static final Codec<GpuFormat> GPU_FORMAT_CODEC = enumCodec(GpuFormat.class);
    private static final Codec<VertexFormatElement> NAMED_ELEMENT_CODEC = Codec.STRING.comapFlatMap(name -> {
        final var element = DEFAULT_ELEMENTS.get(name.toUpperCase(Locale.ROOT));
        return element != null ? DataResult.success(element) : DataResult.error(() -> "Unknown default vertex element: " + name);
    }, element -> DEFAULT_ELEMENTS.entrySet().stream().filter(entry -> entry.getValue().equals(element))
            .map(Map.Entry::getKey).findFirst().orElseThrow());
    private static final Codec<VertexFormatElement> FULL_ELEMENT_CODEC = RecordCodecBuilder.create(instance -> instance.group(
            Codec.STRING.fieldOf("name").forGetter(VertexFormatElement::name),
            Codec.intRange(0, Integer.MAX_VALUE).fieldOf("offset").forGetter(VertexFormatElement::offset),
            GPU_FORMAT_CODEC.fieldOf("format").forGetter(VertexFormatElement::format)
    ).apply(instance, VertexFormatElement::new));
    public static final Codec<VertexFormatElement> ELEMENT_CODEC = Codec.either(NAMED_ELEMENT_CODEC, FULL_ELEMENT_CODEC)
            .xmap(value -> value.map(element -> element, element -> element), element ->
                    DEFAULT_ELEMENTS.containsValue(element) ? Either.left(element) : Either.right(element));
    private static final Codec<VertexFormat> NAMED_CODEC = Codec.STRING.comapFlatMap(name -> {
        final var format = DEFAULT_FORMATS.get(name.toUpperCase(Locale.ROOT));
        return format != null ? DataResult.success(format) : DataResult.error(() -> "Unknown default vertex format: " + name);
    }, format -> DEFAULT_FORMATS.entrySet().stream().filter(entry -> entry.getValue().equals(format) && entry.getValue().getStepRate() == format.getStepRate())
            .map(Map.Entry::getKey).findFirst().orElseThrow());
    private record Description(int stride, int stepRate, java.util.List<VertexFormatElement> elements) {}
    private static final Codec<Description> DESCRIPTION_CODEC = RecordCodecBuilder.create(instance -> instance.group(
            Codec.intRange(1, Integer.MAX_VALUE).fieldOf("stride").forGetter(Description::stride),
            Codec.intRange(0, Integer.MAX_VALUE).optionalFieldOf("stepRate", 0).forGetter(Description::stepRate),
            ELEMENT_CODEC.listOf().fieldOf("elements").forGetter(Description::elements)
    ).apply(instance, Description::new));
    private static final Codec<VertexFormat> FULL_CODEC = DESCRIPTION_CODEC.flatXmap(description -> {
        try {
            if (description.elements().isEmpty()) return DataResult.error(() -> "A vertex format must declare at least one element");
            final var builder = VertexFormat.builder(description.stepRate());
            for (var element : description.elements()) {
                if ((long) element.offset() + element.format().blockSize() > description.stride())
                    return DataResult.error(() -> "Element exceeds vertex stride: " + element.name());
                builder.addAttribute(element.name(), element.offset(), description.stride() - element.offset(), element.format(), 1);
            }
            return DataResult.success(builder.build());
        } catch (IllegalArgumentException | IllegalStateException exception) { return DataResult.error(exception::getMessage); }
    }, format -> DataResult.success(new Description(format.getVertexSize(), format.getStepRate(), format.getElements())));
    public static final Codec<VertexFormat> CODEC = Codec.either(NAMED_CODEC, FULL_CODEC).xmap(
            value -> value.map(format -> format, format -> format), format ->
                    DEFAULT_FORMATS.values().stream().anyMatch(value -> value.equals(format) && value.getStepRate() == format.getStepRate()) ? Either.left(format) : Either.right(format));
    private static final Codec<Integer> NAMED_SIZE_CODEC = Codec.STRING.comapFlatMap(name -> {
        final var size = DEFAULT_BUFFER_SIZES.get(name.toUpperCase(Locale.ROOT));
        return size != null ? DataResult.success(size) : DataResult.error(() -> "Unknown default buffer size: " + name);
    }, size -> DEFAULT_BUFFER_SIZES.entrySet().stream().filter(entry -> entry.getValue().equals(size))
            .map(Map.Entry::getKey).findFirst().orElseThrow());
    public static final Codec<Integer> BUFFER_SIZE_CODEC = Codec.either(NAMED_SIZE_CODEC, Codec.intRange(0, Integer.MAX_VALUE))
            .xmap(value -> value.map(size -> size, size -> size), size ->
                    DEFAULT_BUFFER_SIZES.containsValue(size) ? Either.left(size) : Either.right(size));
    public static Map<String, VertexFormat> getDefaultFormats() { return Map.copyOf(DEFAULT_FORMATS); }
    private VertexFormatCodec() {}
}
