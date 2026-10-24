package foundry.veil.mixin.debug.client;

import com.mojang.renderpearl.api.device.GpuDebugOptions;
import foundry.veil.Veil;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

@Mixin(Minecraft.class)
public class DebugMinecraftMixin {
    @ModifyArg(method = "<init>", at = @At(value = "INVOKE", target = "Lcom/mojang/renderpearl/api/device/GpuBackend;createDevice(Lcom/mojang/renderpearl/api/device/GpuDebugOptions;)Lcom/mojang/renderpearl/api/device/GpuDevice;"), index = 0)
    private GpuDebugOptions veil$nativeDebugOptions(GpuDebugOptions options) {
        return Veil.DEBUG ? new GpuDebugOptions(Math.max(options.logLevel(), 100), true, true, true) : options;
    }
}
