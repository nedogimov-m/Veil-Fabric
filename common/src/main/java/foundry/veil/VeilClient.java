package foundry.veil;

import foundry.veil.api.client.registry.*;
import foundry.veil.api.client.render.VeilRenderSystem;
import foundry.veil.api.quasar.data.ParticleModuleTypeRegistry;
import foundry.veil.api.quasar.data.QuasarParticles;
import foundry.veil.api.quasar.registry.EmitterShapeRegistry;
import foundry.veil.api.quasar.registry.RenderStyleRegistry;
import foundry.veil.impl.client.imgui.VeilImGuiCompat;
import foundry.veil.impl.flare.FlareManager;
import foundry.veil.impl.quasar.QuasarParticleHandler;
import foundry.veil.impl.resource.VeilResourceManagerImpl;
import foundry.veil.platform.VeilClientPlatform;
import foundry.veil.platform.VeilEventPlatform;
import org.jetbrains.annotations.ApiStatus;

import java.util.ServiceLoader;

@ApiStatus.Internal
public class VeilClient {

    private static final VeilClientPlatform PLATFORM = ServiceLoader.load(VeilClientPlatform.class).findFirst().orElseThrow(() -> new RuntimeException("Veil expected client platform implementation"));
    private static final VeilResourceManagerImpl RESOURCE_MANAGER = new VeilResourceManagerImpl();

    public static void init() {
        VeilRenderSystem.bootstrap();
        QuasarParticleHandler.init();
        QuasarParticles.bootstrap();
        FlareManager.bootstrap();

        VeilEventPlatform.INSTANCE.onFreeNativeResources(() -> {
            VeilRenderSystem.close();
            RESOURCE_MANAGER.free();
        });
        VeilEventPlatform.INSTANCE.onVeilRendererAvailable(renderer -> {
            RESOURCE_MANAGER.addVeilLoaders(renderer);
            RenderStyleRegistry.initRenderStyles();
//            glEnable(GL_DEPTH_CLAMP); // TODO add config option
        });

        if (Veil.IMGUIMC) {
            VeilImGuiCompat.load();
        }

        PostPipelineStageRegistry.bootstrap();
        LightTypeRegistry.bootstrap();
        RenderTypeLayerRegistry.bootstrap();
        VeilShaderBufferRegistry.bootstrap();
        VeilResourceEditorRegistry.bootstrap();
        EmitterShapeRegistry.bootstrap();
        RenderStyleRegistry.bootstrap();
        ParticleModuleTypeRegistry.bootstrap();
        PropertyRegistry.bootstrap();
        PropertyModifierRegistry.bootstrap();
    }

    public static VeilClientPlatform clientPlatform() {
        return PLATFORM;
    }

    public static VeilResourceManagerImpl resourceManager() {
        return RESOURCE_MANAGER;
    }
}
