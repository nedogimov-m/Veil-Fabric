package foundry.veil.impl.client.render.pipeline;

import com.mojang.blaze3d.systems.RenderSystem;
import foundry.veil.Veil;
import foundry.veil.api.client.render.VeilRenderSystem;
import foundry.veil.api.client.render.framebuffer.VeilFramebuffers;
import foundry.veil.api.client.render.gpu.NativeBloomTarget;
import foundry.veil.api.client.render.post.PostPipeline;
import foundry.veil.api.client.render.rendertype.NativeRenderTypeOutput;
import foundry.veil.api.compat.IrisCompat;
import foundry.veil.impl.client.render.rendertype.NativeRenderTypeDraws;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.Identifier;
import org.jetbrains.annotations.Nullable;

/** Source bloom pipeline over genuine HDR color and the actual caller depth; no GL output shard or binds. */
public final class VeilBloomRenderer {
    private static final Identifier BLOOM_PIPELINE=Veil.veilPath("core/bloom");
    private static boolean enabled;
    private static NativeBloomTarget bloom;
    private VeilBloomRenderer() { }

    public static void tryEnable() {
        NativeRenderTypeDraws.registerOutput(VeilFramebuffers.BLOOM,VeilBloomRenderer::acquire);
        final boolean previous=enabled;enabled=getPipeline()!=null;
        if(previous!=enabled) {
            if(enabled)Veil.LOGGER.info("Enabled native bloom pipeline");
            else {Veil.LOGGER.warn("Disabled bloom pipeline due to missing/invalid resources");free();}
        }
    }
    private static @Nullable NativeRenderTypeOutput.Target acquire() {
        if(!enabled||(IrisCompat.INSTANCE!=null&&IrisCompat.INSTANCE.areShadersLoaded()))return null;
        final var main=Minecraft.getInstance().gameRenderer.mainRenderTarget();
        if(bloom==null||bloom.framebuffer().width()!=main.width||bloom.framebuffer().height()!=main.height) {
            final var replacement=new NativeBloomTarget(RenderSystem.getDevice(),main.width,main.height);
            final var previous=bloom;bloom=replacement;
            VeilRenderSystem.renderer().getFramebufferManager().setFramebuffer(VeilFramebuffers.BLOOM,replacement.framebuffer());
            if(previous!=null)previous.close();
        }
        return bloom.acquire(main.getDepthTextureView());
    }
    private static @Nullable PostPipeline getPipeline(){return VeilRenderSystem.renderer().getPostProcessingManager().getPipeline(BLOOM_PIPELINE);}
    public static boolean hasRendered(){return enabled&&bloom!=null&&bloom.hasRendered();}

    /** Call after native render-type submission at END_MAIN (or final null stage), before default post pipelines. */
    public static void flush() {
        if(!hasRendered())return;
        final var current=bloom;final var pipeline=getPipeline();
        if(pipeline==null){enabled=false;free();return;}
        final var profiler=net.minecraft.util.profiling.Profiler.get();profiler.push("bloom");
        try {VeilRenderSystem.renderer().getPostProcessingManager().runPipeline(pipeline);}
        finally {
            try {final var encoder=RenderSystem.getDevice().createCommandEncoder();current.clearRendered(encoder);encoder.submit();}
            finally {profiler.pop();}
        }
    }
    /** Window resize/shutdown retires owners after pending output leases, without changing reload policy. */
    public static void free() {
        if(bloom==null)return;
        VeilRenderSystem.renderer().getFramebufferManager().removeFramebuffer(VeilFramebuffers.BLOOM);
        final var previous=bloom;bloom=null;previous.close();
    }
}
