package foundry.veil.mixin.perspective.accessor;

import net.minecraft.client.renderer.chunk.SectionRenderDispatcher;
import net.minecraft.world.phys.Vec3;
import java.util.concurrent.atomic.AtomicReference;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(SectionRenderDispatcher.class)
public interface NativePerspectiveTerrainAccessor {
    @Accessor("cameraPosition") AtomicReference<Vec3> veil$perspectiveCameraPosition();
}
