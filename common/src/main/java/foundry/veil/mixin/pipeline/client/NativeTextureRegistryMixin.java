package foundry.veil.mixin.pipeline.client;

import com.mojang.renderpearl.api.textures.GpuTexture;
import foundry.veil.api.client.util.NativeTextureRegistry;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Inventory only successfully returned real frontend allocations, including Minecraft and other mods. */
@Mixin(targets="com.mojang.renderpearl.frontend.FrontendGpuDevice",remap=false)
public abstract class NativeTextureRegistryMixin {
    @Inject(method={
            "createTexture(Ljava/lang/String;ILcom/mojang/renderpearl/api/GpuFormat;IIII)Lcom/mojang/renderpearl/api/textures/GpuTexture;",
            "createTexture(Ljava/util/function/Supplier;ILcom/mojang/renderpearl/api/GpuFormat;IIII)Lcom/mojang/renderpearl/api/textures/GpuTexture;"
    },at=@At("RETURN"))
    private void veil$trackNativeTexture(CallbackInfoReturnable<GpuTexture> result){NativeTextureRegistry.track(result.getReturnValue());}
}
