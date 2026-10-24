package foundry.veil.api.client.render.gpu;

import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.commands.CommandEncoder;
import com.mojang.renderpearl.api.device.GpuDevice;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import foundry.veil.api.client.render.rendertype.NativeRenderTypeOutput;
import org.jetbrains.annotations.Nullable;
import org.joml.Vector4f;
import java.util.List;

/** Owned HDR color with per-draw borrowed depth; pending native submissions retain the actual color owner. */
public final class NativeBloomTarget implements AutoCloseable {
    private final GpuDevice device;
    private final GpuFramebuffer color;
    private int leases;
    private boolean retired,needsClear=true,rendered;
    public NativeBloomTarget(GpuDevice device,int width,int height) {
        this.device=device;color=GpuFramebuffer.builder("Veil bloom HDR",width,height).color(GpuFormat.RGBA16_FLOAT).build(device);
    }
    public GpuFramebuffer framebuffer(){return color;}
    public boolean hasRendered(){return rendered;}
    public NativeRenderTypeOutput.Target acquire(@Nullable GpuTextureView depth) {
        if(retired)throw new IllegalStateException("Bloom target is retired");
        final var framebuffer=GpuFramebuffer.borrowAttachments(device,"Veil bloom with caller depth",List.of(color.color(0).view()),depth);
        leases++;final var lease=new Lease(framebuffer);
        return new NativeRenderTypeOutput.Target(framebuffer,lease::prepare,lease::submitted,lease);
    }
    /** Called once after the bloom pipeline, including its failure path. Never clears borrowed depth. */
    public void clearRendered(CommandEncoder encoder) {color.clear(encoder,new Vector4f(0),0);rendered=false;needsClear=false;}
    @Override public void close(){retired=true;if(leases==0)color.close();}
    private final class Lease implements AutoCloseable {
        private final GpuFramebuffer framebuffer;
        private boolean prepared,submitted,closed;
        Lease(GpuFramebuffer framebuffer){this.framebuffer=framebuffer;}
        void prepare(CommandEncoder encoder){
            if(closed)throw new IllegalStateException("Bloom output lease is closed");
            if(needsClear){color.clear(encoder,new Vector4f(0),0);needsClear=false;}prepared=true;
        }
        void submitted(){if(closed)throw new IllegalStateException("Bloom output lease is closed");if(!prepared)throw new IllegalStateException("Bloom draw was not prepared");submitted=true;rendered=true;}
        @Override public void close(){
            if(closed)return;closed=true;
            if(prepared&&!submitted)needsClear=true;
            framebuffer.close();if(--leases<0)throw new IllegalStateException("Bloom lease underflow");if(retired&&leases==0)color.close();
        }
    }
}
