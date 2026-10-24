package foundry.veil.api.client.property.properties;

import foundry.veil.api.client.property.InvertibleProperty;
import foundry.veil.api.client.registry.PropertyRegistry;
import foundry.veil.api.client.render.gpu.GpuShaderProgram;
import foundry.veil.api.client.render.gpu.GpuUniformBuffer;
import foundry.veil.api.flare.modifier.PropertyModifier;
import gg.moonflower.molangcompiler.api.MolangExpression;
import gg.moonflower.molangcompiler.api.MolangRuntime;
import org.jetbrains.annotations.Contract;
import org.joml.Matrix3f;
import org.joml.Matrix3fc;

import java.util.List;
import java.util.Optional;

public class Mat3Property extends InvertibleProperty<Matrix3f> {

    public Mat3Property(Matrix3fc value) {
        super(PropertyRegistry.MAT3.get(), new Matrix3f(value));
    }

    @Override
    public void applyValue(String name, GpuShaderProgram shader, String block) {
        GpuUniformBuffer uniform = uniform(name, shader, block, GpuUniformBuffer.ValueType.MAT3);
        if (uniform != null) {
            uniform.setMatrix(name, this.overrideValue);
        }
    }

    @Override
    protected void setQueries(MolangRuntime.Builder builder) {
        super.setQueries(builder);
    }

    @Override
    public void modifyPreInvert(Matrix3f value, PropertyModifier.PropertyModifierMode mode, Optional<List<MolangExpression>> optionalMolang) {
        switch (mode) {
            case ADD -> this.overrideValue.add(value);
            case SUBTRACT -> this.overrideValue.sub(value);
            case MULTIPLY -> this.overrideValue.mul(value);
            case REPLACE, MOLANG -> this.overrideValue.set(value);
        }
    }

    @Override
    protected Matrix3f cloneValue(Matrix3f value) {
        return new Matrix3f(value);
    }
    
    @SuppressWarnings("UnstableApiUsage")
    @Contract(mutates = "param")
    @Override
    protected Matrix3f invertAndMutate(Matrix3f value) {
        return value.invert();
    }
}
