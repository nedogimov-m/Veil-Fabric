package foundry.veil.fabric.compat.iris;

import foundry.veil.api.compat.IrisCompat;
import foundry.veil.ext.iris.IrisRenderTargetExtension;
import foundry.veil.fabric.mixin.compat.iris.IrisRenderingPipelineAccessor;
import it.unimi.dsi.fastutil.objects.Object2ObjectArrayMap;
import it.unimi.dsi.fastutil.objects.Object2ObjectMaps;
import net.irisshaders.iris.Iris;
import net.irisshaders.iris.pipeline.WorldRenderingPipeline;
import net.irisshaders.iris.targets.RenderTargets;
import com.mojang.renderpearl.backend.opengl.GlProgram;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import net.irisshaders.iris.api.v0.IrisApi;
import net.irisshaders.iris.api.v0.IrisProgram;
import net.irisshaders.iris.api.v0.IrisShadowProgram;

import java.util.Collections;
import java.util.Map;
import java.util.Set;

public class VeilFabricIrisCompat implements IrisCompat {

    @Override
    public Set<GlProgram> getLoadedShaders() {
        WorldRenderingPipeline pipeline = Iris.getPipelineManager().getPipelineNullable();
        if (pipeline instanceof IrisRenderingPipelineAccessor accessor) {
            return Set.copyOf(accessor.getLoadedShaders());
        }
        return Collections.emptySet();
    }

    @Override
    public Map<String, IrisRenderTargetExtension> getRenderTargets() {
        WorldRenderingPipeline pipeline = Iris.getPipelineManager().getPipelineNullable();
        if (pipeline instanceof IrisRenderingPipelineAccessor accessor) {
            RenderTargets renderTargets = accessor.getRenderTargets();
            Object2ObjectArrayMap<String, IrisRenderTargetExtension> extensions = new Object2ObjectArrayMap<>(renderTargets.getRenderTargetCount());
            for (int i = 0; i < renderTargets.getRenderTargetCount(); i++) {
                IrisRenderTargetExtension ext = (IrisRenderTargetExtension) renderTargets.get(i);
                if (ext != null) {
                    extensions.put(ext.veil$getName(), ext);
                }
            }
            return extensions;
        }
        return Object2ObjectMaps.emptyMap();
    }

    @Override
    public Flags getFlags() {
        IrisApi api = IrisApi.getInstance();
        return new Flags(api.isShaderPackInUse(), api.isRenderingShadowPass(), api.isReverseZDuringShaders());
    }

    @Override public void assignPipeline(RenderPipeline pipeline, String program) {
        IrisApi.getInstance().assignPipeline(pipeline, IrisProgram.valueOf(program));
    }
    @Override public void assignShadowPipeline(RenderPipeline pipeline, String program) {
        IrisApi.getInstance().assignPipelineShadow(pipeline, IrisShadowProgram.valueOf(program));
    }

    @Override
    public void recompile() {
        try { Iris.reload(); } catch (java.io.IOException failure) { throw new java.io.UncheckedIOException("Cannot reload the actual Iris shader pack", failure); }
    }

    @Override
    public Class<?> getPipelineClass() {
        return WorldRenderingPipeline.class;
    }
}
