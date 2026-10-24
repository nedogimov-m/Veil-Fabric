package foundry.veil.mixin.perspective.accessor;

import net.minecraft.client.RotatingSectionStorage;
import net.minecraft.client.renderer.ViewArea;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(ViewArea.class)
public interface NativePerspectiveViewAreaAccessor {
    @Accessor("sections") RotatingSectionStorage<SectionRenderDispatcher.RenderSection> veil$perspectiveSections();
}
