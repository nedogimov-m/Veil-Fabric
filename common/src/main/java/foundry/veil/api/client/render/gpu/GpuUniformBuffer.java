package foundry.veil.api.client.render.gpu;

import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.commands.CommandEncoder;
import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.api.device.GpuDevice;
import org.joml.Matrix2fc;
import org.joml.Matrix3fc;
import org.joml.Matrix4fc;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/** Native std140 uniform storage with explicit upload-before-pass ordering. */
public final class GpuUniformBuffer implements AutoCloseable {
    private final Layout layout;
    private final GpuBuffer buffer;
    private final ByteBuffer data;
    private boolean dirty = true;
    private boolean closed;

    public GpuUniformBuffer(GpuDevice device, String label, Layout layout) {
        this.layout = Objects.requireNonNull(layout);
        this.data = ByteBuffer.allocateDirect(layout.size()).order(ByteOrder.nativeOrder());
        this.buffer = device.createBuffer(() -> label, GpuBuffer.USAGE_UNIFORM | GpuBuffer.USAGE_COPY_DST, layout.size());
    }
    public Layout layout() { return this.layout; }
    public GpuBuffer buffer() { this.ensureOpen(); return this.buffer; }
    public boolean isDirty() { this.ensureOpen(); return this.dirty; }
    public boolean isClosed() { return this.closed || this.buffer.isClosed(); }
    /** A per-draw CPU value copy independent of later writes to this block. */
    public Snapshot snapshot() { this.ensureOpen(); return new Snapshot(this.layout, this.data.duplicate().clear()); }
    public void restore(Snapshot snapshot) {
        this.ensureOpen();
        if (!this.layout.equals(snapshot.layout())) throw new IllegalArgumentException("Snapshot has a different uniform block layout");
        this.restore(snapshot.bytes());
    }
    public void restore(ByteBuffer bytes) {
        this.ensureOpen();
        if (bytes.remaining() != this.layout.size()) throw new IllegalArgumentException("Snapshot byte size must exactly match uniform block size");
        this.data.clear().put(bytes.duplicate()); this.data.clear(); this.dirty = true;
    }
    public record Snapshot(Layout layout, ByteBuffer bytes) {
        public Snapshot {
            Objects.requireNonNull(layout); Objects.requireNonNull(bytes);
            if (bytes.remaining() != layout.size()) throw new IllegalArgumentException("Snapshot byte size does not match layout");
            ByteBuffer copy = ByteBuffer.allocateDirect(bytes.remaining()).order(ByteOrder.nativeOrder());
            copy.put(bytes.duplicate()).flip(); bytes = copy.asReadOnlyBuffer().order(ByteOrder.nativeOrder());
        }
        @Override public ByteBuffer bytes() { return this.bytes.asReadOnlyBuffer().order(ByteOrder.nativeOrder()); }
    }

    /** Read the pending CPU value; uniform storage is never shader-writable. */
    public float[] getFloats(String name, int element) {
        Field field = this.field(name);
        int base = field.offset(element);
        float[] values = new float[field.type().rows * field.type().columns];
        for (int column = 0; column < field.type().columns; column++) for (int row = 0; row < field.type().rows; row++) {
            int offset = base + (field.type().columns > 1 ? column * 16 : 0) + row * 4;
            values[column * field.type().rows + row] = field.type().integer
                    ? field.type().unsigned() ? Integer.toUnsignedLong(this.data.getInt(offset)) : this.data.getInt(offset)
                    : this.data.getFloat(offset);
        }
        return values;
    }
    public int[] getInts(String name, int element) {
        Field field = this.field(name);
        int base = field.offset(element);
        int[] values = new int[field.type().rows * field.type().columns];
        for (int column = 0; column < field.type().columns; column++) for (int row = 0; row < field.type().rows; row++) {
            int offset = base + (field.type().columns > 1 ? column * 16 : 0) + row * 4;
            values[column * field.type().rows + row] = field.type().integer ? this.data.getInt(offset) : (int) this.data.getFloat(offset);
        }
        return values;
    }

    public void setMatrixValues(String name, int element, int columns, int rows, float... values) {
        Field field = this.field(name);
        if (field.type().integer || columns < 2 || field.type().columns != columns || field.type().rows != rows || values.length != columns * rows) {
            throw new IllegalArgumentException("Wrong native matrix type/count for " + name);
        }
        int base = field.offset(element);
        for (int column = 0; column < columns; column++) for (int row = 0; row < rows; row++) this.data.putFloat(base + column * 16 + row * 4, values[column * rows + row]);
        this.dirty = true;
    }

