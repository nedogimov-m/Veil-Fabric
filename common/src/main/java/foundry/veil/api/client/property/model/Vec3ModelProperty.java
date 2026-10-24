package foundry.veil.api.client.property.model;

import foundry.veil.api.client.property.InapplicableProperty;
import foundry.veil.api.client.property.ModelProperty;
import foundry.veil.api.client.property.properties.Vec3Property;
import foundry.veil.api.client.render.gpu.GpuShaderProgram;
import org.joml.Vector3f;
import org.joml.Vector3fc;

@ModelProperty
@InapplicableProperty
public class Vec3ModelProperty extends Vec3Property {

    public Vec3ModelProperty(Vector3f value) {
        super(value);
    }

    public Vector3fc getValue() {
        return this.overrideValue;
    }

    @Override
    public void applyValue(String name, GpuShaderProgram shader, String block) {
        throw new UnsupportedOperationException();
    }
}
