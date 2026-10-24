package foundry.veil.fabric.compat.sodium;

import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.vertex.VertexFormat;
import foundry.veil.ext.sodium.ChunkVertexEncoderVertexExtension;
import net.caffeinemc.mods.sodium.client.render.chunk.vertex.format.ChunkVertexEncoder;
import net.caffeinemc.mods.sodium.client.render.chunk.vertex.format.ChunkVertexType;
import net.caffeinemc.mods.sodium.client.render.chunk.vertex.format.impl.CompactChunkVertex;
import org.lwjgl.system.MemoryUtil;

/** Genuine Sodium compact encoding plus a native signed-normalized four-byte normal attribute. */
public final class VeilChunkVertex implements ChunkVertexType {
    public static final int STRIDE = 24;
    public static final VertexFormat VERTEX_FORMAT = VertexFormat.builder(0)
            .addAttribute("a_Position", GpuFormat.RG32_UINT).addAttribute("a_Color", GpuFormat.RGBA8_UNORM)
            .addAttribute("a_TexCoord", GpuFormat.RG16_UINT).addAttribute("a_LightAndData", GpuFormat.RGBA8_UINT)
            .addAttribute("a_VeilNormal", GpuFormat.RGBA8_SNORM).build();
    private final ChunkVertexEncoder compact = new CompactChunkVertex().getEncoder();
    public VeilChunkVertex() {
        if (CompactChunkVertex.VERTEX_FORMAT.getVertexSize() != 20) throw new IllegalStateException("Unsupported native Sodium compact vertex stride");
    }
    @Override public VertexFormat getVertexFormat() { return VERTEX_FORMAT; }
    @Override public ChunkVertexEncoder getEncoder() {
        return (pointer, material, vertices, section) -> {
            this.compact.write(pointer, material, vertices, section);
            // Expand backwards so every source word is read before any overlapping destination is written.
            for (int i = 3; i >= 0; i--) {
                long source=pointer+(long)i*20, destination=pointer+(long)i*STRIDE;
                int a=MemoryUtil.memGetInt(source),b=MemoryUtil.memGetInt(source+4),c=MemoryUtil.memGetInt(source+8),
                        d=MemoryUtil.memGetInt(source+12),e=MemoryUtil.memGetInt(source+16);
                MemoryUtil.memPutInt(destination,a);MemoryUtil.memPutInt(destination+4,b);MemoryUtil.memPutInt(destination+8,c);
                MemoryUtil.memPutInt(destination+12,d);MemoryUtil.memPutInt(destination+16,e);
                MemoryUtil.memPutInt(destination+20,((ChunkVertexEncoderVertexExtension)vertices[i]).veil$getPackedNormal());
            }
            return pointer+4L*STRIDE;
        };
    }
}
