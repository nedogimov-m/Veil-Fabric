package foundry.veil.api.client.render.shader.uniform;

import org.joml.*;
import foundry.veil.api.client.render.gpu.GpuUniformBuffer;



/**
 * A single uniform in a shader program.
 */
public interface ShaderUniform extends ShaderUniformAccess {

    /**
     * @return The name assigned to this uniform in the shader
     */
    String getName();

    /**
     * @return The byte offset of the field in its native std140 block
     */
    int getOffset();

    /**
     * @return The data type of this uniform in the shader
     */
    Type getType();

    /**
     * @return The number of array elements in the uniform
     */
    int getArrayLength();

    int getComponentCount();

    /**
     * Retrieves the value of this uniform as a float.
     *
     * @return The float value of this uniform
     */
    float getFloat();

    /**
     * Retrieves the values of this uniform as a float array.
     *
     * @return A new array with the values of this uniform
     */
    default float[] getFloats() {
        float[] values = new float[this.getArrayLength() * this.getComponentCount()];
        this.getFloats(values, 0, values.length);
        return values;
    }

    /**
     * Retrieves the values of this uniform as a float array and stores them in the specified array.
     *
     * @param fill The array to fill
     */
    default void getFloats(float[] fill) {
        this.getFloats(fill, 0, fill.length);
    }

    /**
     * Retrieves the values of this uniform as a float array and stores them in the specified array.
     *
     * @param dst    The array to fill
     * @param offset The position to start filling the array at
     * @param length The number of values to read
     */
    void getFloats(float[] dst, int offset, int length);

    /**
     * Retrieves the value of this uniform as an integer.
     *
     * @return The int value of this uniform
     */
    int getInt();

    /**
     * Retrieves the values of this uniform as an int array.
     *
     * @return A new array with the values of this uniform
     */
    default int[] getInts() {
        int[] values = new int[this.getArrayLength() * this.getComponentCount()];
        this.getInts(values, 0, values.length);
        return values;
    }

    /**
     * Retrieves the values of this uniform as an int array and stores them in the specified array.
     *
     * @param fill The array to fill
     */
    default void getInts(int[] fill) {
        this.getInts(fill, 0, fill.length);
    }

    /**
     * Retrieves the values of this uniform as an int array and stores them in the specified array.
     *
     * @param dst    The array to fill
     * @param offset The position to start filling the array at
     * @param length The number of values to read
     */
    void getInts(int[] dst, int offset, int length);

    /**
     * Retrieves the value of this uniform as a double.
     *
     * @return The int value of this uniform
     */
    double getDouble();

    /**
     * Retrieves the values of this uniform as a double array.
     *
     * @return A new array with the values of this uniform
     */
    default double[] getDoubles() {
        double[] values = new double[this.getArrayLength() * this.getComponentCount()];
        this.getDoubles(values, 0, values.length);
        return values;
    }

    /**
     * Retrieves the values of this uniform as a double array and stores them in the specified array.
     *
     * @param fill The array to fill
     */
    default void getDoubles(double[] fill) {
        this.getDoubles(fill, 0, fill.length);
    }

    /**
     * Retrieves the values of this uniform as a double array and stores them in the specified array.
     *
     * @param dst    The array to fill
     * @param offset The position to start filling the array at
     * @param length The number of values to read
     */
    void getDoubles(double[] dst, int offset, int length);

    /**
     * Retrieves the value of this uniform as a long.
     *
     * @return The int value of this uniform
     */
    long getLong();

    /**
     * Retrieves the values of this uniform as a long array.
     *
     * @return A new array with the values of this uniform
     */
    default long[] getLongs() {
        long[] values = new long[this.getArrayLength() * this.getComponentCount()];
        this.getLongs(values, 0, values.length);
        return values;
    }

    /**
     * Retrieves the values of this uniform as a long array and stores them in the specified array.
     *
     * @param fill The array to fill
     */
    default void getLongs(long[] fill) {
        this.getLongs(fill, 0, fill.length);
    }

    /**
     * Retrieves the values of this uniform as a long array and stores them in the specified array.
     *
     * @param dst    The array to fill
     * @param offset The position to start filling the array at
     * @param length The number of values to read
     */
    void getLongs(long[] dst, int offset, int length);

    /**
     * Retrieves an array of vectors by the specified name.
     *
     * @param name   The name of the uniform to get
     * @param values The values to set
     */
    void getVector(CharSequence name, Vector2f... values);

    /**
     * Retrieves an array of vectors by the specified name.
     *
     * @param name   The name of the uniform to get
     * @param values The values to set
     */
    void getVector(CharSequence name, Vector3f... values);

    /**
     * Retrieves an array of vectors by the specified name.
     *
     * @param name   The name of the uniform to get
     * @param values The values to set
     */
    void getVector(CharSequence name, Vector4f... values);

    /**
     * Retrieves an array of vectors by the specified name.
     *
     * @param name   The name of the uniform to get
     * @param values The values to set
     */
    void getVectori(CharSequence name, Vector2i... values);

