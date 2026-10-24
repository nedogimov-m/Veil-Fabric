package foundry.veil.porttest.mixin;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import foundry.veil.impl.client.render.shader.injection.NativeShaderViews;
import foundry.veil.porttest.GpuShaderViewAcceptance;
import net.minecraft.client.gui.render.GuiRenderer;
import net.minecraft.client.renderer.*;
import net.minecraft.client.renderer.state.level.*;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import java.util.function.Supplier;

@Mixin(GameRenderer.class)
abstract class NativeShaderViewGameProbe implements GpuShaderViewAcceptance.GameAccess {
    @Accessor("hudProjection") public abstract Projection veil$viewHudProjection();
    @Accessor("guiRenderer") public abstract GuiRenderer veil$viewGuiRenderer();
    @Invoker("renderItemInHand") public abstract void veil$viewHand(CameraRenderState camera, PlayerRenderState player, GpuTextureView depth);
    @Inject(method = "renderItemInHand", at = @At("HEAD"), cancellable = true)
    private void veil$probe(CallbackInfo ci) { if (GpuShaderViewAcceptance.probe(NativeShaderViews.Kind.HAND)) ci.cancel(); }
}
@Mixin(LevelRenderer.class)
class NativeShaderViewWorldProbe {
    @Inject(method = "render", at = @At("HEAD"), cancellable = true)
    private void veil$probe(CallbackInfo ci) { if (GpuShaderViewAcceptance.probe(NativeShaderViews.Kind.WORLD)) ci.cancel(); }
}
@Mixin(GuiRenderer.class)
abstract class NativeShaderViewGuiProbe implements GpuShaderViewAcceptance.GuiAccess {
    @Accessor("guiProjection") public abstract Projection veil$viewGuiProjection();
    @Invoker("executeDrawRange") public abstract void veil$viewRange(Supplier<String> name, RenderTarget target, GpuBufferSlice transform, int start, int end);
    @Inject(method = "executeDrawRange", at = @At("HEAD"), cancellable = true)
    private void veil$probe(CallbackInfo ci) { if (GpuShaderViewAcceptance.probe(NativeShaderViews.Kind.GUI)) ci.cancel(); }
}
