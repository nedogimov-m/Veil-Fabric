package foundry.veil.mixin.pipeline.client;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import foundry.veil.Veil;
import foundry.veil.api.client.render.VeilLevelPerspectiveRenderer;
import foundry.veil.api.client.render.VeilRenderSystem;
import foundry.veil.api.compat.IrisCompat;
import foundry.veil.impl.client.render.pipeline.VeilBloomRenderer;
import foundry.veil.impl.client.render.pipeline.VeilFirstPersonRenderer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.client.renderer.state.level.PlayerRenderState;
import org.joml.Vector3f;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.invoke.arg.Args;

/** Native hand target and end-of-level postprocessing; view UBO timing is handled by explicit native frame scopes. */
@Mixin(GameRenderer.class)
public class PipelineGameRendererMixin {
    @Shadow @Final private Minecraft minecraft;
    @Shadow @Final private net.minecraft.client.renderer.Projection hudProjection;
    @Unique private final Vector3f veil$cameraBobOffset=new Vector3f();
    @Inject(method="renderLevel",at=@At("HEAD"))
    private void veil$resetNoBob(CallbackInfo callback){if(!minecraft.options.bobView().get())VeilRenderSystem.setCameraBobOffset(veil$cameraBobOffset.zero());}
    @Inject(method="bobView",at=@At("HEAD"))
    private void veil$beginBob(CallbackInfo callback){veil$cameraBobOffset.zero();}
    @Inject(method="bobView",at=@At("TAIL"))
    private void veil$endBob(CallbackInfo callback){VeilRenderSystem.setCameraBobOffset(veil$cameraBobOffset);}
    @ModifyArgs(method="bobView",at=@At(value="INVOKE",target="Lcom/mojang/blaze3d/vertex/PoseStack;translate(FFF)V"))
    private void veil$captureBob(Args args){veil$cameraBobOffset.sub(args.get(0),args.get(1),args.get(2));}
    @Inject(method="resize",at=@At("TAIL"))
    private void veil$resizeTargets(CallbackInfo callback){var target=minecraft.gameRenderer.mainRenderTarget();VeilRenderSystem.resize(target.width,target.height);}
    @Inject(method="renderLevel",at=@At("TAIL"))
    private void veil$postWorld(CallbackInfo callback){if(!Veil.platform().hasErrors()&&!VeilLevelPerspectiveRenderer.isRenderingPerspective())VeilRenderSystem.renderPost(null);}
    @WrapMethod(method="renderItemInHand")
    private void veil$renderNativeHand(CameraRenderState camera,PlayerRenderState player,GpuTextureView depth,Operation<Void> original){
        if(camera.isPanoramicMode||Veil.platform().hasErrors()||(IrisCompat.INSTANCE!=null&&IrisCompat.INSTANCE.areShadersLoaded())){original.call(camera,player,depth);return;}
        try(var view=foundry.veil.impl.client.render.shader.injection.NativeShaderViews.beginHand(hudProjection.getMatrix(new org.joml.Matrix4f()),camera);
            var scope=VeilFirstPersonRenderer.begin()){original.call(camera,player,scope.framebuffer().depth().view());}
    }
    @Inject(method="close",at=@At("TAIL"))
    private void veil$closeTargets(CallbackInfo callback){VeilFirstPersonRenderer.free();VeilBloomRenderer.free();}
}
