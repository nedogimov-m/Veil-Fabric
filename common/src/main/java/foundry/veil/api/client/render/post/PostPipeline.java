package foundry.veil.api.client.render.post;

import com.mojang.renderpearl.api.commands.CommandEncoder;
import com.mojang.renderpearl.api.device.GpuDevice;
import com.mojang.renderpearl.api.pipeline.CompareOp;
import com.mojang.renderpearl.api.textures.GpuSampler;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import com.mojang.serialization.Codec;
import foundry.veil.api.client.registry.PostPipelineStageRegistry;
import foundry.veil.api.client.render.gpu.GpuFramebuffer;
import foundry.veil.api.client.render.gpu.GpuShaderProgram;
import foundry.veil.api.client.render.shader.ShaderManager;
import foundry.veil.api.client.render.shader.texture.ShaderTextureSource;
import foundry.veil.api.client.render.texture.TextureFilter;
import foundry.veil.api.client.render.dynamicbuffer.DynamicBufferType;
import foundry.veil.api.client.render.framebuffer.FramebufferDefinition;
import net.minecraft.resources.Identifier;
import org.jetbrains.annotations.Nullable;
import org.lwjgl.system.NativeResource;
import java.util.concurrent.Executor;

/** Explicit native GPU post stages. No global framebuffer, shader or GL state. */
public interface PostPipeline extends NativeResource {
    Codec<PostPipeline> CODEC = PostPipelineStageRegistry.REGISTRY.byNameCodec()
            .dispatch(PostPipeline::getType, PostPipelineStageRegistry.PipelineType::codec);
    void apply(Context context);
    @Override default void free() { }
    PostPipelineStageRegistry.PipelineType<? extends PostPipeline> getType();

    interface Context extends ShaderTextureSource.Context {
        GpuDevice device();
        CommandEncoder encoder();
        Executor workers();
        ShaderManager shaders();
        void setTexture(String name, GpuTextureView texture, GpuSampler sampler);
        void setTexture(String name, GpuTextureView texture, TextureFilter filter);
        void setFramebuffer(Identifier name, GpuFramebuffer framebuffer);
        void setFramebuffer(Identifier name, GpuFramebuffer framebuffer, FramebufferDefinition definition);
        void applySamplers(GpuShaderProgram shader);
        GpuFramebuffer getDrawFramebuffer();
        default GpuFramebuffer getFramebufferOrDraw(Identifier name) {
            GpuFramebuffer target = this.getFramebuffer(name);
            return target != null ? target : this.getDrawFramebuffer();
        }
        default GpuFramebuffer requireFramebuffer(Identifier name) {
            GpuFramebuffer target = this.getFramebuffer(name);
            if (target == null) throw new IllegalArgumentException("Unknown post framebuffer: " + name);
            return target;
        }
        void setColorMask(boolean red, boolean green, boolean blue, boolean alpha);
        int colorMask();
        void setDepthState(CompareOp comparison, boolean writeDepth);
        CompareOp depthComparison();
        boolean writeDepth();
        void copy(GpuFramebuffer source, GpuFramebuffer target, boolean color, boolean depth, boolean linear);
        GpuTextureView getDynamicTexture(DynamicBufferType type);
        Scope pushScope();
        @Nullable PostPipeline getPipeline(Identifier name);
        interface Scope extends AutoCloseable { @Override void close(); }
    }
}
