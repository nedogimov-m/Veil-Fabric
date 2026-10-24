package foundry.veil.mixin.debug.client;

import net.minecraft.client.KeyboardHandler;
import net.minecraft.client.Minecraft;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import static org.lwjgl.sdl.SDLScancode.SDL_SCANCODE_L;
import static org.lwjgl.sdl.SDLScancode.SDL_SCANCODE_Q;

@Mixin(KeyboardHandler.class)
public abstract class DebugKeyboardHandlerMixin {

    @Shadow
    @Final
    private Minecraft minecraft;

    @Shadow
    protected abstract boolean handleChunkDebugKeys(KeyEvent key);

    @Inject(method = "handleDebugKeys", at = @At("HEAD"), cancellable = true)
    public void handleChunkDebugKeys(KeyEvent key, CallbackInfoReturnable<Boolean> cir) {
        if (key.key() != SDL_SCANCODE_L && this.handleChunkDebugKeys(key)) {
            cir.setReturnValue(true);
        }
    }

    @Inject(method = "handleDebugKeys", at = @At("RETURN"))
    public void printChunkDebugKeys(KeyEvent key, CallbackInfoReturnable<Boolean> cir) {
        if (key.key() == SDL_SCANCODE_Q) {
            this.minecraft.showDebugChat(Component.literal("F3 + E = Show VisGraph Path"));
            this.minecraft.showDebugChat(Component.literal("F3 + U = Capture View Frustum"));
            this.minecraft.showDebugChat(Component.literal("F3 + Shift + U = Kill Captured View Frustum"));
            this.minecraft.showDebugChat(Component.literal("F3 + V = Show VisGraph Visibility"));
            this.minecraft.showDebugChat(Component.literal("F3 + W = Show Wireframe"));
        }
    }
}
