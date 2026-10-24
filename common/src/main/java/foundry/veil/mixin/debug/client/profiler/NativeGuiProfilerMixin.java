package foundry.veil.mixin.debug.client.profiler;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import foundry.veil.api.client.render.profiler.RenderProfilerCounter;
import foundry.veil.api.client.render.profiler.VeilRenderProfiler;
import net.minecraft.client.gui.render.GuiRenderer;
import org.spongepowered.asm.mixin.Mixin;

@Mixin(GuiRenderer.class)
public class NativeGuiProfilerMixin {
    @WrapMethod(method = "render")
    private void veil$profileGui(Operation<Void> original) {
        VeilRenderProfiler profiler = VeilRenderProfiler.get(); profiler.push("hud", RenderProfilerCounter.STANDARD_GEOMETRY);
        try { original.call(); } finally { profiler.pop(); }
    }
}