    public void setFloat(String name, float value) { this.setFloats(name, 0, value); }
    public void setVector(String name, float... values) { this.setFloats(name, 0, values); }
    public void setFloats(String name, int element, float... values) {
        Field field = this.field(name);
        if (field.type().integer || field.type().columns > 1 || values.length != field.type().rows) {
            throw new IllegalArgumentException("Wrong float vector type/count for " + name);
        }
        int offset = field.offset(element);
        for (int index = 0; index < values.length; index++) this.data.putFloat(offset + index * 4, values[index]);
        this.dirty = true;
    }
    public void setInt(String name, int value) { this.setInts(name, 0, value); }
    public void setVectorI(String name, int... values) { this.setInts(name, 0, values); }
    public void setInts(String name, int element, int... values) {
        Field field = this.field(name);
        if (!field.type().integer || values.length != field.type().rows) {
            throw new IllegalArgumentException("Wrong integer vector type/count for " + name);
        }
        int offset = field.offset(element);
        for (int index = 0; index < values.length; index++) this.data.putInt(offset + index * 4, values[index]);
        this.dirty = true;
    }
    public void setMatrix(String name, Matrix2fc matrix) { this.setMatrix(name, 0, matrix); }
    public void setMatrix(String name, Matrix3fc matrix) { this.setMatrix(name, 0, matrix); }
    public void setMatrix(String name, Matrix4fc matrix) { this.setMatrix(name, 0, matrix); }
    public void setMatrix(String name, int element, Matrix2fc matrix) {
        Field field = this.matrixField(name, ValueType.MAT2);
        int base = field.offset(element);
        for (int col = 0; col < 2; col++) for (int row = 0; row < 2; row++) this.data.putFloat(base + col * 16 + row * 4, matrix.get(col, row));
        this.dirty = true;
    }
    public void setMatrix(String name, int element, Matrix3fc matrix) {
        Field field = this.matrixField(name, ValueType.MAT3);
        int base = field.offset(element);
        for (int col = 0; col < 3; col++) for (int row = 0; row < 3; row++) this.data.putFloat(base + col * 16 + row * 4, matrix.get(col, row));
        this.dirty = true;
    }
    public void setMatrix(String name, int element, Matrix4fc matrix) {
        Field field = this.matrixField(name, ValueType.MAT4);
        int base = field.offset(element);
        for (int col = 0; col < 4; col++) for (int row = 0; row < 4; row++) this.data.putFloat(base + col * 16 + row * 4, matrix.get(col, row));
        this.dirty = true;
    }
    private Field matrixField(String name, ValueType type) {
        Field field = this.field(name);
        if (field.type() != type) throw new IllegalArgumentException("Wrong matrix type for " + name + ": " + field.type());
        return field;
    }
    private Field field(String name) {
        this.ensureOpen();
        Field field = this.layout.fields().get(name);
        if (field == null) throw new IllegalArgumentException("Unknown uniform field: " + name);
        return field;
    }

    /** Record data updates before opening the render pass that consumes them. */
    public void upload(CommandEncoder encoder) {
        this.ensureOpen();
        if (this.dirty) {
            encoder.writeToBuffer(this.buffer.slice(), this.data.duplicate().clear());
            this.dirty = false;
        }
    }
    public void bind(RenderPass pass, String block) {
        this.ensureOpen();
        if (this.dirty) throw new IllegalStateException("Upload uniform buffer before opening the render pass: " + block);
        pass.setUniform(block, this.buffer);
    }
    private void ensureOpen() { if (this.closed || this.buffer.isClosed()) throw new IllegalStateException("Uniform buffer is closed"); }
    @Override public void close() { if (!this.closed) { this.closed = true; this.buffer.close(); } }

