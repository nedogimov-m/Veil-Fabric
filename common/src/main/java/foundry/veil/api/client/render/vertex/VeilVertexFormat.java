package foundry.veil.api.client.render.vertex;

import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.vertex.VertexFormat;
import com.mojang.renderpearl.api.vertex.VertexFormatElement;

/** Native named attributes with explicit offsets; no mutable global vertex-element ID table. */
public final class VeilVertexFormat {
    public static final VertexFormatElement BONE_INDEX = new VertexFormatElement("BoneIndex", DefaultVertexFormat.ENTITY.getVertexSize(), GpuFormat.R16_UINT);
    public static final VertexFormat SKINNED_MESH = skinned();
    public static final VertexFormat QUASAR_PARTICLE = VertexFormat.builder(0)
            .addAttribute("Position", GpuFormat.RGB32_FLOAT).addAttribute("Normal", 4, GpuFormat.RGB8_SNORM).build();
    private static VertexFormat skinned() {
        final var builder = VertexFormat.builder(0);
        for (var element : DefaultVertexFormat.ENTITY.getElements()) builder.addAttribute(element.name(), element.offset(),
                element.format().blockSize(), element.format(), 1);
        builder.addAttribute(BONE_INDEX.name(), BONE_INDEX.offset(), 4, BONE_INDEX.format(), 1);
        return builder.build();
    }
    public static VertexFormatElement register(String name, int offset, GpuFormat format) {
        return new VertexFormatElement(name, offset, format);
    }
    private VeilVertexFormat() {}
}
