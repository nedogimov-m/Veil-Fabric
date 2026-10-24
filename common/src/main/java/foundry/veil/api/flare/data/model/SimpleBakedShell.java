package foundry.veil.api.flare.data.model;

import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import com.mojang.renderpearl.api.commands.CommandEncoder;
import com.mojang.renderpearl.api.pipeline.PrimitiveTopology;
import com.mojang.renderpearl.api.vertex.VertexFormat;
import foundry.veil.api.client.render.vertex.VertexArray;
import foundry.veil.api.flare.model.BakedShell;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.List;

/**
 * @since 2.5.0
 */
public class SimpleBakedShell implements BakedShell {

    protected final List<FlareBakedQuad> faces;
    private final Map<VertexFormat, VertexArray> vertexArrays = new HashMap<>();

    public SimpleBakedShell(List<FlareBakedQuad> faces) {
        this.faces = List.copyOf(faces);
    }

    @Override
    public List<FlareBakedQuad> getQuads() {
        return this.faces;
    }

    @Override
    public VertexArray getVertexArray(CommandEncoder encoder, VertexFormat format) {
        VertexArray existing = this.vertexArrays.get(format);
        if (existing != null) return existing;
        if (this.faces.isEmpty()) throw new IllegalStateException("Cannot upload an empty shell");
        VertexArray mesh = VertexArray.create();
        try (ByteBufferBuilder bytes = new ByteBufferBuilder(Math.multiplyExact(this.faces.size(), 4 * format.getVertexSize()))) {
            BufferBuilder builder = new BufferBuilder(bytes, PrimitiveTopology.QUADS, format);
            for (FlareBakedQuad quad : this.faces) quad.putBakedQuadInto(builder, format);
            mesh.upload(encoder, builder.buildOrThrow(), VertexArray.DrawUsage.STATIC);
        } catch (RuntimeException | Error failure) {
            mesh.free();
            throw failure;
        }
        this.vertexArrays.put(format, mesh);
        return mesh;
    }

    public static Builder builder() {
        return new Builder();
    }

    @Override
    public void free() {
        this.vertexArrays.values().forEach(VertexArray::free);
        this.vertexArrays.clear();
    }

    public static class Builder {

        protected final List<FlareBakedQuad> faces = new ArrayList<>();

        public Builder addFace(FlareBakedQuad face) {
            this.faces.add(face);
            return this;
        }

        public SimpleBakedShell build() {
            return new SimpleBakedShell(this.faces);
        }
    }
}
