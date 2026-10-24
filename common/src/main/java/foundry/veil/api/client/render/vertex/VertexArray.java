package foundry.veil.api.client.render.vertex;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import com.mojang.renderpearl.api.commands.CommandEncoder;
import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.api.device.GpuDevice;
import com.mojang.renderpearl.api.pipeline.IndexType;
import com.mojang.renderpearl.api.pipeline.PrimitiveTopology;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import com.mojang.renderpearl.api.vertex.VertexFormat;
import net.minecraft.client.renderer.StagedVertexBuffer;
import net.minecraft.client.renderer.rendertype.RenderType;
import org.jetbrains.annotations.Nullable;
import org.lwjgl.system.NativeResource;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.*;

/**
 * Native reusable indexed mesh with explicit upload/pass ownership and typed vertex bindings.
 * Bind the compatible pipeline before drawing. Keep this mesh unchanged/alive until submit, or
 * retain a Draw while recording and close it after submission; retained draws survive replacement/free.
 * Borrowed buffers retain their caller-owned contents and lifetime contract.
 *
 * @author Ocelot
 */
public final class VertexArray implements NativeResource, AutoCloseable {
    public static final int VERTEX_BUFFER = 0;
    public static final int INSTANCE_BUFFER = 1;
    private final GpuDevice device;
    private final String label;
    private final Map<Integer, GpuBuffer> owned = new HashMap<>();
    private final Set<GpuBuffer> retired = Collections.newSetFromMap(new IdentityHashMap<>());
    private final Map<Integer, VertexArrayBuilder.Binding> bindings = new TreeMap<>();
    private final VertexArrayBuilder builder = new VertexArrayBuilder(this);
    private @Nullable GpuBuffer ownedIndices;
    private @Nullable GpuBufferSlice indices;
    private IndexType indexType = IndexType.SHORT;
    private PrimitiveTopology topology = PrimitiveTopology.TRIANGLES;
    private int indexCount;
    private int vertexCount;
    private int leases;
    private boolean closeRequested;
    private boolean closed;

