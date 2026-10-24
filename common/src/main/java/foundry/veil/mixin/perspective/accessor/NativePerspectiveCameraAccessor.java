package foundry.veil.mixin.perspective.accessor;

import net.minecraft.client.Camera;
import net.minecraft.client.renderer.Projection;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(Camera.class)
public interface NativePerspectiveCameraAccessor {
    @Accessor("initialized") void veil$perspectiveInitialized(boolean initialized);
    @Accessor("projection") Projection veil$perspectiveProjection();
}
