package foundry.veil.api.event;

import foundry.veil.api.client.render.shader.ShaderManager;
import foundry.veil.api.client.render.gpu.GpuShaderProgram;
import net.minecraft.resources.Identifier;

import java.util.Map;

/**
 * Fired when Veil has finished compiling shaders.
 *
 * @author Ocelot
 */
@FunctionalInterface
public interface VeilShaderCompileEvent {

    /**
     * Called when Veil shaders recompile.
     *
     * @param shaderManager   The shader manager instance
     * @param updatedPrograms A view of the programs that have changed
     */
    void onVeilCompileShaders(ShaderManager shaderManager, Map<Identifier, GpuShaderProgram> updatedPrograms);
}
