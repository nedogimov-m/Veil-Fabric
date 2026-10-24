package foundry.veil.api.client.render.gpu;

import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.vertex.VertexFormat;
import java.nio.ByteBuffer;

/** Actual native instance layouts, with a portable aligned area-light stride. */
public final class NativeLightInstances {
    public static final VertexFormat POINT = VertexFormat.builder(1)
            .addAttribute("LightPosition", GpuFormat.RGB32_FLOAT).addAttribute("Color", GpuFormat.RGB32_FLOAT)
            .addAttribute("Distance", GpuFormat.R32_FLOAT).addAttribute("Occluded", GpuFormat.R32_FLOAT).build();
    public static final VertexFormat AREA = VertexFormat.builder(1)
            .addAttribute("LightMatrix", GpuFormat.RGBA32_FLOAT, 4).addAttribute("Color", GpuFormat.RGB32_FLOAT)
            .addAttribute("Size", GpuFormat.RG32_FLOAT).addAttribute("NormalizedAngle", GpuFormat.R32_FLOAT)
            .addAttribute("Distance", GpuFormat.R32_FLOAT).addAttribute("Occluded", GpuFormat.R32_FLOAT).build();
    public static void expandArea(ByteBuffer packed, ByteBuffer output) {
        if (packed.remaining() != 94 || output.remaining() < 96) throw new IllegalArgumentException("Incorrect area-light instance stride");
        int start = packed.position();
        output.put(packed.duplicate().limit(start + 84));
        output.putFloat(Short.toUnsignedInt(packed.getShort(start + 84)) / 65535f);
        output.putFloat(packed.getFloat(start + 86)); output.putFloat(packed.getFloat(start + 90));
    }
    private NativeLightInstances() { }
}
