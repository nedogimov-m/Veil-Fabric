package foundry.veil.mixin.shader.client;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import foundry.veil.impl.client.render.shader.injection.NativeShaderInjectionFrames;
import net.minecraft.client.renderer.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;

/** Frame preparation precedes native world and GUI passes; queued draws retain resources through encoder submission. */
@Mixin(GameRenderer.class)
public class NativeShaderFrameMixin {
    @WrapMethod(method = "render")
    private void veil$shaderFrame(Operation<Void> original) {
        try (var frame = NativeShaderInjectionFrames.beginRuntimeFrame()) { original.call(); }
    }
}
