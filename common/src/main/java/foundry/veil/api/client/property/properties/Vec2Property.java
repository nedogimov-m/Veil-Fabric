package foundry.veil.api.client.property.properties;

import foundry.veil.api.client.property.Property;
import foundry.veil.api.client.registry.PropertyRegistry;
import foundry.veil.api.client.render.gpu.GpuShaderProgram;
import foundry.veil.api.client.render.gpu.GpuUniformBuffer;
import foundry.veil.api.flare.modifier.PropertyModifier;
import gg.moonflower.molangcompiler.api.MolangEnvironment;
import gg.moonflower.molangcompiler.api.MolangExpression;
import gg.moonflower.molangcompiler.api.MolangRuntime;
import org.joml.Vector2f;
import org.joml.Vector2fc;

import java.util.List;
import java.util.Optional;

public class Vec2Property extends Property<Vector2f> {
    public Vec2Property(Vector2fc value) {
        super(PropertyRegistry.VEC2.get(), new Vector2f(value));
    }

    @Override
    public void applyValue(String name, GpuShaderProgram shader, String block) {
        GpuUniformBuffer uniform = uniform(name, shader, block, GpuUniformBuffer.ValueType.VEC2);
        if (uniform != null) {
            uniform.setVector(name, this.overrideValue.x, this.overrideValue.y);
        }
    }

    @Override
    protected void setQueries(MolangRuntime.Builder builder) {
        super.setQueries(builder);
        builder.setQuery("x", this.overrideValue::x);
        builder.setQuery("y", this.overrideValue::y);
    }

    @Override
    public void modify(Vector2f value, PropertyModifier.PropertyModifierMode mode, Optional<List<MolangExpression>> optionalMolang) {
        switch (mode) {
            case ADD -> this.overrideValue.add(value);
            case SUBTRACT -> this.overrideValue.sub(value);
            case MULTIPLY -> this.overrideValue.mul(value);
            case REPLACE -> this.overrideValue.set(value);
            case MOLANG -> {
                this.overrideValue.set(value);
                optionalMolang.ifPresent(molang -> {
                    MolangEnvironment environment = this.getEnvironment().get();
                    if (!molang.isEmpty()) {
                        this.overrideValue.x = environment.safeResolve(molang.getFirst());
                    }
                    if (molang.size() > 1) {
                        this.overrideValue.y = environment.safeResolve(molang.get(1));
                    }
                });
            }
        }
    }

    @Override
    protected Vector2f cloneValue(Vector2f value) {
        return new Vector2f(value);
    }
}
