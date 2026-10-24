package foundry.veil.api.client.necromancer.render;

import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.renderpearl.api.pipeline.PrimitiveTopology;
import foundry.veil.api.client.necromancer.Skeleton;
import foundry.veil.api.client.render.MatrixStack;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import it.unimi.dsi.fastutil.ints.IntList;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.util.Mth;
import org.joml.*;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.system.NativeResource;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

/** CPU mesh ownership with immutable deformed native feature snapshots. GPU feature staging belongs to Minecraft. */
public final class Skin implements NativeResource {
    private final List<Vertex> vertices;
    private final int[] indices;
    private final List<String> boneNames;
    private boolean retired;
    private record Vertex(float x, float y, float z, float u, float v, float nx, float ny, float nz, int bone) {}
    public record DeformedVertex(float x, float y, float z, float u, float v, float nx, float ny, float nz, int color, int overlay, int light) {}
    private Skin(List<Vertex> vertices, int[] indices, List<String> bones) {
        this.vertices = List.copyOf(vertices); this.indices = indices.clone(); this.boneNames = List.copyOf(bones);
    }
    public int getSkeletonDataSize() { return this.boneNames.size(); }
    /** Serialize the live hierarchy/interpolated pose now; submitted geometry never reads the mutable skeleton or source skin. */
    public Snapshot snapshot(Skeleton skeleton, Matrix4fc transform, Vector4fc tint, int overlay, int light, float partial) {
        if (this.retired) throw new IllegalStateException("Skin is retired");
        Matrix4x3f[] matrices = new Matrix4x3f[this.boneNames.size()];
        Matrix3f[] normals = new Matrix3f[this.boneNames.size()];
        Vector4f[] colors = new Vector4f[this.boneNames.size()];
        for (int i = 0; i < this.boneNames.size(); i++) {
            var bone = skeleton.bones.get(this.boneNames.get(i));
            if (bone == null) throw new IllegalArgumentException("Skin references a missing skeleton bone: " + this.boneNames.get(i));
            matrices[i] = bone.getModelTransform(new Matrix4x3f().set(transform), new Quaternionf(), partial);
            normals[i] = matrices[i].normal(new Matrix3f());
            colors[i] = new Vector4f(); bone.getColor(colors[i], partial); colors[i].mul(tint);
        }
        List<DeformedVertex> output = new ArrayList<>(this.vertices.size());
        for (Vertex vertex : this.vertices) {
            var position = matrices[vertex.bone].transformPosition(new Vector3f(vertex.x, vertex.y, vertex.z));
            var normal = normals[vertex.bone].transform(vertex.nx, vertex.ny, vertex.nz, new Vector3f());
            if (normal.lengthSquared() > 0) normal.normalize();
            var color = colors[vertex.bone];
            int packed = channel(color.w) << 24 | channel(color.x) << 16 | channel(color.y) << 8 | channel(color.z);
            output.add(new DeformedVertex(position.x, position.y, position.z, vertex.u, vertex.v, normal.x, normal.y, normal.z, packed, overlay, light));
        }
        return new Snapshot(output, this.indices);
    }
    private static int channel(float value) { return java.lang.Math.round(Mth.clamp(value, 0, 1) * 255); }
    public static final class Snapshot {
        private final List<DeformedVertex> vertices;
        private final int[] indices;
        private Snapshot(List<DeformedVertex> vertices, int[] indices) { this.vertices = List.copyOf(vertices); this.indices = indices.clone(); }
        public List<DeformedVertex> vertices() { return this.vertices; }
        public int[] indices() { return this.indices.clone(); }
        public void submit(SubmitNodeCollector collector, PoseStack poses, RenderType type) {
            var topology = type.primitiveTopology();
            if (topology != PrimitiveTopology.QUADS && topology != PrimitiveTopology.TRIANGLES)
                throw new IllegalArgumentException("Skinned triangle meshes require a native triangle or quad render type");
            collector.submitCustomGeometry(poses, type, (pose, output) -> {
                for (int i = 0; i < this.indices.length; i += 3) {
                    this.emit(pose, output, type, this.indices[i]); this.emit(pose, output, type, this.indices[i + 1]); this.emit(pose, output, type, this.indices[i + 2]);
                    // A degenerate fourth vertex lets native quad staging carry any indexed triangle without changing its winding.
                    if (topology == PrimitiveTopology.QUADS) this.emit(pose, output, type, this.indices[i + 2]);
                }
            });
        }
        private void emit(PoseStack.Pose pose, VertexConsumer output, RenderType type, int index) {
            DeformedVertex v = this.vertices.get(index);
            output.addVertex(pose, v.x, v.y, v.z);
            var format = type.format();
            if (format.contains("Color")) output.setColor(v.color);
            if (format.contains("UV0")) output.setUv(v.u, v.v);
            if (format.contains("UV1")) output.setOverlay(v.overlay);
            if (format.contains("UV2")) output.setLight(v.light);
            if (format.contains("Normal")) output.setNormal(pose, v.nx, v.ny, v.nz);
        }
    }
    @Override public void free() { this.retired = true; }
    public static Builder builder(int textureWidth, int textureHeight) {
        if (textureWidth <= 0 || textureHeight <= 0) throw new IllegalArgumentException("Skin texture dimensions must be positive");
        return new Builder(textureWidth, textureHeight);
    }

