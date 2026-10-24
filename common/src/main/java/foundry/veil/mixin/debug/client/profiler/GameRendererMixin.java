package foundry.veil.mixin.debug.client.profiler;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import foundry.veil.api.client.render.profiler.RenderProfilerCounter;
import foundry.veil.api.client.render.profiler.VeilRenderProfiler;
import net.minecraft.client.renderer.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;

/** Native GPU timing follows the real frame and level render scopes. */
@Mixin(GameRenderer.class)
public class GameRendererMixin {
    @WrapMethod(method = "render")
    private void veil$profileFrame(Operation<Void> original) {
        VeilRenderProfiler profiler = VeilRenderProfiler.get(); profiler.push("frame", RenderProfilerCounter.STANDARD_GEOMETRY);
        try { original.call(); } finally { profiler.pop(); }
    }
    @WrapMethod(method = "renderLevel")
    private void veil$profileLevel(Operation<Void> original) {
        VeilRenderProfiler profiler = VeilRenderProfiler.get(); profiler.push("level", RenderProfilerCounter.STANDARD_GEOMETRY);
        try { original.call(); } finally { profiler.pop(); }
    }
}
