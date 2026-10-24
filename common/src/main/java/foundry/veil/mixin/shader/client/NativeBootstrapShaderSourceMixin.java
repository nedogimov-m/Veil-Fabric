package foundry.veil.mixin.shader.client;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.blaze3d.pipeline.PipelineCache;
import com.mojang.renderpearl.api.device.GpuDevice;
import com.mojang.renderpearl.api.pipeline.ShaderSource;
import foundry.veil.api.client.render.shader.NativePipelineBridge;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.server.packs.resources.ResourceManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** The loading-screen source is created before ShaderManager's first pack reload. */
@Mixin(GameRenderer.class)
public class NativeBootstrapShaderSourceMixin {
    @WrapOperation(method = "preloadUiShader", at = @At(value = "NEW", target = "(Lcom/mojang/renderpearl/api/device/GpuDevice;Lcom/mojang/renderpearl/api/pipeline/ShaderSource;)Lcom/mojang/blaze3d/pipeline/PipelineCache;"))
    private static PipelineCache veil$bootstrapSourceOwner(GpuDevice device, ShaderSource source,
            Operation<PipelineCache> original, ResourceManager resources) {
        NativePipelineBridge.sourceLoaded(source, resources);
        return original.call(device, source);
    }
}
