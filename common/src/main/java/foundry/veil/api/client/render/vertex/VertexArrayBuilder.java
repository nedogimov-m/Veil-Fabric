package foundry.veil.api.client.render.vertex;

import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.api.vertex.VertexFormat;
import java.util.Objects;

/** Native vertex bindings use named formats with explicit attribute offsets, stride and instance step rate. */
public final class VertexArrayBuilder {
    private final VertexArray owner;
    VertexArrayBuilder(VertexArray owner) { this.owner = owner; }
    public VertexArray vertexArray() { return this.owner; }
    public VertexArrayBuilder defineVertexBuffer(int binding, GpuBufferSlice data, VertexFormat format) {
        this.owner.setVertexBuffer(binding, data, format); return this;
    }
    public VertexArrayBuilder removeVertexBuffer(int binding) { this.owner.removeVertexBuffer(binding); return this; }
    public VertexArrayBuilder clearVertexBuffers() { this.owner.clearVertexBuffers(); return this; }
    static void validate(int slot, GpuBufferSlice data, VertexFormat format) {
        Objects.requireNonNull(data); Objects.requireNonNull(format);
        if (slot < 0 || slot >= RenderPass.MAX_VERTEX_BUFFERS) throw new IllegalArgumentException("Native vertex binding out of range: " + slot);
        if (data.buffer().isClosed() || (data.buffer().usage() & GpuBuffer.USAGE_VERTEX) == 0) throw new IllegalArgumentException("Vertex binding requires live native vertex storage");
        if (format.getVertexSize() <= 0 || data.length() < format.getVertexSize() || data.offset() % 4 != 0) throw new IllegalArgumentException("Invalid native vertex range/stride");
    }
    public record Binding(GpuBufferSlice data, VertexFormat format) { }
}
