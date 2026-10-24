package foundry.veil.mixin.pipeline.accessor;

import com.mojang.renderpearl.backend.api.GpuDeviceBackend;
import com.mojang.renderpearl.frontend.FrontendGpuDevice;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Exposes the genuine backend so the array extension can allocate its actual native layers. */
@Mixin(value=FrontendGpuDevice.class,remap=false)
public interface NativeTextureDeviceAccessor {
    @Accessor("backend") GpuDeviceBackend veil$textureBackend();
}
