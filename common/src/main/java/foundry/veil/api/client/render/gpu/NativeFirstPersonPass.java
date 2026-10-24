package foundry.veil.api.client.render.gpu;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.GpuFormat;
import foundry.veil.ext.RenderTargetExtension;
import foundry.veil.impl.client.render.dynamicbuffer.NativeDynamicBuffers;
import java.util.Objects;
import java.util.function.Consumer;

/** Native hand target with independent depth, preserved scene color, and transactional owner restoration. */
public final class NativeFirstPersonPass implements AutoCloseable {
    private GpuFramebuffer target;
    private boolean rendering,closed;
    public Scope begin(RenderTarget main,Consumer<GpuFramebuffer> complete){
        RenderSystem.assertOnRenderThread();
        if(closed||rendering)throw new IllegalStateException("First-person target is closed or already rendering");
        Objects.requireNonNull(complete);
        final var device=RenderSystem.getDevice();
        final var source=GpuFramebuffer.borrow(device,"Veil first-person caller",main);
        try{
            var format=source.color(0).texture().getFormat();
            var depth=source.depth().texture().getFormat();
            if(target==null||target.width()!=source.width()||target.height()!=source.height()
                    ||target.color(0).texture().getFormat()!=format||target.depth().texture().getFormat()!=depth){
                var replacement=GpuFramebuffer.builder("Veil first-person scene",source.width(),source.height()).color(format).depth(depth).build(device);
                if(target!=null)target.close();target=replacement;
            }
            var encoder=device.createCommandEncoder();
            source.copyColorTo(encoder,0,target,0);encoder.clearDepthTexture(target.depth().texture(),0);encoder.submit();
            var extension=(RenderTargetExtension)main;
            var previous=extension.veil$getWrapper();
            var dynamic=NativeDynamicBuffers.beginIndependentWorld(target.color(0).view(),target.depth().view());
            try{
                extension.veil$setWrapper(target);rendering=true;
                return new Scope(source,extension,previous,dynamic,complete);
            }catch(RuntimeException|Error failure){extension.veil$setWrapper(previous);dynamic.close();throw failure;}
        }catch(RuntimeException|Error failure){source.close();throw failure;}
    }
    public final class Scope implements AutoCloseable {
        private final GpuFramebuffer source,previous;
        private final RenderTargetExtension extension;
        private final NativeDynamicBuffers.Scope dynamic;
        private final Consumer<GpuFramebuffer> complete;
        private boolean done;
        private Scope(GpuFramebuffer source,RenderTargetExtension extension,GpuFramebuffer previous,NativeDynamicBuffers.Scope dynamic,Consumer<GpuFramebuffer> complete){
            this.source=source;this.extension=extension;this.previous=previous;this.dynamic=dynamic;this.complete=complete;
        }
        public GpuFramebuffer framebuffer(){return target;}
        @Override public void close(){
            if(done)return;done=true;
            try{
                extension.veil$setWrapper(previous);
                var encoder=RenderSystem.getDevice().createCommandEncoder();target.copyColorTo(encoder,0,source,0);encoder.submit();
                complete.accept(target);
            }finally{
                try{dynamic.close();}finally{source.close();rendering=false;}
            }
        }
    }
    @Override public void close(){
        RenderSystem.assertOnRenderThread();if(closed)return;if(rendering)throw new IllegalStateException("Cannot close an active first-person pass");
        closed=true;if(target!=null){target.close();target=null;}
    }
}
