package foundry.veil.mixin.rendertype.client;

import com.mojang.renderpearl.api.commands.RenderPass;
import foundry.veil.impl.client.render.rendertype.NativeRenderTypeDraws;
import net.minecraft.client.renderer.StagedVertexBuffer;
import net.minecraft.client.renderer.rendertype.PreparedRenderType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(PreparedRenderType.class)
public abstract class PreparedRenderTypeMixin {
    @Inject(method = "drawFromBuffer", at = @At("HEAD"), cancellable = true)
    private void veil$drawNativeLayers(StagedVertexBuffer.ExecuteInfo geometry, RenderPass pass, CallbackInfo callback) {
        if (NativeRenderTypeDraws.draw((PreparedRenderType) (Object) this, geometry, pass)) callback.cancel();
    }
}
