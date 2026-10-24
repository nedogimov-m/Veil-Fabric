package foundry.veil.api.client.property.properties;

import foundry.veil.api.client.property.Property;
import foundry.veil.api.client.registry.PropertyRegistry;
import foundry.veil.api.client.render.gpu.GpuShaderProgram;
import foundry.veil.api.client.render.gpu.GpuUniformBuffer;
import foundry.veil.api.flare.modifier.PropertyModifier;
import gg.moonflower.molangcompiler.api.MolangExpression;
import gg.moonflower.molangcompiler.api.MolangRuntime;

import java.util.List;
import java.util.Optional;

public class BoolProperty extends Property<Boolean> {
    public BoolProperty(boolean value) {
        super(PropertyRegistry.BOOL.get(), value);
    }

    @Override
    public void applyValue(String name, GpuShaderProgram shader, String block) {
        GpuUniformBuffer uniform = uniform(name, shader, block, GpuUniformBuffer.ValueType.BOOL);
        if (uniform != null) {
            uniform.setInt(name, this.overrideValue ? 1 : 0);
        }
    }

    @Override
    public void modify(Boolean value, PropertyModifier.PropertyModifierMode mode, Optional<List<MolangExpression>> optionalMolang) {
        this.overrideValue = value;
        if (mode == PropertyModifier.PropertyModifierMode.MOLANG) {
            optionalMolang.ifPresent(molang -> {
                try {
                    this.overrideValue = this.getEnvironment().get().resolve(molang.getFirst()) >= 0;
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            });
        }
    }

    @Override
    protected void setQueries(MolangRuntime.Builder builder) {
        super.setQueries(builder);
        builder.setQuery("v", () -> this.overrideValue ? 1.0f : -1.0f);
    }

    @Override
    protected Boolean cloneValue(Boolean value) {
        return value;
    }
}
