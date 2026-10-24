package foundry.veil.mixin.shader.client;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.mojang.blaze3d.resource.GraphicsResourceAllocator;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import foundry.veil.impl.client.render.shader.injection.NativeShaderViews;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import org.joml.Vector4f;
import org.spongepowered.asm.mixin.Mixin;

@Mixin(LevelRenderer.class)
public class NativeShaderWorldViewMixin {
    @WrapMethod(method = "render")
    private void veil$worldView(GraphicsResourceAllocator allocator, boolean outlines, CameraRenderState camera,
            GpuBufferSlice fog, Vector4f fogColor, boolean sky, boolean clouds, Operation<Void> original) {
        try (var view = NativeShaderViews.beginWorld(camera)) { original.call(allocator, outlines, camera, fog, fogColor, sky, clouds); }
    }
}