    public enum ValueType {
        FLOAT("float", 1, 1, false), INT("int", 1, 1, true), UINT("uint", 1, 1, true), BOOL("bool", 1, 1, true),
        VEC2("vec2", 2, 1, false), VEC3("vec3", 3, 1, false), VEC4("vec4", 4, 1, false),
        IVEC2("ivec2", 2, 1, true), IVEC3("ivec3", 3, 1, true), IVEC4("ivec4", 4, 1, true),
        UVEC2("uvec2", 2, 1, true), UVEC3("uvec3", 3, 1, true), UVEC4("uvec4", 4, 1, true),
        BVEC2("bvec2", 2, 1, true), BVEC3("bvec3", 3, 1, true), BVEC4("bvec4", 4, 1, true),
        MAT2("mat2", 2, 2, false), MAT3("mat3", 3, 3, false), MAT4("mat4", 4, 4, false),
        MAT2X3("mat2x3", 3, 2, false), MAT3X2("mat3x2", 2, 3, false),
        MAT2X4("mat2x4", 4, 2, false), MAT4X2("mat4x2", 2, 4, false),
        MAT3X4("mat3x4", 4, 3, false), MAT4X3("mat4x3", 3, 4, false);
        private final String glsl;
        private final int rows;
        private final int columns;
        private final boolean integer;
        ValueType(String glsl, int rows, int columns, boolean integer) { this.glsl = glsl; this.rows = rows; this.columns = columns; this.integer = integer; }
        public String glsl() { return this.glsl; }
        public int rows() { return this.rows; }
        public int columns() { return this.columns; }
        public boolean integer() { return this.integer; }
        public boolean unsigned() { return this == UINT || this.glsl.startsWith("uvec"); }
        private int alignment() { return this.columns > 1 || this.rows >= 3 ? 16 : this.rows * 4; }
        private int size() { return this.columns > 1 ? this.columns * 16 : this.rows * 4; }
        public static ValueType fromGlsl(String type) {
            for (ValueType value : values()) if (value.glsl.equals(type)) return value;
            throw new IllegalArgumentException("Unsupported std140 uniform type: " + type);
        }
    }

    public record Field(String name, ValueType type, int baseOffset, int arrayLength, int stride) {
        public int offset(int element) {
            int count = this.arrayLength == 0 ? 1 : this.arrayLength;
            if (element < 0 || element >= count) throw new IndexOutOfBoundsException("Uniform array index " + element + " for " + this.name);
            return this.baseOffset + element * this.stride;
        }
    }

    public record Layout(Map<String, Field> fields, int size) {
        public Layout {
            fields = Collections.unmodifiableMap(new LinkedHashMap<>(fields));
            if (size <= 0 || size % 16 != 0) throw new IllegalArgumentException("Uniform block size must be positive and aligned to 16 bytes");
            int offset = 0;
            for (var entry : fields.entrySet()) {
                Field field = entry.getValue();
                validateName(entry.getKey());
                if (!entry.getKey().equals(field.name()) || field.arrayLength() < 0) throw new IllegalArgumentException("Invalid uniform layout field");
                int alignment = field.arrayLength() > 0 ? 16 : field.type().alignment();
                int base = align(offset, alignment);
                int stride = field.arrayLength() > 0 ? align(field.type().size(), 16) : field.type().size();
                if (base != field.baseOffset() || stride != field.stride()) throw new IllegalArgumentException("Non-std140 uniform field layout: " + field.name());
                offset = Math.addExact(base, Math.multiplyExact(stride, Math.max(1, field.arrayLength())));
            }
            if (align(offset, 16) != size) throw new IllegalArgumentException("Uniform block size does not match its fields");
        }
        public String declaration(String block) {
            validateName(block);
            StringBuilder shader = new StringBuilder("layout(std140) uniform ").append(block).append(" {\n");
            this.fields.forEach((name, field) -> {
                shader.append("    ").append(field.type().glsl()).append(' ').append(name);
                if (field.arrayLength() > 0) shader.append('[').append(field.arrayLength()).append(']');
                shader.append(";\n");
            });
            return shader.append("};\n").toString();
        }
    }

    public static Builder layoutBuilder() { return new Builder(); }
    public static final class Builder {
        private final Map<String, Field> fields = new LinkedHashMap<>();
        private int offset;
        public Builder field(String name, ValueType type) { return this.add(name, type, 0); }
        public Builder array(String name, ValueType type, int count) {
            if (count < 1) throw new IllegalArgumentException("Uniform arrays need a positive length");
            return this.add(name, type, count);
        }
        private Builder add(String name, ValueType type, int count) {
            validateName(name);
            Objects.requireNonNull(type);
            if (this.fields.containsKey(name)) throw new IllegalArgumentException("Duplicate uniform field: " + name);
            int alignment = count > 0 ? 16 : type.alignment();
            int base = align(this.offset, alignment);
            int stride = count > 0 ? align(type.size(), 16) : type.size();
            this.offset = Math.addExact(base, Math.multiplyExact(stride, Math.max(count, 1)));
            this.fields.put(name, new Field(name, type, base, count, stride));
            return this;
        }
        public Layout build() { return new Layout(this.fields, align(this.offset, 16)); }
    }
    private static int align(int value, int alignment) { return Math.multiplyExact(Math.floorDiv(Math.addExact(value, alignment - 1), alignment), alignment); }
    private static void validateName(String name) {
        if (!name.matches("[A-Za-z_][A-Za-z0-9_]*") || name.startsWith("gl_")) throw new IllegalArgumentException("Invalid GLSL uniform name: " + name);
    }
}
