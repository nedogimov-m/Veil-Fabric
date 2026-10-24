package foundry.veil.mixin.debug.client;

import com.mojang.blaze3d.systems.RenderSystem;
import foundry.veil.api.client.render.ext.VeilDebug;
import net.minecraft.util.profiling.ActiveProfiler;
import it.unimi.dsi.fastutil.longs.LongList;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ActiveProfiler.class)
public class DebugActiveProfilerMixin {

    @Shadow private boolean started;
    @Shadow @Final private LongList startTimes;
    @Unique private int veil$mirroredDepth;

    @Inject(method = "push(Ljava/lang/String;)V", at = @At("HEAD"))
    public void push(String name, CallbackInfo ci) {
        if (this.started && RenderSystem.isOnRenderThread()) {
            VeilDebug.get().pushDebugGroup(name);
            this.veil$mirroredDepth++;
        }
    }

    @Inject(method = "pop()V", at = @At("HEAD"))
    public void pop(CallbackInfo ci) {
        if (this.started && !this.startTimes.isEmpty() && this.veil$mirroredDepth > 0 && RenderSystem.isOnRenderThread()) {
            VeilDebug.get().popDebugGroup();
            this.veil$mirroredDepth--;
        }
    }
    @Inject(method = "endTick", at = @At("RETURN"))
    private void veil$resetNativeGroups(CallbackInfo ci) {
        if (RenderSystem.isOnRenderThread()) {
            while (this.veil$mirroredDepth > 0) {
                VeilDebug.get().popDebugGroup();
                this.veil$mirroredDepth--;
            }
        }
    }
}

