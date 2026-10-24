package foundry.veil.mixin.shader.client;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import foundry.veil.impl.client.render.shader.injection.NativeShaderViews;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.render.GuiRenderer;
import net.minecraft.client.renderer.Projection;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import java.util.function.Supplier;

@Mixin(GuiRenderer.class)
public class NativeShaderGuiViewMixin {
    @Shadow @Final private Projection guiProjection;
    /** guiProjection is configured inside draw(), before this range opens its native pass. */
    @WrapMethod(method = "executeDrawRange")
    private void veil$guiView(Supplier<String> name, RenderTarget target, GpuBufferSlice transform, int start, int end, Operation<Void> original) {
        float scale = Minecraft.getInstance().gameRenderer.gameRenderState().windowRenderState.guiScale;
        try (var view = NativeShaderViews.beginGui(this.guiProjection.getMatrix(new Matrix4f()), scale)) { original.call(name, target, transform, start, end); }
    }
}
