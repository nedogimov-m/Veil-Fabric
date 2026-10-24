package foundry.veil.api.client.render.post.uniform;

import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.MapCodec;
import foundry.veil.api.client.render.gpu.GpuUniformBuffer;

import java.util.Arrays;
import java.util.Locale;
import java.util.stream.Collectors;

public sealed interface UniformValue permits FloatUniform, IntUniform, Matrix2Uniform, Matrix3Uniform, Matrix4Uniform {

    Codec<UniformValue> CODEC = Type.CODEC.dispatch(UniformValue::type, Type::getCodec);

    void apply(GpuUniformBuffer uniform, String name);

    default GpuUniformBuffer.ValueType valueType() {
        return switch (this) {
            case FloatUniform value -> switch (value.values().length) {
                case 1 -> GpuUniformBuffer.ValueType.FLOAT;
                case 2 -> GpuUniformBuffer.ValueType.VEC2;
                case 3 -> GpuUniformBuffer.ValueType.VEC3;
                case 4 -> GpuUniformBuffer.ValueType.VEC4;
                default -> throw new IllegalArgumentException("Float uniforms require one to four components");
            };
            case IntUniform value -> switch (value.values().length) {
                case 1 -> GpuUniformBuffer.ValueType.INT;
                case 2 -> GpuUniformBuffer.ValueType.IVEC2;
                case 3 -> GpuUniformBuffer.ValueType.IVEC3;
                case 4 -> GpuUniformBuffer.ValueType.IVEC4;
                default -> throw new IllegalArgumentException("Integer uniforms require one to four components");
            };
            case Matrix2Uniform ignored -> GpuUniformBuffer.ValueType.MAT2;
            case Matrix3Uniform ignored -> GpuUniformBuffer.ValueType.MAT3;
            case Matrix4Uniform ignored -> GpuUniformBuffer.ValueType.MAT4;
        };
    }

    Type type();

    enum Type {
        FLOAT(FloatUniform.CODEC),
        INT(IntUniform.CODEC),
        MAT2(Matrix2Uniform.CODEC),
        MAT3(Matrix3Uniform.CODEC),
        MAT4(Matrix4Uniform.CODEC);

        private static final Type[] VALUES = values();
        private static final String VALID_OPTIONS = Arrays.stream(VALUES).map(type -> type.name().toLowerCase(Locale.ROOT)).collect(Collectors.joining(", "));
        public static final Codec<Type> CODEC = Codec.STRING.flatXmap(name -> {
            for (Type type : VALUES) {
                if (type.name().equalsIgnoreCase(name)) {
                    return DataResult.success(type);
                }
            }
            return DataResult.error(() -> "Unknown uniform type: " + name + ". Valid types: " + VALID_OPTIONS);
        }, type -> DataResult.success(type.name().toLowerCase(Locale.ROOT)));

        private final MapCodec<? extends UniformValue> codec;

        Type(MapCodec<? extends UniformValue> codec) {
            this.codec = codec;
        }

        public MapCodec<? extends UniformValue> getCodec() {
            return this.codec;
        }
    }
}