    public static class Builder {
        private final Vector3f POS = new Vector3f();
        private final Vector3f NORMAL = new Vector3f();

        private final ByteBufferBuilder vertices;
        private final IntList indices;
        private final List<String> boneNames;
        private final float textureWidth;
        private final float textureHeight;
        private final Matrix4f position;
        private final Matrix3f normal;

        private int nextIndex;

        private Builder(float textureWidth, float textureHeight) {
            this.vertices = new ByteBufferBuilder(Skeleton.MAX_BONES * 24 * 24);
            this.indices = new IntArrayList();
            this.boneNames = new ArrayList<>();
            this.textureWidth = textureWidth;
            this.textureHeight = textureHeight;
            this.position = new Matrix4f();
            this.normal = new Matrix3f();

            this.nextIndex = 0;
        }

        private static byte normalIntValue(float value) {
            return (byte) ((int) (Mth.clamp(value, -1.0F, 1.0F) * 127.0F) & 0xFF);
        }

        public Builder startBone(String boneId) {
            if (this.boneNames.contains(boneId)) {
                throw new IllegalStateException("Bone '" + boneId + "' has already defined mesh data");
            }
            if (this.boneNames.size() >= Skeleton.MAX_BONES) {
                throw new IllegalStateException("Too many bones defined. Max is " + Skeleton.MAX_BONES);
            }

            this.boneNames.add(boneId);
            return this;
        }

        public Builder setTransform(MatrixStack stack) {
            return this.setTransform(stack.position());
        }

        public Builder setTransform(PoseStack stack) {
            return this.setTransform(stack.last());
        }

        public Builder setTransform(PoseStack.Pose pose) {
            return this.setTransform(pose.pose());
        }

        public Builder setTransform(Matrix4fc position) {
            this.position.set(position);
            this.position.normal(this.normal);
            return this;
        }

        public Builder addVertex(float x, float y, float z, float u, float v, float normalX, float normalY, float normalZ) {
            if (this.boneNames.isEmpty()) {
                throw new IllegalStateException("No bone specified. Call #startBone(String) to start building a mesh.");
            }

            this.position.transformPosition(x, y, z, POS);
            this.normal.transform(normalX, normalY, normalZ, NORMAL);
            long pointer = this.vertices.reserve(24);
            MemoryUtil.memPutFloat(pointer, POS.x);
            MemoryUtil.memPutFloat(pointer + 4, POS.y);
            MemoryUtil.memPutFloat(pointer + 8, POS.z);
            MemoryUtil.memPutFloat(pointer + 12, u);
            MemoryUtil.memPutFloat(pointer + 16, v);
            MemoryUtil.memPutByte(pointer + 20, normalIntValue(NORMAL.x));
            MemoryUtil.memPutByte(pointer + 21, normalIntValue(NORMAL.y));
            MemoryUtil.memPutByte(pointer + 22, normalIntValue(NORMAL.z));
            MemoryUtil.memPutByte(pointer + 23, (byte) (this.boneNames.size() - 1));
            return this;
        }

        public Builder addIndex(int index) {
            this.indices.add(index);
            if (index > this.nextIndex) {
                this.nextIndex = index + 1;
            }
            return this;
        }

        public Builder addQuadIndices(int index) {
            this.addIndex(index);
            this.addIndex(index + 1);
            this.addIndex(index + 2);
            this.addIndex(index + 2);
            this.addIndex(index + 3);
            this.addIndex(index);
            return this;
        }

