package foundry.veil.impl.client.render.shader.uniform;

import foundry.veil.api.client.render.gpu.GpuUniformBuffer;
import foundry.veil.api.client.render.shader.uniform.ShaderUniform;
import org.joml.*;
import org.lwjgl.system.NativeResource;
import java.util.Objects;
import java.util.function.Supplier;
import java.lang.Math;

/** Borrowed typed std140 field view. Reads return pending CPU values; uploads remain explicit. */
public final class ShaderUniformImpl implements ShaderUniform, NativeResource {
    private final Supplier<GpuUniformBuffer> source;
    private final String name;
    private boolean closed;
    public ShaderUniformImpl(Supplier<GpuUniformBuffer> source, String name) {
        this.source = Objects.requireNonNull(source); this.name = Objects.requireNonNull(name);
    }
    private GpuUniformBuffer buffer() {
        if (this.closed) throw new IllegalStateException("Uniform field view is closed");
        GpuUniformBuffer buffer = this.source.get();
        if (buffer == null || buffer.isClosed() || !buffer.layout().fields().containsKey(this.name)) throw new IllegalStateException("Native uniform field is unavailable: " + this.name);
        return buffer;
    }
    private GpuUniformBuffer.Field field() { return this.buffer().layout().fields().get(this.name); }
    @Override public boolean isValid() {
        if (this.closed) return false;
        try {
            GpuUniformBuffer buffer = this.source.get();
            return buffer != null && !buffer.isClosed() && buffer.layout().fields().containsKey(this.name);
        } catch (IllegalStateException unavailable) { return false; }
    }
    @Override public String getName() { return this.name; }
    @Override public int getOffset() { return this.field().baseOffset(); }
    @Override public Type getType() { return Type.fromNative(this.field().type()); }
    @Override public int getArrayLength() { return Math.max(1, this.field().arrayLength()); }
    @Override public int getComponentCount() { var type = this.field().type(); return type.rows() * type.columns(); }
    @Override public float getFloat() { return this.buffer().getFloats(this.name, 0)[0]; }
    @Override public int getInt() { return this.buffer().getInts(this.name, 0)[0]; }
    @Override public double getDouble() { return this.field().type().integer() ? this.getLong() : this.getFloat(); }
    @Override public long getLong() {
        if (!this.field().type().integer()) return (long) this.getFloat();
        int value = this.getInt(); return this.field().type().unsigned() ? Integer.toUnsignedLong(value) : value;
    }
    private void checkRead(int offset, int length, int destinationLength) {
        Objects.checkFromIndexSize(offset, length, destinationLength);
        if (length > this.getArrayLength() * this.getComponentCount()) throw new IllegalArgumentException("Read exceeds native uniform field");
    }
    @Override public void getFloats(float[] dst, int offset, int length) {
        this.checkRead(offset, length, dst.length); int written = 0;
        for (int element = 0; written < length; element++) for (float value : this.buffer().getFloats(this.name, element)) {
            dst[offset + written++] = value; if (written == length) break;
        }
    }
    @Override public void getInts(int[] dst, int offset, int length) {
        this.checkRead(offset, length, dst.length); int written = 0;
        for (int element = 0; written < length; element++) for (int value : this.buffer().getInts(this.name, element)) {
            dst[offset + written++] = value; if (written == length) break;
        }
    }
    @Override public void getDoubles(double[] dst, int offset, int length) {
        this.checkRead(offset, length, dst.length); int written = 0;
        for (int element = 0; written < length; element++) {
            if (this.field().type().integer()) for (int value : this.buffer().getInts(this.name, element)) {
                dst[offset + written++] = this.field().type().unsigned() ? Integer.toUnsignedLong(value) : value;
                if (written == length) break;
            } else for (float value : this.buffer().getFloats(this.name, element)) {
                dst[offset + written++] = value; if (written == length) break;
            }
        }
    }
    @Override public void getLongs(long[] dst, int offset, int length) {
        this.checkRead(offset, length, dst.length); double[] values = new double[length]; this.getDoubles(values, 0, length);
        for (int index = 0; index < length; index++) dst[offset + index] = (long) values[index];
    }
    private void requireVector(int components, int count) {
        var type = this.field().type();
        if (type.columns() != 1 || type.rows() != components || count > this.getArrayLength()) throw new IllegalArgumentException("Native uniform vector dimensions/count do not match " + this.name);
    }
    private static float floating(double value) {
        float result = (float) value;
        if (Double.isFinite(value) && !Float.isFinite(result)) throw new IllegalArgumentException("Value exceeds native float32 range");
        return result;
    }
    private int integer(long value) {
        if (this.field().type().unsigned()) {
            if (value < 0 || value > 0xFFFFFFFFL) throw new IllegalArgumentException("Value exceeds native uint32 range");
            return (int) value;
        }
        return Math.toIntExact(value);
    }
    private void writeFloats(float[] values) {
        var type = this.field().type();
        if (type.columns() != 1 || type.integer() || values.length % type.rows() != 0 || values.length / type.rows() > this.getArrayLength()) throw new IllegalArgumentException("Native float array dimensions/count do not match " + this.name);
        for (int element = 0; element < values.length / type.rows(); element++) this.buffer().setFloats(this.name, element, java.util.Arrays.copyOfRange(values, element * type.rows(), (element + 1) * type.rows()));
    }
    private void writeInts(int[] values) {
        var type = this.field().type();
        if (type.columns() != 1 || !type.integer() || values.length % type.rows() != 0 || values.length / type.rows() > this.getArrayLength()) throw new IllegalArgumentException("Native integer array dimensions/count do not match " + this.name);
        for (int element = 0; element < values.length / type.rows(); element++) this.buffer().setInts(this.name, element, java.util.Arrays.copyOfRange(values, element * type.rows(), (element + 1) * type.rows()));
    }
    private void matrix(int columns, int rows, float[] values, boolean transpose) {
        if (transpose) {
            float[] reordered = new float[values.length];
            for (int column = 0; column < columns; column++) for (int row = 0; row < rows; row++) reordered[column * rows + row] = values[row * columns + column];
            values = reordered;
        }
        this.buffer().setMatrixValues(this.name, 0, columns, rows, values);
    }
    private void checkName(CharSequence name) {
        if (!this.name.contentEquals(name)) throw new IllegalArgumentException("This view refers to " + this.name);
    }
    @Override public void free() { this.closed = true; }
    @Override public void getVector(CharSequence name, Vector2f... values) {
        this.checkName(name); this.requireVector(2, values.length);
        for (int index = 0; index < values.length; index++) { float[] v = this.buffer().getFloats(this.name, index); values[index].set(v[0], v[1]); }
    }
    @Override public void getVectori(CharSequence name, Vector2i... values) {
        this.checkName(name); this.requireVector(2, values.length);
        for (int index = 0; index < values.length; index++) { int[] v = this.buffer().getInts(this.name, index); values[index].set(v[0], v[1]); }
    }
    @Override public void getVector(CharSequence name, Vector3f... values) {
        this.checkName(name); this.requireVector(3, values.length);
        for (int index = 0; index < values.length; index++) { float[] v = this.buffer().getFloats(this.name, index); values[index].set(v[0], v[1], v[2]); }
    }
    @Override public void getVectori(CharSequence name, Vector3i... values) {
        this.checkName(name); this.requireVector(3, values.length);
        for (int index = 0; index < values.length; index++) { int[] v = this.buffer().getInts(this.name, index); values[index].set(v[0], v[1], v[2]); }
    }
    @Override public void getVector(CharSequence name, Vector4f... values) {
        this.checkName(name); this.requireVector(4, values.length);
        for (int index = 0; index < values.length; index++) { float[] v = this.buffer().getFloats(this.name, index); values[index].set(v[0], v[1], v[2], v[3]); }
    }
    @Override public void getVectori(CharSequence name, Vector4i... values) {
        this.checkName(name); this.requireVector(4, values.length);
        for (int index = 0; index < values.length; index++) { int[] v = this.buffer().getInts(this.name, index); values[index].set(v[0], v[1], v[2], v[3]); }
    }
    @Override public void getMatrix(CharSequence name, Matrix2f value) {
        this.checkName(name);
        if (this.field().type().columns() != 2 || this.field().type().rows() != 2) throw new IllegalArgumentException("Native matrix dimensions do not match");
        value.set(this.buffer().getFloats(this.name, 0));
    }
    @Override public void getMatrix(CharSequence name, Matrix3f value) {
        this.checkName(name);
        if (this.field().type().columns() != 3 || this.field().type().rows() != 3) throw new IllegalArgumentException("Native matrix dimensions do not match");
        value.set(this.buffer().getFloats(this.name, 0));
    }
    @Override public void getMatrix(CharSequence name, Matrix4f value) {
        this.checkName(name);
        if (this.field().type().columns() != 4 || this.field().type().rows() != 4) throw new IllegalArgumentException("Native matrix dimensions do not match");
        value.set(this.buffer().getFloats(this.name, 0));
    }
    @Override public void getMatrix(CharSequence name, Matrix3x2f value) {
        this.checkName(name);
        if (this.field().type().columns() != 3 || this.field().type().rows() != 2) throw new IllegalArgumentException("Native matrix dimensions do not match");
        value.set(this.buffer().getFloats(this.name, 0));
    }
    @Override public void getMatrix(CharSequence name, Matrix4x3f value) {
        this.checkName(name);
        if (this.field().type().columns() != 4 || this.field().type().rows() != 3) throw new IllegalArgumentException("Native matrix dimensions do not match");
        value.set(this.buffer().getFloats(this.name, 0));
    }
    @Override public void setFloat(float value) { this.buffer().setFloat(this.name, value); }
    @Override public void setInt(int value) { this.buffer().setInt(this.name, value); }
    @Override public void setDouble(double value) { this.setFloat(floating(value)); }
    @Override public void setLong(long value) { this.setInt(this.integer(value)); }
    @Override public void setVector(float x, float y) { this.buffer().setVector(this.name, x, y); }
    @Override public void setVectorI(int x, int y) { this.buffer().setVectorI(this.name, x, y); }
    @Override public void setVector64(double x, double y) { this.buffer().setVector(this.name, floating(x), floating(y)); }
    @Override public void setVectorI64(long x, long y) { this.buffer().setVectorI(this.name, this.integer(x), this.integer(y)); }
    @Override public void setVectors(Vector2fc... values) {
        this.requireVector(2, values.length);
        for (int index = 0; index < values.length; index++) this.buffer().setFloats(this.name, index, values[index].x(), values[index].y());
    }
    @Override public void setIVectors(Vector2ic... values) {
        this.requireVector(2, values.length);
        for (int index = 0; index < values.length; index++) this.buffer().setInts(this.name, index, values[index].x(), values[index].y());
    }
    @Override public void set64Vectors(Vector2dc... values) {
        this.requireVector(2, values.length);
        for (int index = 0; index < values.length; index++) this.buffer().setFloats(this.name, index, floating(values[index].x()), floating(values[index].y()));
    }
    @Override public void setVector(float x, float y, float z) { this.buffer().setVector(this.name, x, y, z); }
    @Override public void setVectorI(int x, int y, int z) { this.buffer().setVectorI(this.name, x, y, z); }
    @Override public void setVector64(double x, double y, double z) { this.buffer().setVector(this.name, floating(x), floating(y), floating(z)); }
    @Override public void setVectorI64(long x, long y, long z) { this.buffer().setVectorI(this.name, this.integer(x), this.integer(y), this.integer(z)); }
    @Override public void setVectors(Vector3fc... values) {
        this.requireVector(3, values.length);
        for (int index = 0; index < values.length; index++) this.buffer().setFloats(this.name, index, values[index].x(), values[index].y(), values[index].z());
    }
    @Override public void setIVectors(Vector3ic... values) {
        this.requireVector(3, values.length);
        for (int index = 0; index < values.length; index++) this.buffer().setInts(this.name, index, values[index].x(), values[index].y(), values[index].z());
    }
    @Override public void set64Vectors(Vector3dc... values) {
        this.requireVector(3, values.length);
        for (int index = 0; index < values.length; index++) this.buffer().setFloats(this.name, index, floating(values[index].x()), floating(values[index].y()), floating(values[index].z()));
    }
    @Override public void setVector(float x, float y, float z, float w) { this.buffer().setVector(this.name, x, y, z, w); }
    @Override public void setVectorI(int x, int y, int z, int w) { this.buffer().setVectorI(this.name, x, y, z, w); }
    @Override public void setVector64(double x, double y, double z, double w) { this.buffer().setVector(this.name, floating(x), floating(y), floating(z), floating(w)); }
    @Override public void setVectorI64(long x, long y, long z, long w) { this.buffer().setVectorI(this.name, this.integer(x), this.integer(y), this.integer(z), this.integer(w)); }
    @Override public void setVectors(Vector4fc... values) {
        this.requireVector(4, values.length);
        for (int index = 0; index < values.length; index++) this.buffer().setFloats(this.name, index, values[index].x(), values[index].y(), values[index].z(), values[index].w());
    }
    @Override public void setIVectors(Vector4ic... values) {
        this.requireVector(4, values.length);
        for (int index = 0; index < values.length; index++) this.buffer().setInts(this.name, index, values[index].x(), values[index].y(), values[index].z(), values[index].w());
    }
    @Override public void set64Vectors(Vector4dc... values) {
        this.requireVector(4, values.length);
        for (int index = 0; index < values.length; index++) this.buffer().setFloats(this.name, index, floating(values[index].x()), floating(values[index].y()), floating(values[index].z()), floating(values[index].w()));
    }
    @Override public void setFloats(float... values) { this.writeFloats(values); }
    @Override public void setInts(int... values) { this.writeInts(values); }
    @Override public void setDoubles(double... values) { float[] converted = new float[values.length]; for (int i = 0; i < values.length; i++) converted[i] = floating(values[i]); this.writeFloats(converted); }
    @Override public void setLongs(long... values) { int[] converted = new int[values.length]; for (int i = 0; i < values.length; i++) converted[i] = this.integer(values[i]); this.writeInts(converted); }
    @Override public void setHandle(long value) { throw new UnsupportedOperationException("Bind a native texture view and sampler on GpuShaderProgram"); }
    @Override public void setHandles(long... values) { throw new UnsupportedOperationException("Bind native texture views and samplers on GpuShaderProgram"); }
    @Override public void setMatrix(Matrix2fc value, boolean transpose) {
        float[] values = new float[4]; value.get(values);
        this.matrix(2, 2, values, transpose);
    }
    @Override public void setMatrix(Matrix2dc value, boolean transpose) {
        double[] raw = new double[4]; value.get(raw); float[] values = new float[raw.length];
        for (int i = 0; i < raw.length; i++) values[i] = floating(raw[i]);
        this.matrix(2, 2, values, transpose);
    }
    @Override public void setMatrix(Matrix3fc value, boolean transpose) {
        float[] values = new float[9]; value.get(values);
        this.matrix(3, 3, values, transpose);
    }
    @Override public void setMatrix(Matrix3dc value, boolean transpose) {
        double[] raw = new double[9]; value.get(raw); float[] values = new float[raw.length];
        for (int i = 0; i < raw.length; i++) values[i] = floating(raw[i]);
        this.matrix(3, 3, values, transpose);
    }
    @Override public void setMatrix(Matrix4fc value, boolean transpose) {
        float[] values = new float[16]; value.get(values);
        this.matrix(4, 4, values, transpose);
    }
    @Override public void setMatrix(Matrix4dc value, boolean transpose) {
        double[] raw = new double[16]; value.get(raw); float[] values = new float[raw.length];
        for (int i = 0; i < raw.length; i++) values[i] = floating(raw[i]);
        this.matrix(4, 4, values, transpose);
    }
    @Override public void setMatrix2x3(Matrix3x2fc value, boolean transpose) {
        float[] values = new float[6]; value.get(values);
        this.matrix(2, 3, values, transpose);
    }
    @Override public void setMatrix2x3(Matrix3x2dc value, boolean transpose) {
        double[] raw = new double[6]; value.get(raw); float[] values = new float[raw.length];
        for (int i = 0; i < raw.length; i++) values[i] = floating(raw[i]);
        this.matrix(2, 3, values, transpose);
    }
    @Override public void setMatrix3x2(Matrix3x2fc value, boolean transpose) {
        float[] values = new float[6]; value.get(values);
        this.matrix(3, 2, values, transpose);
    }
    @Override public void setMatrix3x2(Matrix3x2dc value, boolean transpose) {
        double[] raw = new double[6]; value.get(raw); float[] values = new float[raw.length];
        for (int i = 0; i < raw.length; i++) values[i] = floating(raw[i]);
        this.matrix(3, 2, values, transpose);
    }
    @Override public void setMatrix3x4(Matrix4x3fc value, boolean transpose) {
        float[] values = new float[12]; value.get(values);
        this.matrix(3, 4, values, transpose);
    }
    @Override public void setMatrix3x4(Matrix4x3dc value, boolean transpose) {
        double[] raw = new double[12]; value.get(raw); float[] values = new float[raw.length];
        for (int i = 0; i < raw.length; i++) values[i] = floating(raw[i]);
        this.matrix(3, 4, values, transpose);
    }
    @Override public void setMatrix4x3(Matrix4x3fc value, boolean transpose) {
        float[] values = new float[12]; value.get(values);
        this.matrix(4, 3, values, transpose);
    }
    @Override public void setMatrix4x3(Matrix4x3dc value, boolean transpose) {
        double[] raw = new double[12]; value.get(raw); float[] values = new float[raw.length];
        for (int i = 0; i < raw.length; i++) values[i] = floating(raw[i]);
        this.matrix(4, 3, values, transpose);
    }
}
