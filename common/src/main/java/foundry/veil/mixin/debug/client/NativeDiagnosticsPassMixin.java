package foundry.veil.mixin.debug.client;

import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.frontend.FrontendRenderPass;
import foundry.veil.api.client.render.ext.VeilDebug;
import foundry.veil.impl.client.render.profiler.VeilRenderProfilerImpl;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(FrontendRenderPass.class)
public class NativeDiagnosticsPassMixin {
    @Inject(method = "close", at = @At("HEAD"))
    private void veil$closeNativeDiagnostics(CallbackInfo ci) {
        RenderPass pass = (RenderPass) (Object) this;
        try { VeilRenderProfilerImpl.closed(pass); } finally { VeilDebug.get().closed(pass); }
    }
}
