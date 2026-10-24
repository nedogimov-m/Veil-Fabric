package foundry.veil.fabric.mixin.client;

import foundry.veil.api.client.render.VeilRenderSystem;
import foundry.veil.fabric.FabricRenderTypeStageHandler;
import foundry.veil.fabric.event.FabricFreeNativeResourcesEvent;
import foundry.veil.fabric.event.FabricVeilRegisterFixedBuffersEvent;
import foundry.veil.fabric.event.FabricVeilRegisterBlockLayersEvent;
import foundry.veil.fabric.event.FabricVeilRendererAvailableEvent;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = Minecraft.class, priority = 900)
public class MinecraftMixin {

    @Inject(method = "<init>", at = @At(value = "INVOKE", target = "Lnet/minecraft/server/packs/resources/ReloadableResourceManager;createReload(Ljava/util/concurrent/Executor;Ljava/util/concurrent/Executor;Ljava/util/concurrent/CompletableFuture;Ljava/util/List;)Lnet/minecraft/server/packs/resources/ReloadInstance;"))
    public void init(CallbackInfo ci) {
        VeilRenderSystem.init();
        FabricVeilRendererAvailableEvent.EVENT.invoker().onVeilRendererAvailable(VeilRenderSystem.renderer());
        FabricVeilRegisterFixedBuffersEvent.EVENT.invoker().onRegisterFixedBuffers(FabricRenderTypeStageHandler::registerSubmission);
        FabricVeilRegisterBlockLayersEvent.EVENT.invoker().onRegisterBlockLayers(FabricRenderTypeStageHandler::registerSubmission);
    }

    @Inject(method = "close", at = @At("HEAD"))
    public void close(CallbackInfo ci) {
        FabricFreeNativeResourcesEvent.EVENT.invoker().onFree();
    }
}
