package foundry.veil.api.client.property.properties;

import com.mojang.serialization.MapCodec;
import foundry.veil.api.client.property.Property;
import foundry.veil.api.client.registry.PropertyRegistry;
import foundry.veil.api.client.render.gpu.GpuShaderProgram;
import foundry.veil.api.flare.modifier.PropertyModifier;
import gg.moonflower.molangcompiler.api.MolangExpression;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.AbstractTexture;
import net.minecraft.resources.Identifier;

import java.util.List;
import java.util.Optional;

public class Sampler2DProperty extends Property<AbstractTexture> {

    private final Identifier source;

    public static final MapCodec<Sampler2DProperty> CODEC = Identifier.CODEC.fieldOf("value").xmap(Sampler2DProperty::new, property -> property.source);

    public Sampler2DProperty(Identifier value) {
        super(PropertyRegistry.SAMPLER2D.get(), null);
        this.source = value;
    }

    @Override
    public void applyValue(String name, GpuShaderProgram shader, String block) {
        if (!shader.hasUniform(name)) {
            return;
        }
        AbstractTexture texture = this.overrideValue != null ? this.overrideValue
                : Minecraft.getInstance().getTextureManager().getTexture(this.source);
        shader.setTexture(name, texture.getTextureView(), texture.getSampler());
    }

    @Override
    public void modify(AbstractTexture value, PropertyModifier.PropertyModifierMode mode, Optional<List<MolangExpression>> optionalMolang) {
        this.overrideValue = value;
    }

    @Override
    protected AbstractTexture cloneValue(AbstractTexture value) {
        return value;
    }

}
