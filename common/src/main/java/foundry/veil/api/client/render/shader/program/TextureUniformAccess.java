package foundry.veil.api.client.render.shader.program;
import com.mojang.renderpearl.api.textures.GpuSampler;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import foundry.veil.api.client.render.framebuffer.FramebufferDefinition;
import foundry.veil.api.client.render.gpu.GpuFramebuffer;
import foundry.veil.api.client.render.texture.TextureFilter;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.AbstractTexture;
import net.minecraft.resources.Identifier;
import org.jetbrains.annotations.Nullable;

/** Explicit native texture-view/sampler bindings, borrowed from their respective owners. */
public interface TextureUniformAccess {
    boolean hasUniform(String name);
    void setTexture(String name, GpuTextureView texture, GpuSampler sampler);
    void setTexture(String name, GpuTextureView texture, TextureFilter filter);
    void removeTexture(String name);
    void clearTextureBindings();
    default void setTexture(String name, Identifier location) { this.setTexture(name, Minecraft.getInstance().getTextureManager().getTexture(location)); }
    default void setTexture(String name, AbstractTexture texture) { this.setTexture(name, texture.getTextureView(), texture.getSampler()); }
    default void setFramebufferSamplers(GpuFramebuffer framebuffer) { this.setFramebufferSamplers(framebuffer, null); }
    default void setFramebufferSamplers(GpuFramebuffer framebuffer, @Nullable FramebufferDefinition definition) {
        for (int index = 0; index < framebuffer.colorCount(); index++) {
            var attachment = framebuffer.color(index);
            var settings = definition != null ? definition.colorBuffers()[index] : null;
            TextureFilter filter = settings != null ? settings.filter() : TextureFilter.CLAMP;
            String name = "DiffuseSampler" + index;
            if (this.hasUniform(name)) this.setTexture(name, attachment.view(), filter);
            if (index == 0 && this.hasUniform("DiffuseSampler")) this.setTexture("DiffuseSampler", attachment.view(), filter);
            if (settings != null && settings.name() != null && this.hasUniform(settings.name())) this.setTexture(settings.name(), attachment.view(), filter);
        }
        if (framebuffer.hasDepth()) {
            var settings = definition != null ? definition.depthBuffer() : null;
            TextureFilter filter = settings != null ? settings.filter() : TextureFilter.CLAMP;
            if (this.hasUniform("DiffuseDepthSampler")) this.setTexture("DiffuseDepthSampler", framebuffer.depth().view(), filter);
            if (settings != null && settings.name() != null && this.hasUniform(settings.name())) this.setTexture(settings.name(), framebuffer.depth().view(), filter);
        }
    }
}
