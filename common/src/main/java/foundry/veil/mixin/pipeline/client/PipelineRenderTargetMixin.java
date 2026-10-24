package foundry.veil.mixin.pipeline.client;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.textures.GpuTexture;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import foundry.veil.api.client.render.gpu.GpuFramebuffer;
import foundry.veil.ext.RenderTargetExtension;
import foundry.veil.impl.client.render.framebuffer.NativeRenderTargetStorage;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Native target redirection uses actual typed fields; its own allocation/deletion still owns only native storage. */
@Mixin(RenderTarget.class)
public abstract class PipelineRenderTargetMixin implements RenderTargetExtension {
    @Shadow public int width;
    @Shadow public int height;
    @Shadow @Nullable protected GpuTexture colorTexture;
    @Shadow @Nullable protected GpuTextureView colorTextureView;
    @Shadow @Nullable protected GpuTexture depthTexture;
    @Shadow @Nullable protected GpuTextureView depthTextureView;
    @Unique @Nullable private GpuFramebuffer veil$wrapper;
    @Unique @Nullable private NativeRenderTargetStorage veil$nativeStorage;

    @Unique private NativeRenderTargetStorage veil$capture() {
        return new NativeRenderTargetStorage(width,height,colorTexture,colorTextureView,depthTexture,depthTextureView);
    }
    @Unique private void veil$restoreNative() {
        final var stored=java.util.Objects.requireNonNull(veil$nativeStorage);
        width=stored.width();height=stored.height();colorTexture=stored.color();colorTextureView=stored.colorView();
        depthTexture=stored.depth();depthTextureView=stored.depthView();
    }
    @Unique private void veil$applyWrapper() {
        final var framebuffer=java.util.Objects.requireNonNull(veil$wrapper);
        width=framebuffer.width();height=framebuffer.height();
        colorTexture=framebuffer.color(0).texture();colorTextureView=framebuffer.color(0).view();
        depthTexture=framebuffer.hasDepth()?framebuffer.depth().texture():null;
        depthTextureView=framebuffer.hasDepth()?framebuffer.depth().view():null;
    }

    @Override public void veil$setWrapper(@Nullable GpuFramebuffer framebuffer) {
        RenderSystem.assertOnRenderThread();
        if(framebuffer==veil$wrapper)return;
        if(framebuffer!=null) {
            if(framebuffer.colorCount()==0)throw new IllegalArgumentException("RenderTarget redirection requires a color attachment");
            framebuffer.color(0).view();if(framebuffer.hasDepth())framebuffer.depth().view();
            if(veil$wrapper==null)veil$nativeStorage=veil$capture();
            veil$wrapper=framebuffer;veil$applyWrapper();
        } else {
            veil$restoreNative();veil$wrapper=null;veil$nativeStorage=null;
        }
    }
    @Override public @Nullable GpuFramebuffer veil$getWrapper(){return veil$wrapper;}
    @Override public @Nullable GpuTextureView veil$getTexture(int index) {
        if(veil$wrapper!=null)return index>=0&&index<veil$wrapper.colorCount()?veil$wrapper.color(index).view():null;
        return index==0?colorTextureView:null;
    }
    @Inject(method="hasDepth",at=@At("HEAD"),cancellable=true)
    private void veil$nativeDepth(CallbackInfoReturnable<Boolean> result){if(veil$wrapper!=null)result.setReturnValue(veil$wrapper.hasDepth());}

    @WrapMethod(method="destroyBuffers")
    private void veil$destroyNativeStorage(Operation<Void> original) {
        if(veil$wrapper==null){original.call();return;}
        veil$restoreNative();
        try {original.call();} finally {veil$nativeStorage=veil$capture();veil$applyWrapper();}
    }
    @WrapMethod(method="createBuffers")
    private void veil$createNativeStorage(int width,int height,Operation<Void> original) {
        if(veil$wrapper==null){original.call(width,height);return;}
        veil$restoreNative();
        try {original.call(width,height);} finally {veil$nativeStorage=veil$capture();veil$applyWrapper();}
    }
}
