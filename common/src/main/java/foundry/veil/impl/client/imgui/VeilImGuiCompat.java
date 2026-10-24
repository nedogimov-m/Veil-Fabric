package foundry.veil.impl.client.imgui;

import com.mojang.blaze3d.platform.InputConstants;
import foundry.imgui.api.ImGuiMCEvents;
import foundry.veil.Veil;
import foundry.veil.api.client.render.VeilRenderSystem;
import foundry.veil.impl.client.editor.*;
import foundry.veil.platform.VeilEventPlatform;
import net.minecraft.client.KeyMapping;
import org.jetbrains.annotations.ApiStatus;

@ApiStatus.Internal
public final class VeilImGuiCompat {

    public static final KeyMapping EDITOR_KEY = new KeyMapping("key.veil.editor", InputConstants.KEY_F6, KeyMapping.Category.register(net.minecraft.resources.Identifier.parse("veil:editor")));

    private VeilImGuiCompat() {
    }

    public static void load() {
        ImGuiMCEvents.INSTANCE.preRenderImGuiEvent(() -> {
            VeilImGuiStylesheet.initStyles();
            NativeImGuiAreas.begin();
            VeilRenderSystem.renderer().getEditorManager().render();
        });
        ImGuiMCEvents.INSTANCE.postRenderImGuiEvent(() -> {
            VeilImGuiStylesheet.initStyles();
            VeilRenderSystem.renderer().getEditorManager().renderLast();
            NativeImGuiAreas.end();
        });
        VeilEventPlatform.INSTANCE.onVeilRegisterInspectors(registry -> {
            // Example for devs
            if (Veil.platform().isDevelopmentEnvironment()) {
                registry.registerInspector(new DemoInspector());
            }

            // Debug editors
            registry.registerInspector(new DeviceInfoViewer());
            registry.registerInspector(new PipelineStatisticsViewer());
            registry.registerInspector(new PostInspector());
            registry.registerInspector(new ShaderInspector());
            registry.registerInspector(new TextureInspector());
            registry.registerInspector(new LightInspector());
            registry.registerInspector(new FramebufferInspector());
            registry.registerInspector(new ResourceManagerInspector());
            registry.registerInspector(new ParticleEditorInspector());
        });
    }
}