        public Skin.Builder addCube(
                float xSize, float ySize, float zSize,
                float xOffset, float yOffset, float zOffset,
                float xInflate, float yInflate, float zInflate,
                float uOffset, float vOffset, boolean mirrored) {
            float minX = xOffset - xInflate, minY = yOffset - yInflate, minZ = zOffset - zInflate;
            float maxX = xOffset + xSize + xInflate, maxY = yOffset + ySize + yInflate, maxZ = zOffset + zSize + zInflate;

            float u0 = uOffset, u1 = u0 + zSize, u2 = u1 + xSize, u3 = u2 + zSize, u4 = u3 + xSize;
            u0 /= this.textureWidth;
            u1 /= this.textureWidth;
            u2 /= this.textureWidth;
            u3 /= this.textureWidth;
            u4 /= this.textureWidth;
            float topBottomU0 = uOffset + zSize, topBottomU1 = topBottomU0 + xSize, topBottomU2 = topBottomU1 + xSize;
            topBottomU0 /= this.textureWidth;
            topBottomU1 /= this.textureWidth;
            topBottomU2 /= this.textureWidth;

            float v0 = vOffset, v1 = v0 + zSize, v2 = v1 + ySize;
            v0 /= this.textureHeight;
            v1 /= this.textureHeight;
            v2 /= this.textureHeight;

            // A little gross, but should work in every case.
            if (!mirrored) {
                // Up
                this.addVertex(minX, maxY, minZ, topBottomU1, v1, 0.0F, 1.0F, 0.0F);
                this.addVertex(minX, maxY, maxZ, topBottomU1, v0, 0.0F, 1.0F, 0.0F);
                this.addVertex(maxX, maxY, maxZ, topBottomU0, v0, 0.0F, 1.0F, 0.0F);
                this.addVertex(maxX, maxY, minZ, topBottomU0, v1, 0.0F, 1.0F, 0.0F);

                // Down
                this.addVertex(maxX, minY, maxZ, topBottomU1, v0, 0.0F, -1.0F, 0.0F);
                this.addVertex(minX, minY, maxZ, topBottomU2, v0, 0.0F, -1.0F, 0.0F);
                this.addVertex(minX, minY, minZ, topBottomU2, v1, 0.0F, -1.0F, 0.0F);
                this.addVertex(maxX, minY, minZ, topBottomU1, v1, 0.0F, -1.0F, 0.0F);

                // East
                this.addVertex(maxX, minY, maxZ, u0, v2, 1.0F, 0.0F, 0.0F);
                this.addVertex(maxX, minY, minZ, u1, v2, 1.0F, 0.0F, 0.0F);
                this.addVertex(maxX, maxY, minZ, u1, v1, 1.0F, 0.0F, 0.0F);
                this.addVertex(maxX, maxY, maxZ, u0, v1, 1.0F, 0.0F, 0.0F);

                // West
                this.addVertex(minX, minY, minZ, u2, v2, -1.0F, 0.0F, 0.0F);
                this.addVertex(minX, minY, maxZ, u3, v2, -1.0F, 0.0F, 0.0F);
                this.addVertex(minX, maxY, maxZ, u3, v1, -1.0F, 0.0F, 0.0F);
                this.addVertex(minX, maxY, minZ, u2, v1, -1.0F, 0.0F, 0.0F);

                // North
                this.addVertex(maxX, minY, minZ, u1, v2, 0.0F, 0.0F, -1.0F);
                this.addVertex(minX, minY, minZ, u2, v2, 0.0F, 0.0F, -1.0F);
                this.addVertex(minX, maxY, minZ, u2, v1, 0.0F, 0.0F, -1.0F);
                this.addVertex(maxX, maxY, minZ, u1, v1, 0.0F, 0.0F, -1.0F);

                // South
                this.addVertex(minX, minY, maxZ, u3, v2, 0.0F, 0.0F, 1.0F);
                this.addVertex(maxX, minY, maxZ, u4, v2, 0.0F, 0.0F, 1.0F);
                this.addVertex(maxX, maxY, maxZ, u4, v1, 0.0F, 0.0F, 1.0F);
                this.addVertex(minX, maxY, maxZ, u3, v1, 0.0F, 0.0F, 1.0F);
            } else {
                // Up, mirrored
                this.addVertex(minX, maxY, minZ, topBottomU0, v1, 0.0F, 1.0F, 0.0F);
                this.addVertex(minX, maxY, maxZ, topBottomU0, v0, 0.0F, 1.0F, 0.0F);
                this.addVertex(maxX, maxY, maxZ, topBottomU1, v0, 0.0F, 1.0F, 0.0F);
                this.addVertex(maxX, maxY, minZ, topBottomU1, v1, 0.0F, 1.0F, 0.0F);

                // Down, mirrored
                this.addVertex(maxX, minY, maxZ, topBottomU2, v0, 0.0F, -1.0F, 0.0F);
                this.addVertex(minX, minY, maxZ, topBottomU1, v0, 0.0F, -1.0F, 0.0F);
                this.addVertex(minX, minY, minZ, topBottomU1, v1, 0.0F, -1.0F, 0.0F);
                this.addVertex(maxX, minY, minZ, topBottomU2, v1, 0.0F, -1.0F, 0.0F);

                // East, mirrored
                this.addVertex(maxX, minY, maxZ, u3, v2, 1.0F, 0.0F, 0.0F);
                this.addVertex(maxX, minY, minZ, u2, v2, 1.0F, 0.0F, 0.0F);
                this.addVertex(maxX, maxY, minZ, u2, v1, 1.0F, 0.0F, 0.0F);
                this.addVertex(maxX, maxY, maxZ, u3, v1, 1.0F, 0.0F, 0.0F);

                // West, mirrored
                this.addVertex(minX, minY, minZ, u1, v2, -1.0F, 0.0F, 0.0F);
                this.addVertex(minX, minY, maxZ, u0, v2, -1.0F, 0.0F, 0.0F);
                this.addVertex(minX, maxY, maxZ, u0, v1, -1.0F, 0.0F, 0.0F);
                this.addVertex(minX, maxY, minZ, u1, v1, -1.0F, 0.0F, 0.0F);

                // North, mirrored
                this.addVertex(maxX, minY, minZ, u2, v2, 0.0F, 0.0F, -1.0F);
                this.addVertex(minX, minY, minZ, u1, v2, 0.0F, 0.0F, -1.0F);
                this.addVertex(minX, maxY, minZ, u1, v1, 0.0F, 0.0F, -1.0F);
                this.addVertex(maxX, maxY, minZ, u2, v1, 0.0F, 0.0F, -1.0F);

                // South, mirrored
                this.addVertex(minX, minY, maxZ, u4, v2, 0.0F, 0.0F, 1.0F);
                this.addVertex(maxX, minY, maxZ, u3, v2, 0.0F, 0.0F, 1.0F);
                this.addVertex(maxX, maxY, maxZ, u3, v1, 0.0F, 0.0F, 1.0F);
                this.addVertex(minX, maxY, maxZ, u4, v1, 0.0F, 0.0F, 1.0F);
            }

            for (int i = 0; i < 6; i++) {
                this.addQuadIndices(this.nextIndex());
            }

            return this;
        }

