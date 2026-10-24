package foundry.veil.mixin.shader.client;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.blaze3d.pipeline.PipelineCache;
import com.mojang.renderpearl.api.device.GpuDevice;
import com.mojang.renderpearl.api.pipeline.*;
import foundry.veil.api.client.render.shader.NativePipelineBridge;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

@Mixin(PipelineCache.class)
public class NativePipelineCacheMixin {
    @Shadow @Final private Map<RenderPipeline, CompiledRenderPipeline> cache;
    @Unique private final Map<RenderPipeline, Long> veil$sourceRevisions = new HashMap<>();
    /** Keep the genuine last working owner until a changed source successfully finishes its real native compilation. */
    @WrapMethod(method = "get")
    private CompiledRenderPipeline veil$refreshSources(RenderPipeline definition, Operation<CompiledRenderPipeline> original) {
        long revision = NativePipelineBridge.sourceRevision();
        CompiledRenderPipeline previous = this.cache.get(definition);
        Long observed = this.veil$sourceRevisions.get(definition);
        if (previous == null || observed == null || observed == revision) {
            CompiledRenderPipeline result = original.call(definition);
            if (result != null && !result.isClosed()) this.veil$sourceRevisions.put(definition, revision);
            return result;
        }
        this.cache.remove(definition);
        try {
            CompiledRenderPipeline replacement = original.call(definition);
            if (replacement == null || replacement.isClosed()) {
                this.cache.put(definition, previous); this.veil$sourceRevisions.put(definition, revision); return previous;
            }
            this.veil$sourceRevisions.put(definition, revision);
            previous.close(); return replacement;
        } catch (RuntimeException failure) {
            this.cache.put(definition, previous); this.veil$sourceRevisions.put(definition, revision);
            org.slf4j.LoggerFactory.getLogger("Veil").error("Cannot refresh native pipeline {}; retaining its last working owner", definition.getLocation(), failure);
            return previous;
        } catch (Error failure) { this.cache.put(definition, previous); throw failure; }
    }
    @Inject(method = "clear", at = @At("RETURN"))
    private void veil$clearRevisions(CallbackInfo ci) { this.veil$sourceRevisions.clear(); }
    @WrapOperation(method = "get", at = @At(value = "INVOKE", target = "Lcom/mojang/renderpearl/api/device/GpuDevice;compilePipeline(Lcom/mojang/renderpearl/api/pipeline/RenderPipeline;Lcom/mojang/renderpearl/api/pipeline/ShaderSource;Ljava/util/concurrent/Executor;)Ljava/util/concurrent/CompletableFuture;"))
    private CompletableFuture<CompiledRenderPipeline.Pending> veil$extend(GpuDevice device, RenderPipeline pipeline, ShaderSource source, Executor workers, Operation<CompletableFuture<CompiledRenderPipeline.Pending>> call) {
        return NativePipelineBridge.compile(device, pipeline, source, workers, (modified, inputs, executor) -> call.call(device, modified, inputs, executor));
    }
}
