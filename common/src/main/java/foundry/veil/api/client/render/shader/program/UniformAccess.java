package foundry.veil.api.client.render.shader.program;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import foundry.veil.api.client.render.gpu.GpuUniformBuffer;
import foundry.veil.api.client.render.shader.uniform.ShaderUniform;
import foundry.veil.api.client.render.shader.uniform.ShaderUniformAccess;
import org.jetbrains.annotations.Nullable;

/** Named native bindings and explicitly declared std140 fields. */
public interface UniformAccess {
    boolean hasUniform(String binding);
    GpuUniformBuffer createUniformBlock(String name, GpuUniformBuffer.Layout layout);
    @Nullable GpuUniformBuffer getUniformBlock(String name);
    void setUniformBlock(String name, GpuBufferSlice buffer);
    void removeExternalUniformBlock(String name);
    @Nullable ShaderUniform getUniform(String block, String field);
    /** A migrated source-level numeric uniform resides in the program's actual owned std140 block. */
    default @Nullable ShaderUniform getUniform(String field) {
        return this.getUniform(foundry.veil.api.client.render.gpu.GpuShaderSource.MANAGED_UNIFORMS, field);
    }
    default ShaderUniformAccess getUniformSafe(String field) {
        ShaderUniform uniform = this.getUniform(field); return uniform != null ? uniform : ShaderUniformAccess.EMPTY;
    }
    default ShaderUniformAccess getUniformSafe(String block, String field) {
        ShaderUniform uniform = this.getUniform(block, field);
        return uniform != null ? uniform : ShaderUniformAccess.EMPTY;
    }
}
