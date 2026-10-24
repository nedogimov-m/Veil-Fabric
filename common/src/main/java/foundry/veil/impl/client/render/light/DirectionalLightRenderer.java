package foundry.veil.impl.client.render.light;


import com.mojang.blaze3d.systems.RenderSystem;
import foundry.veil.Veil;
import foundry.veil.api.client.color.Colorc;
import foundry.veil.api.client.render.CullFrustum;
import foundry.veil.api.client.render.light.data.DirectionalLightData;
import foundry.veil.api.client.render.light.renderer.LightRenderHandle;
import foundry.veil.api.client.render.light.renderer.LightRenderer;
import foundry.veil.api.client.render.light.renderer.LightTypeRenderer;

import net.minecraft.resources.Identifier;
import org.jetbrains.annotations.ApiStatus;
import org.joml.Vector3f;

import java.util.Collection;
import java.util.LinkedList;
import java.util.List;

@ApiStatus.Internal
public class DirectionalLightRenderer implements LightTypeRenderer<DirectionalLightData> {

    private static final Vector3f DIRECTION = new Vector3f();
    private static final Identifier RENDER_TYPE = Veil.veilPath("light/directional");

    private final List<LightHandle> lights;

    private boolean freed;

    public DirectionalLightRenderer() {
        this.lights = new LinkedList<>();

    }

    @Override
    public LightRenderHandle<DirectionalLightData> addLight(DirectionalLightData light) {
        LightHandle handle = new LightHandle(light);
        this.lights.add(handle);
        return handle;
    }

    @Override
    public LightRenderHandle<DirectionalLightData> steal(LightRenderHandle<DirectionalLightData> handle) {
        if (!this.lights.contains(handle) || !handle.isValid()) {
            handle.free();
            return this.addLight(handle.getLightData());
        }
        return handle;
    }

    @Override
    public void prepareLights(LightRenderer lightRenderer, CullFrustum frustum) {
    }

    @Override
    public void renderLights(LightRenderer owner) {
        for (var handle : this.lights) {
            var light = handle.getLightData();
            var encoder = RenderSystem.getDevice().createCommandEncoder();
            owner.draw(encoder, RENDER_TYPE, null, 1, false, program -> {
                var color = light.getColor(); float brightness = light.getBrightness();
                program.getUniformSafe("LightColor").setVector(color.red()*brightness, color.green()*brightness, color.blue()*brightness);
                program.getUniformSafe("LightDirection").setVector(light.getDirection().normalize(DIRECTION));
            });
        }
    }

    @Override
    public Collection<? extends LightRenderHandle<DirectionalLightData>> getLights() {
        return this.lights;
    }

    @Override
    public int getVisibleLights() {
        return this.lights.size();
    }

    @Override
    public void free() {
        this.freed = true;
        this.lights.clear();
    }

    private class LightHandle implements LightRenderHandle<DirectionalLightData> {

        private final DirectionalLightData data;

        private LightHandle(DirectionalLightData data) {
            this.data = data;
        }

        @Override
        public DirectionalLightData getLightData() {
            return this.data;
        }

        @Override
        public void markDirty() {
        }

        @Override
        public boolean isValid() {
            return !DirectionalLightRenderer.this.freed && lights.contains(this);
        }

        @Override
        public void free() {
            DirectionalLightRenderer.this.lights.remove(this);
        }
    }
}