    private VertexArray(GpuDevice device, String label) { this.device = Objects.requireNonNull(device); this.label = Objects.requireNonNull(label); }
    public static VertexArray create() { return create(RenderSystem.getDevice(), "Veil mesh"); }
    public static VertexArray create(GpuDevice device, String label) { return new VertexArray(device, label); }
    public static VertexArray[] create(int count) {
        if (count < 0) throw new IllegalArgumentException("Negative mesh count");
        VertexArray[] meshes = new VertexArray[count]; create(meshes); return meshes;
    }
    public static void create(VertexArray[] meshes) {
        for (int index = 0; index < meshes.length; index++) meshes[index] = create();
    }
    private void ensureOpen() { if (this.closed || this.closeRequested) throw new IllegalStateException("Mesh is retired: " + this.label); }
    public boolean isClosed() { return this.closed; }
    public VertexArrayBuilder editFormat() { this.ensureOpen(); return this.builder; }
    public Map<Integer, VertexFormat> formats() {
        this.ensureOpen(); Map<Integer, VertexFormat> formats = new TreeMap<>();
        this.bindings.forEach((slot, binding) -> formats.put(slot, binding.format())); return Collections.unmodifiableMap(formats);
    }
    public @Nullable GpuBuffer vertexBuffer(int binding) { this.ensureOpen(); var value = this.bindings.get(binding); return value == null ? null : value.data().buffer(); }
    public @Nullable GpuBufferSlice indexBuffer() { this.ensureOpen(); return this.indices; }
    public int getIndexCount() { this.ensureOpen(); return this.indexCount; }
    public int getVertexCount() { this.ensureOpen(); return this.vertexCount; }
    public IndexType getIndexType() { this.ensureOpen(); return this.indexType; }
    public PrimitiveTopology getDrawMode() { this.ensureOpen(); return this.topology; }
    public void setDrawMode(PrimitiveTopology mode) { this.ensureOpen(); this.topology = Objects.requireNonNull(mode); }
    public void setVertexCount(int count) { this.ensureOpen(); if (count < 0) throw new IllegalArgumentException("Negative vertex count"); this.vertexCount = count; }
    public void setIndexCount(int count, IndexType type) {
        this.ensureOpen(); Objects.requireNonNull(type);
        if (count < 0 || this.indices != null && (long) count * type.bytes > this.indices.length()) throw new IllegalArgumentException("Index count exceeds selected storage");
        this.indexCount = count; this.indexType = type;
    }
    public void setVertexBuffer(int slot, GpuBufferSlice data, VertexFormat format) {
        this.ensureOpen(); VertexArrayBuilder.validate(slot, data, format);
        GpuBuffer previous = this.owned.get(slot);
        if (previous != data.buffer()) { this.owned.remove(slot); this.retire(previous); }
        this.bindings.put(slot, new VertexArrayBuilder.Binding(data, format));
        this.releaseRetired();
    }
    public void removeVertexBuffer(int slot) {
        this.ensureOpen(); this.bindings.remove(slot); this.retire(this.owned.remove(slot));
    }
    public void clearVertexBuffers() { this.ensureOpen(); for (int slot : List.copyOf(this.bindings.keySet())) this.removeVertexBuffer(slot); }
    public void setIndexBuffer(GpuBufferSlice data, IndexType type, int count) {
        this.ensureOpen(); validateIndices(data, type, count);
        GpuBuffer previous = this.ownedIndices;
        if (this.ownedIndices != data.buffer()) this.ownedIndices = null;
        this.indices = data; this.indexType = type; this.indexCount = count;
        if (previous != data.buffer()) this.retire(previous);
    }
    private static void validateIndices(GpuBufferSlice data, IndexType type, int count) {
        Objects.requireNonNull(data); Objects.requireNonNull(type);
        if (data.buffer().isClosed() || (data.buffer().usage() & GpuBuffer.USAGE_INDEX) == 0) throw new IllegalArgumentException("Index binding requires live native index storage");
        if (count < 0 || data.offset() % type.bytes != 0 || (long) count * type.bytes > data.length()) throw new IllegalArgumentException("Invalid typed index range");
    }
    private GpuBuffer allocate(CommandEncoder encoder, ByteBuffer bytes, int usage, String suffix) {
        if (!bytes.hasRemaining()) throw new IllegalArgumentException("Native mesh storage must be nonempty");
        GpuBuffer buffer = this.device.createBuffer(() -> this.label + suffix, usage | GpuBuffer.USAGE_COPY_DST | GpuBuffer.USAGE_COPY_SRC, bytes.remaining());
        try { encoder.writeToBuffer(buffer.slice(), bytes.duplicate()); return buffer; }
        catch (RuntimeException | Error failure) { buffer.close(); throw failure; }
    }
    /** Every replacement owns separate storage so retained/recorded earlier draws keep their bytes. */
    public void uploadVertexBuffer(CommandEncoder encoder, int slot, ByteBuffer bytes, VertexFormat format, DrawUsage usage) {
        this.ensureOpen(); Objects.requireNonNull(usage); Objects.requireNonNull(format);
        if (slot < 0 || slot >= RenderPass.MAX_VERTEX_BUFFERS || format.getVertexSize() <= 0 || bytes.remaining() % format.getVertexSize() != 0)
            throw new IllegalArgumentException("Invalid vertex binding/stride");
        GpuBuffer replacement = this.allocate(encoder, bytes, GpuBuffer.USAGE_VERTEX, "/vertices/" + slot);
        GpuBuffer previous = this.owned.put(slot, replacement);
        this.bindings.put(slot, new VertexArrayBuilder.Binding(replacement.slice(), format)); this.retire(previous);
        if (slot == 0 && format.getStepRate() == 0) this.vertexCount = bytes.remaining() / format.getVertexSize();
    }
    public void uploadIndexBuffer(CommandEncoder encoder, ByteBuffer bytes, IndexType type) {
        this.ensureOpen(); Objects.requireNonNull(type);
        if (bytes.remaining() % type.bytes != 0) throw new IllegalArgumentException("Partial native index element");
        GpuBuffer replacement = this.allocate(encoder, bytes, GpuBuffer.USAGE_INDEX, "/indices");
        GpuBuffer previous = this.ownedIndices;
        this.ownedIndices = replacement; this.indices = replacement.slice(); this.indexType = type; this.indexCount = bytes.remaining() / type.bytes;
        this.retire(previous);
    }
    /** Consumes the real Minecraft mesh, including sorted indices or the actual sequential topology pattern. */
    public void upload(CommandEncoder encoder, MeshData data, DrawUsage usage) {
        this.ensureOpen(); Objects.requireNonNull(usage);
        try (data) {
            var state = data.drawState();
            if (state.vertexCount() == 0 || state.indexCount() == 0) throw new IllegalArgumentException("Cannot upload an empty mesh");
            ByteBuffer vertices = data.vertexBuffer();
            if (vertices.remaining() != Math.multiplyExact(state.vertexCount(), state.format().getVertexSize())) throw new IllegalArgumentException("Minecraft mesh vertex size mismatch");
            ByteBuffer indexBytes = data.indexBuffer();
            IndexType type = state.indexType();
            if (indexBytes == null) indexBytes = sequential(state);
            if (indexBytes.remaining() != Math.multiplyExact(state.indexCount(), type.bytes)) throw new IllegalArgumentException("Minecraft mesh index size mismatch");
            GpuBuffer newVertices = this.allocate(encoder, vertices, GpuBuffer.USAGE_VERTEX, "/vertices/0");
            GpuBuffer newIndices;
            try { newIndices = this.allocate(encoder, indexBytes, GpuBuffer.USAGE_INDEX, "/indices"); }
            catch (RuntimeException | Error failure) { newVertices.close(); throw failure; }
            this.retire(this.owned.put(0, newVertices)); this.retire(this.ownedIndices);
            this.bindings.put(0, new VertexArrayBuilder.Binding(newVertices.slice(), state.format()));
            this.ownedIndices = newIndices; this.indices = newIndices.slice();
            this.vertexCount = state.vertexCount(); this.indexCount = state.indexCount(); this.indexType = type; this.topology = state.primitiveTopology();
            this.releaseRetired();
        }
    }
    private static ByteBuffer sequential(MeshData.DrawState state) {
        if (state.indexCount() != state.primitiveTopology().indexCount(state.vertexCount())) throw new IllegalArgumentException("Invalid sequential Minecraft topology");
        ByteBuffer bytes = ByteBuffer.allocateDirect(Math.multiplyExact(state.indexCount(), state.indexType().bytes)).order(ByteOrder.nativeOrder());
        boolean quads = state.primitiveTopology() == PrimitiveTopology.QUADS;
        boolean lines = state.primitiveTopology() == PrimitiveTopology.LINES;
        if (quads || lines) {
            if (state.vertexCount() % 4 != 0) throw new IllegalArgumentException("Incomplete sequential quad/line primitive");
            int[] pattern = quads ? new int[]{0, 1, 2, 2, 3, 0} : new int[]{0, 1, 2, 3, 2, 1};
            for (int vertex = 0; vertex < state.vertexCount(); vertex += 4) for (int index : pattern) putIndex(bytes, state.indexType(), vertex + index);
        } else for (int index = 0; index < state.indexCount(); index++) putIndex(bytes, state.indexType(), index);
        return bytes.flip();
    }
    private static void putIndex(ByteBuffer bytes, IndexType type, int index) { if (type == IndexType.SHORT) bytes.putShort((short) index); else bytes.putInt(index); }
    public Draw retain() {
        this.ensureOpen(); this.validate(); this.leases++;
        return new Draw(Map.copyOf(this.bindings), this.indices, this.indexType, this.topology, this.indexCount, this.vertexCount);
    }
    private void validate() {
        if (this.bindings.isEmpty()) throw new IllegalStateException("Mesh has no native vertex bindings");
        this.bindings.forEach((slot, binding) -> VertexArrayBuilder.validate(slot, binding.data(), binding.format()));
        if (this.indices != null) validateIndices(this.indices, this.indexType, this.indexCount);
        if (this.vertexCount <= 0) throw new IllegalStateException("Mesh has no vertices");
    }
    /** Immediate recording: the owner must remain unchanged/alive through encoder submission. */
    public void draw(RenderPass pass) { this.drawInstanced(pass, 1); }
    public void drawInstanced(RenderPass pass, int instances) {
        this.ensureOpen(); this.validate();
        draw(pass, this.bindings, this.indices, this.indexType, this.indexCount, this.vertexCount, instances);
    }
    private static void draw(RenderPass pass, Map<Integer, VertexArrayBuilder.Binding> bindings, @Nullable GpuBufferSlice indices, IndexType type, int count, int vertices, int instances) {
        if (instances < 0) throw new IllegalArgumentException("Negative instance count");
        bindings.forEach((slot, binding) -> {
            int step = binding.format().getStepRate();
            long elements = step == 0 ? vertices : Math.floorDiv((long) instances + step - 1, step);
            if (elements * binding.format().getVertexSize() > binding.data().length()) throw new IllegalArgumentException("Native mesh draw exceeds vertex/instance storage at binding " + slot);
        });
        bind(pass, bindings, indices, type);
        if (indices != null) pass.drawIndexed(count, instances, Math.toIntExact(indices.offset() / type.bytes), 0, 0);
        else pass.draw(vertices, instances, 0, 0);
    }
    private static void bind(RenderPass pass, Map<Integer, VertexArrayBuilder.Binding> bindings, @Nullable GpuBufferSlice indices, IndexType type) {
        bindings.forEach((slot, binding) -> {
            if (binding.data().buffer().isClosed()) throw new IllegalStateException("Native vertex storage expired");
            pass.setVertexBuffer(slot, binding.data());
        });
        if (indices != null) { if (indices.buffer().isClosed()) throw new IllegalStateException("Native index storage expired"); pass.setIndexBuffer(indices.buffer(), type); }
    }
    private void retire(@Nullable GpuBuffer buffer) { if (buffer != null) this.retired.add(buffer); this.releaseRetired(); }
    private void releaseRetired() {
        if (this.leases != 0) return;
        this.retired.removeIf(buffer -> {
            if (this.indices != null && this.indices.buffer() == buffer || this.bindings.values().stream().anyMatch(binding -> binding.data().buffer() == buffer)) return false;
            buffer.close(); return true;
        });
    }
    private void release() {
        if (--this.leases == 0) { this.releaseRetired(); if (this.closeRequested) this.dispose(); }
    }
    @Override public void free() { this.close(); }
    @Override public void close() { if (this.closed || this.closeRequested) return; this.closeRequested = true; if (this.leases == 0) this.dispose(); }
    private void dispose() {
        Set<GpuBuffer> buffers = Collections.newSetFromMap(new IdentityHashMap<>());
        buffers.addAll(this.owned.values()); buffers.addAll(this.retired);
        if (this.ownedIndices != null) buffers.add(this.ownedIndices);
        buffers.forEach(GpuBuffer::close); this.owned.clear(); this.retired.clear(); this.ownedIndices = null;
        this.bindings.clear(); this.indices = null; this.closed = true;
    }
    /** Retained immutable binding selection; close after the encoder using it has been submitted. */
    public final class Draw implements AutoCloseable {
        private final Map<Integer, VertexArrayBuilder.Binding> bindings;
        private final @Nullable GpuBufferSlice indices;
        private final IndexType indexType;
        private final PrimitiveTopology topology;
        private final int indexCount, vertexCount;
        private boolean closed;
        private Draw(Map<Integer, VertexArrayBuilder.Binding> bindings, @Nullable GpuBufferSlice indices, IndexType type, PrimitiveTopology topology, int count, int vertices) {
            this.bindings = bindings; this.indices = indices; this.indexType = type; this.topology = topology; this.indexCount = count; this.vertexCount = vertices;
        }
        private void ensureOpen() { if (this.closed) throw new IllegalStateException("Mesh draw lease is closed"); }
        public void validatePipeline(RenderPipeline pipeline) {
            this.ensureOpen();
            if (pipeline.getPrimitiveTopology() != this.topology) throw new IllegalArgumentException("Mesh and pipeline topology differ");
            this.bindings.forEach((slot, binding) -> {
                if (slot >= pipeline.getVertexFormatBindings().size() || !binding.format().equals(pipeline.getVertexFormatBinding(slot)))
                    throw new IllegalArgumentException("Mesh and pipeline vertex layout differ at binding " + slot);
            });
            for (int slot = 0; slot < pipeline.getVertexFormatBindings().size(); slot++)
                if (pipeline.getVertexFormatBinding(slot) != null && !this.bindings.containsKey(slot)) throw new IllegalArgumentException("Missing native mesh binding: " + slot);
        }
        public void draw(RenderPass pass) { this.drawInstanced(pass, 1); }
        public void drawInstanced(RenderPass pass, int instances) {
            this.ensureOpen(); VertexArray.draw(pass, this.bindings, this.indices, this.indexType, this.indexCount, this.vertexCount, instances);
        }
        /** The native indirect range is tightly packed, with genuine indexed/nonindexed command structures. */
        public void drawIndirect(RenderPass pass, GpuBufferSlice commands, int count) {
            this.ensureOpen();
            int stride = this.indices == null ? RenderPass.INDIRECT_DRAW_SIZE : RenderPass.INDIRECT_INDEXED_DRAW_SIZE;
            if (commands.buffer().isClosed() || (commands.buffer().usage() & GpuBuffer.USAGE_INDIRECT_PARAMETERS) == 0 || count < 0 || commands.offset() % 4 != 0 || commands.length() < (long) count * stride)
                throw new IllegalArgumentException("Invalid native indirect command range");
            if (this.indices != null && this.indices.offset() != 0) throw new IllegalArgumentException("Indirect indexed commands require a full index-buffer origin; firstIndex belongs to the native command");
            bind(pass, this.bindings, this.indices, this.indexType);
            if (this.indices == null) pass.drawIndirect(commands, count); else pass.drawIndexedIndirect(commands, count);
        }
        /** Uses real native type preparation/layers; keep this Draw retained until any deferred type queue is submitted. */
        public void drawWithRenderType(RenderPass pass, RenderType type) {
            type.prepare().drawFromBuffer(this.stagedGeometry(type), pass);
        }
        /** Immutable native geometry descriptor. Keep this Draw retained until its commands are submitted. */
        public StagedVertexBuffer.ExecuteInfo stagedGeometry(RenderType type) {
            this.ensureOpen();
            if (this.bindings.size() != 1 || !this.bindings.containsKey(0) || this.indices == null) throw new IllegalStateException("PreparedRenderType requires one indexed vertex binding; bind a native instanced pipeline explicitly");
            var vertices = this.bindings.get(0);
            if (!vertices.format().equals(type.format()) || this.topology != type.primitiveTopology() || vertices.data().offset() % vertices.format().getVertexSize() != 0)
                throw new IllegalArgumentException("Native type and mesh layout/topology differ");
            return new StagedVertexBuffer.ExecuteInfo(vertices.data().buffer(), this.indices.buffer(), this.indexType,
                    Math.toIntExact(vertices.data().offset() / vertices.format().getVertexSize()), Math.toIntExact(this.indices.offset() / this.indexType.bytes), this.indexCount, this.topology);
        }
        @Override public void close() { if (!this.closed) { this.closed = true; VertexArray.this.release(); } }
    }
    /** Retained source hints; all native storage uses explicit COPY_DST uploads rather than GL usage enums. */
    public enum DrawUsage { STATIC, DYNAMIC, STREAM }
}
