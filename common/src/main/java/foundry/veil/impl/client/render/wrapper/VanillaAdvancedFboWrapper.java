package foundry.veil.impl.client.render.wrapper;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.renderpearl.api.device.GpuDevice;
import com.mojang.renderpearl.api.textures.GpuTexture;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import foundry.veil.api.client.render.gpu.GpuFramebuffer;
import java.util.Objects;
import java.util.function.Supplier;
import org.jetbrains.annotations.Nullable;

/** Refreshable typed borrow. Closing it never closes the native target's textures or views. */
public final class VanillaAdvancedFboWrapper implements AutoCloseable {
    private final GpuDevice device;
    private final Supplier<RenderTarget> targets;
    @Nullable private RenderTarget target;
    @Nullable private GpuTextureView color;
    @Nullable private GpuTextureView depth;
    @Nullable private GpuFramebuffer framebuffer;
    private int width,height;
    private boolean closed;

    public VanillaAdvancedFboWrapper(GpuDevice device,Supplier<RenderTarget> targets) {
        this.device=Objects.requireNonNull(device);this.targets=Objects.requireNonNull(targets);
    }

    /** A previous borrow expires on native resize, replacement, destruction or explicit invalidation. */
    public GpuFramebuffer framebuffer() {
        if(closed)throw new IllegalStateException("RenderTarget wrapper is closed");
        final var current=Objects.requireNonNull(targets.get(),"Native render target is unavailable");
        final var currentColor=current.getColorTextureView();final var currentDepth=current.getDepthTextureView();
        if(framebuffer!=null&&!framebuffer.isClosed()&&target==current&&color==currentColor&&depth==currentDepth
                &&width==current.width&&height==current.height)return framebuffer;
        final var replacement=GpuFramebuffer.borrow(device,"Minecraft render target",current);
        final var previous=framebuffer;
        target=current;color=currentColor;depth=currentDepth;width=current.width;height=current.height;framebuffer=replacement;
        if(previous!=null)previous.close();
        return replacement;
    }

    public GpuTexture colorTexture(int index){return framebuffer().color(index).texture();}
    public GpuTextureView colorView(int index){return framebuffer().color(index).view();}
    public @Nullable GpuTexture depthTexture(){final var value=framebuffer();return value.hasDepth()?value.depth().texture():null;}
    public @Nullable GpuTextureView depthView(){final var value=framebuffer();return value.hasDepth()?value.depth().view():null;}
    public void invalidate(){if(framebuffer!=null)framebuffer.close();framebuffer=null;target=null;color=null;depth=null;}
    @Override public void close(){if(closed)return;closed=true;invalidate();}
}
