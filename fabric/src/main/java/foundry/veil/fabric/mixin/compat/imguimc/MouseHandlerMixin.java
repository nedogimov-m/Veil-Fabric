package foundry.veil.fabric.mixin.compat.imguimc;

import foundry.veil.api.client.render.VeilRenderSystem;
import foundry.veil.impl.client.imgui.VeilImGuiCompat;
import net.minecraft.client.Minecraft;
import net.minecraft.client.MouseHandler;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import net.minecraft.client.input.MouseButtonInfo;
import com.mojang.blaze3d.platform.InputConstants;

@Mixin(MouseHandler.class)
public class MouseHandlerMixin {

    @Shadow
    @Final
    private Minecraft minecraft;

    @Inject(method = "onButton", at = @At("HEAD"))
    public void keyPress(long window, MouseButtonInfo button, int action, CallbackInfo ci) {
        if (window == this.minecraft.getWindow().handle() && action == InputConstants.PRESS && VeilImGuiCompat.EDITOR_KEY.matches(InputConstants.Type.MOUSE.getOrCreate(button.button()))) {
            VeilRenderSystem.renderer().getEditorManager().toggle();
        }
    }
}
