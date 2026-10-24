package foundry.veil.mixin.shader.client;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.renderpearl.api.device.GpuDevice;
import com.mojang.renderpearl.api.pipeline.*;
import foundry.veil.api.client.render.shader.NativePipelineBridge;
import net.minecraft.client.renderer.ShaderManager;
import net.minecraft.server.packs.resources.ResourceManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

@Mixin(ShaderManager.class)
public class NativeShaderManagerMixin {
    @Inject(method = "loadConfigs", at = @At("RETURN"))
    private static void veil$sourceOwner(ResourceManager resources, CallbackInfoReturnable<ShaderSource> cir) {
        NativePipelineBridge.sourceLoaded(cir.getReturnValue(), resources);
    }
    @WrapOperation(method = "lambda$compilePipelines$0", at = @At(value = "INVOKE", target = "Lcom/mojang/renderpearl/api/device/GpuDevice;compilePipeline(Lcom/mojang/renderpearl/api/pipeline/RenderPipeline;Lcom/mojang/renderpearl/api/pipeline/ShaderSource;Ljava/util/concurrent/Executor;)Ljava/util/concurrent/CompletableFuture;"))
    private static CompletableFuture<CompiledRenderPipeline.Pending> veil$extend(GpuDevice device, RenderPipeline pipeline, ShaderSource source, Executor workers, Operation<CompletableFuture<CompiledRenderPipeline.Pending>> call) {
        return NativePipelineBridge.compile(device, pipeline, source, workers, (modified, inputs, executor) -> call.call(device, modified, inputs, executor));
    }
}
