package foundry.veil.api.client.property;

import com.google.common.base.Suppliers;
import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import foundry.veil.api.client.registry.PropertyRegistry;
import foundry.veil.api.client.render.gpu.GpuShaderProgram;
import foundry.veil.api.client.render.gpu.GpuUniformBuffer;
import foundry.veil.api.flare.modifier.PropertyModifier;
import gg.moonflower.molangcompiler.api.MolangEnvironment;
import gg.moonflower.molangcompiler.api.MolangExpression;
import gg.moonflower.molangcompiler.api.MolangRuntime;

import java.util.List;
import java.util.Optional;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * <p>Properties are variables (and usually also shader uniforms) that can be created from a data driven context. Used by Flare effects to manipulate models and shader uniforms</p>
 *
 * @param <T>
 * @author GuyApooye
 */
public abstract class Property<T> {

    protected T overrideValue;
    protected final T value;
    private final PropertyRegistry.PropertyType<T, ? extends Property<T>> type;
    private final Supplier<MolangEnvironment> environment;

    public Property(PropertyRegistry.PropertyType<T, ? extends Property<T>> type, T value) {
        this.type = type;
        this.value = value;
        this.overrideValue = this.cloneValue(value);
        this.environment = Suppliers.memoize(() -> {
            MolangRuntime.Builder builder = MolangRuntime.runtime();
            this.setQueries(builder);
            return builder.create();
        });
    }

    protected void setQueries(MolangRuntime.Builder builder) {
    }

    /**
     * Writes the current value into a caller-created native uniform block, or
     * binds a texture on the program for sampler properties. Apply properties
     * before {@link GpuShaderProgram#prepare} and before opening the render pass.
     * The block layout and the shader's std140 declaration must agree.
     *
     * @param name the uniform field or sampler binding name
     * @param shader the native program that owns the bindings
     * @param block the uniform block binding name; unused by sampler properties
     */
    public abstract void applyValue(String name, GpuShaderProgram shader, String block);

    /** Finds a writable field while retaining the optional-uniform behavior of properties. */
    protected static GpuUniformBuffer uniform(String name, GpuShaderProgram shader, String block,
                                            GpuUniformBuffer.ValueType type) {
        if (!shader.hasUniform(block)) {
            return null;
        }
        GpuUniformBuffer buffer = shader.getUniformBlock(block);
        if (buffer == null) {
            throw new IllegalStateException("Create an owned uniform block before applying properties: " + block);
        }
        GpuUniformBuffer.Field field = buffer.layout().fields().get(name);
        if (field == null) {
            return null;
        }
        if (field.type() != type || field.arrayLength() != 0) {
            throw new IllegalArgumentException("Property " + name + " requires a " + type.glsl()
                    + " field in uniform block " + block);
        }
        return buffer;
    }

    public abstract void modify(T value, PropertyModifier.PropertyModifierMode mode, Optional<List<MolangExpression>> optionalMolang);

    protected abstract T cloneValue(T value);

    public void resetOverrideValue() {
        this.modify(this.value, PropertyModifier.PropertyModifierMode.REPLACE, Optional.empty());
    }

    public PropertyRegistry.PropertyType<T, ? extends Property<T>> getType() {
        return this.type;
    }

    public static <T, M extends Property<T>> MapCodec<M> codec(PropertyRegistry.PropertyType<T, M> type) {
        return type.codec();
    }

    public static <T, M extends Property<T>> MapCodec<M> createCodec(Function<T, M> factory, Codec<T> typeCodec) {
        return typeCodec.fieldOf("value").xmap(factory, property -> property.value);
    }

    public Supplier<MolangEnvironment> getEnvironment() {
        return this.environment;
    }
}
