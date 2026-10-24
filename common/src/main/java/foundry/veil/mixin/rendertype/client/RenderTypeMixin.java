package foundry.veil.mixin.rendertype.client;

import foundry.veil.impl.client.render.rendertype.NativeRenderTypeDraws;
import foundry.veil.api.client.registry.RenderTypeShardRegistry;
import foundry.veil.ext.CompositeStateExtension;
import foundry.veil.api.client.render.rendertype.VeilRenderTypeBuilder;
import java.util.Collection;
import java.util.function.UnaryOperator;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import net.minecraft.client.renderer.rendertype.PreparedRenderType;
import net.minecraft.client.renderer.rendertype.RenderType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(RenderType.class)
public abstract class RenderTypeMixin implements CompositeStateExtension {
    @Inject(method = "<init>", at = @At("RETURN"))
    private void veil$trackNativeType(CallbackInfo callback) {
        RenderTypeShardRegistry.created((RenderType) (Object) this);
    }
    @Override public void veil$addShards(Collection<UnaryOperator<VeilRenderTypeBuilder>> transforms) {
        @SuppressWarnings("unchecked") final UnaryOperator<VeilRenderTypeBuilder>[] values = transforms.toArray(UnaryOperator[]::new);
        RenderTypeShardRegistry.addShard((RenderType) (Object) this, values);
    }

    @Inject(method = "prepare", at = @At("RETURN"))
    private void veil$captureNativeDraw(CallbackInfoReturnable<PreparedRenderType> callback) {
        NativeRenderTypeDraws.capture((RenderType) (Object) this, callback.getReturnValue());
    }
}
