package foundry.veil.api.compat;

import foundry.veil.Veil;
import foundry.veil.ext.iris.IrisRenderTargetExtension;
import com.mojang.renderpearl.backend.opengl.GlProgram;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.Nullable;

import java.util.Map;
import java.util.ServiceLoader;
import java.util.Set;

/**
 * Veil iris compat implementation.
 *
 * @author Ocelot
 */
public interface IrisCompat {

    /**
     * Retrieves the compat instance. This will be <code>null</code> if iris is not installed.
     */
    @Nullable
    IrisCompat INSTANCE = Veil.platform().isModLoaded("iris") ? ServiceLoader.load(IrisCompat.class).findFirst().orElse(null) : null;

    /**
     * @return Whether Iris is loaded
     */
    static boolean isLoaded() {
        return INSTANCE != null;
    }

    /**
     * @return All loaded shaders created by Iris
     */
    Set<GlProgram> getLoadedShaders();

    /**
     * @return A mapping of all iris GBuffers by name
     */
    Map<String, IrisRenderTargetExtension> getRenderTargets();

    /**
     * @return Whether shaders are currently being used
     */
    record Flags(boolean packInUse, boolean shadowPass, boolean reverseZ) { }
    Flags getFlags();
    default boolean areShadersLoaded() { return getFlags().packInUse(); }

    /** Assign a genuine native pipeline using an actual IrisProgram enum name. */
    void assignPipeline(RenderPipeline pipeline, String program);
    /** Assign a genuine native shadow pipeline using an actual IrisShadowProgram enum name. */
    void assignShadowPipeline(RenderPipeline pipeline, String program);

    /**
     * Recompiles iris shaders from source.
     */
    @ApiStatus.Experimental
    void recompile();

    /**
     * @return The pipeline interface class
     */
    Class<?> getPipelineClass();
}
