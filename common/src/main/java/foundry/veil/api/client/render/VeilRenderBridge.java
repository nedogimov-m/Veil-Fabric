package foundry.veil.api.client.render;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import foundry.veil.api.client.render.framebuffer.FramebufferManager;
import foundry.veil.api.client.render.gpu.GpuFramebuffer;
import foundry.veil.api.client.render.gpu.GpuShaderProgram;
import foundry.veil.api.client.render.rendertype.VeilRenderTypeBuilder;
import foundry.veil.api.client.render.shader.ShaderManager;
import foundry.veil.api.client.render.shader.program.ShaderProgram;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.resources.Identifier;
import org.jetbrains.annotations.Nullable;
import java.util.function.Supplier;
import java.util.function.UnaryOperator;

/** Bridges native Minecraft resources and Veil's explicit renderer contracts. */
public interface VeilRenderBridge {
    static RenderPipeline toPipeline(ShaderProgram program) { return program.definition(); }
    static @Nullable GpuShaderProgram toShaderProgram(ShaderManager manager, RenderPipeline pipeline) { return manager.findShader(pipeline); }
    static CullFrustum create(Frustum frustum) { return (CullFrustum) frustum; }
    static MatrixStack create(PoseStack poseStack) { return (MatrixStack) poseStack; }
    static VeilRenderTypeBuilder createBuilder() { return new VeilRenderTypeBuilder(); }

    /** Caller closes the borrow; the native textures remain owned by Minecraft. */
    static GpuFramebuffer wrap(RenderTarget target) {
        return GpuFramebuffer.borrow(RenderSystem.getDevice(), "Minecraft render target", target);
    }
    /** Acquire a fresh borrow per use so resize never leaves a cached closed view. */
    static Supplier<GpuFramebuffer> wrap(Supplier<RenderTarget> targets) {
        return () -> wrap(targets.get());
    }
    static UnaryOperator<VeilRenderTypeBuilder> shaderState(Identifier shader) { return builder -> builder.shaderState(shader, true); }
    static UnaryOperator<VeilRenderTypeBuilder> outputState(Identifier framebuffer) { return builder -> builder.outputState(framebuffer); }
    /** Manual targets remain caller-owned and are selected by the same native registry as resource targets. */
    static UnaryOperator<VeilRenderTypeBuilder> outputState(FramebufferManager manager, Identifier name, GpuFramebuffer framebuffer) {
        manager.setFramebuffer(name, framebuffer);
        return outputState(name);
    }
    static UnaryOperator<VeilRenderTypeBuilder> patchState(int vertices) { return builder -> builder.patchState(vertices); }
    static UnaryOperator<VeilRenderTypeBuilder> depthClampState() { return builder -> builder.depthClampState(true); }
    static UnaryOperator<VeilRenderTypeBuilder> multisampleState() { return builder -> builder.multisampleState(true); }
    static UnaryOperator<VeilRenderTypeBuilder> seamlessCubeMapState() { return builder -> builder.seamlessCubemapState(true); }
}
