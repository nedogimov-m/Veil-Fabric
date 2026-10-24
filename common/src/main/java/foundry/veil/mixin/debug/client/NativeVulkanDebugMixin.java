package foundry.veil.mixin.debug.client;

import com.mojang.renderpearl.backend.vulkan.VulkanDebug;
import foundry.veil.VeilDebugHooks;
import org.lwjgl.vulkan.VkDebugUtilsMessengerCallbackDataEXT;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(VulkanDebug.Enabled.class)
public class NativeVulkanDebugMixin {
    @Inject(method = "onDebugMessage", at = @At("HEAD"))
    private void veil$nativeValidation(int severity, int type, long data, long user, CallbackInfoReturnable<Integer> ci) {
        var callback = VkDebugUtilsMessengerCallbackDataEXT.create(data);
        VeilDebugHooks.onGpuMessage("Vulkan", severity, type, callback.messageIdNumber(), callback.pMessageString(), user);
    }
}