    /**
     * Retrieves an array of vectors by the specified name.
     *
     * @param name   The name of the uniform to get
     * @param values The values to set
     */
    void getVectori(CharSequence name, Vector3i... values);

    /**
     * Retrieves an array of vectors by the specified name.
     *
     * @param name   The name of the uniform to get
     * @param values The values to set
     */
    void getVectori(CharSequence name, Vector4i... values);

    /**
     * Retrieves a matrix2x2 by the specified name
     *
     * @param name  The name of the uniform to get
     * @param value The value to set
     */
    void getMatrix(CharSequence name, Matrix2f value);

    /**
     * Retrieves a matrix3x3 by the specified name
     *
     * @param name  The name of the uniform to get
     * @param value The value to set
     */
    void getMatrix(CharSequence name, Matrix3f value);

    /**
     * Retrieves a matrix4x4 by the specified name
     *
     * @param name  The name of the uniform to get
     * @param value The value to set
     */
    void getMatrix(CharSequence name, Matrix4f value);

    /**
     * Retrieves a matrix3x2 by the specified name
     *
     * @param name  The name of the uniform to get
     * @param value The value to set
     */
    void getMatrix(CharSequence name, Matrix3x2f value);

    /**
     * Retrieves a matrix4x3 by the specified name
     *
     * @param name  The name of the uniform to get
     * @param value The value to set
     */
    void getMatrix(CharSequence name, Matrix4x3f value);

    /**
     * Possible uniform types that can be used.
     */
    /** Numeric metadata; uploads use typed native blocks and explicit encoders. */
    enum Type {
        SAMPLER(0),
        FLOAT(Float.BYTES),
        FLOAT_VEC2(Float.BYTES * 2),
        FLOAT_VEC3(Float.BYTES * 3),
        FLOAT_VEC4(Float.BYTES * 4),
        INT(Integer.BYTES),
        INT_VEC2(Integer.BYTES * 2),
        INT_VEC3(Integer.BYTES * 3),
        INT_VEC4(Integer.BYTES * 4),
        UNSIGNED_INT(Integer.BYTES),
        UNSIGNED_INT_VEC2(Integer.BYTES * 2),
        UNSIGNED_INT_VEC3(Integer.BYTES * 3),
        UNSIGNED_INT_VEC4(Integer.BYTES * 4),
        MATRIX2x2(Float.BYTES * 2 * 2),
        MATRIX3x3(Float.BYTES * 3 * 3),
        MATRIX4x4(Float.BYTES * 4 * 4),
        MATRIX2x3(Float.BYTES * 2 * 3),
        MATRIX3x2(Float.BYTES * 3 * 2),
        MATRIX2x4(Float.BYTES * 2 * 4),
        MATRIX4x2(Float.BYTES * 4 * 2),
        MATRIX3x4(Float.BYTES * 3 * 4),
        MATRIX4x3(Float.BYTES * 4 * 3),
        DOUBLE(Double.BYTES),
        DOUBLE_VEC2(Double.BYTES * 2),
        DOUBLE_VEC3(Double.BYTES * 3),
        DOUBLE_VEC4(Double.BYTES * 4),
        LONG(Long.BYTES),
        LONG_VEC2(Long.BYTES * 2),
        LONG_VEC3(Long.BYTES * 3),
        LONG_VEC4(Long.BYTES * 4),
        UNSIGNED_LONG(Long.BYTES),
        UNSIGNED_LONG_VEC2(Long.BYTES * 2),
        UNSIGNED_LONG_VEC3(Long.BYTES * 3),
        UNSIGNED_LONG_VEC4(Long.BYTES * 4),
        DOUBLE_MATRIX2x2(Double.BYTES * 2 * 2),
        DOUBLE_MATRIX3x3(Double.BYTES * 3 * 3),
        DOUBLE_MATRIX4x4(Double.BYTES * 4 * 4),
        DOUBLE_MATRIX2x3(Double.BYTES * 2 * 3),
        DOUBLE_MATRIX3x2(Double.BYTES * 3 * 2),
        DOUBLE_MATRIX2x4(Double.BYTES * 2 * 4),
        DOUBLE_MATRIX4x2(Double.BYTES * 4 * 2),
        DOUBLE_MATRIX3x4(Double.BYTES * 3 * 4),
        DOUBLE_MATRIX4x3(Double.BYTES * 4 * 3);
        private final int bytes;
        Type(int bytes) { this.bytes = bytes; }
        public int getBytes() { return this.bytes; }
        public static Type fromNative(GpuUniformBuffer.ValueType type) {
            if (type.columns() > 1) return valueOf("MATRIX" + type.columns() + "x" + type.rows());
            String base = type.integer() ? type.unsigned() ? "UNSIGNED_INT" : "INT" : "FLOAT";
            return valueOf(base + (type.rows() > 1 ? "_VEC" + type.rows() : ""));
        }
    }
}
