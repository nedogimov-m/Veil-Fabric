package foundry.veil.mixin.debug.client;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.api.commands.RenderPassDescriptor;
import com.mojang.renderpearl.frontend.FrontendCommandEncoder;
import foundry.veil.api.client.render.ext.VeilDebug;
import foundry.veil.impl.client.render.profiler.VeilRenderProfilerImpl;
import org.spongepowered.asm.mixin.Mixin;

@Mixin(FrontendCommandEncoder.class)
public class NativeDiagnosticsEncoderMixin {
    @WrapMethod(method = "createRenderPass(Lcom/mojang/renderpearl/api/commands/RenderPassDescriptor;)Lcom/mojang/renderpearl/api/commands/RenderPass;")
    private RenderPass veil$nativeDiagnostics(RenderPassDescriptor descriptor, Operation<RenderPass> original) {
        RenderPass pass = original.call(descriptor);
        try { VeilDebug.get().opened(pass); VeilRenderProfilerImpl.opened(pass); return pass; }
        catch (RuntimeException | Error failure) { pass.close(); throw failure; }
    }
}
