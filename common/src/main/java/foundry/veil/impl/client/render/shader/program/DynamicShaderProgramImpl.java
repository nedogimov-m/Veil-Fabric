package foundry.veil.impl.client.render.shader.program;

import com.mojang.renderpearl.api.device.GpuDevice;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import com.mojang.renderpearl.api.pipeline.ShaderSource;
import foundry.veil.api.client.render.gpu.GpuShaderProgram;
import org.jetbrains.annotations.ApiStatus;

import java.util.Objects;
import java.util.concurrent.Executor;

/**
 * An editable native program. Sources include all actual stages, draw state and typed bindings.
 * Inherited reload installs only a successful compilation; queued draws retain their old program owner.
 */
@ApiStatus.Internal
public final class DynamicShaderProgramImpl extends GpuShaderProgram {
    private final Runnable onFree;
    private boolean retired;

    public DynamicShaderProgramImpl(GpuDevice device, RenderPipeline pipeline, ShaderSource source, Executor workers, Runnable onFree) {
        super(device, pipeline, checkedSource(source, onFree), workers);
        this.onFree = onFree;
    }
    private static ShaderSource checkedSource(ShaderSource source, Runnable onFree) {
        Objects.requireNonNull(onFree); return Objects.requireNonNull(source);
    }
    /** The callback removes this owner from its registry once; retained draws still defer actual GPU disposal. */
    @Override public void close() {
        if (this.retired) return;
        this.retired = true;
        try { super.close(); } finally { this.onFree.run(); }
    }
}