        public Builder addTri(
                float x1, float y1, float z1, float u1, float v1,
                float x2, float y2, float z2, float u2, float v2,
                float x3, float y3, float z3, float u3, float v3,
                float normalX, float normalY, float normalZ) {
            this.addVertex(x1, y1, z1, u1, v1, normalX, normalY, normalZ);
            this.addVertex(x2, y2, z2, u2, v2, normalX, normalY, normalZ);
            this.addVertex(x3, y3, z3, u3, v3, normalX, normalY, normalZ);
            this.addIndex(this.nextIndex());
            this.addIndex(this.nextIndex());
            this.addIndex(this.nextIndex());
            return this;
        }

        public Builder addFace(
                float x1, float y1, float z1, float u1, float v1,
                float x2, float y2, float z2, float u2, float v2,
                float x3, float y3, float z3, float u3, float v3,
                float x4, float y4, float z4, float u4, float v4,
                float normalX, float normalY, float normalZ) {
            this.addVertex(x1, y1, z1, u1, v1, normalX, normalY, normalZ);
            this.addVertex(x2, y2, z2, u2, v2, normalX, normalY, normalZ);
            this.addVertex(x3, y3, z3, u3, v3, normalX, normalY, normalZ);
            this.addVertex(x4, y4, z4, u4, v4, normalX, normalY, normalZ);
            this.addQuadIndices(this.nextIndex());
            return this;
        }

        public int nextIndex() {
            return this.nextIndex;
        }

        public Skin build() {
            try (this.vertices; ByteBufferBuilder.Result result = this.vertices.build()) {
                if (result == null) throw new IllegalStateException("No mesh data provided to skin");
                ByteBuffer bytes = result.byteBuffer().order(java.nio.ByteOrder.nativeOrder());
                List<Vertex> mesh = new ArrayList<>();
                for (int offset = 0; offset < bytes.remaining(); offset += 24) {
                    mesh.add(new Vertex(bytes.getFloat(offset), bytes.getFloat(offset + 4), bytes.getFloat(offset + 8),
                            bytes.getFloat(offset + 12), bytes.getFloat(offset + 16), bytes.get(offset + 20) / 127f,
                            bytes.get(offset + 21) / 127f, bytes.get(offset + 22) / 127f, bytes.get(offset + 23) & 255));
                }
                int[] triangles = this.indices.toIntArray();
                if (triangles.length % 3 != 0) throw new IllegalStateException("Skin index count must contain complete triangles");
                for (int index : triangles) if (index < 0 || index >= mesh.size()) throw new IllegalArgumentException("Skin index outside mesh: " + index);
                return new Skin(mesh, triangles, this.boneNames);
            }
        }
    }
}
