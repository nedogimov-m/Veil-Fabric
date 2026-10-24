import com.google.gson.GsonBuilder;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.device.GpuDevice;
import com.mojang.renderpearl.api.pipeline.*;
import foundry.veil.api.client.render.gpu.*;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.resources.Identifier;
import org.joml.Vector4f;
import java.nio.*;
import java.nio.file.*;
import java.util.*;

/** New mixed attachment ownership/occlusion only; no previous framebuffer groups. */
public final class NativeFramebufferCompositionAcceptance implements ClientModInitializer {
    private int ticks;private boolean ran;
    @Override public void onInitializeClient(){ClientTickEvents.END_CLIENT_TICK.register(client->{
        if(ran||++ticks<5||!client.isGameLoadFinished())return;ran=true;final var report=new LinkedHashMap<String,Object>();final var device=RenderSystem.getDevice();report.put("backend",device.getDeviceInfo().backendName());report.put("device",device.getDeviceInfo().name());
        try {
            final var owner=new TextureTarget("composition native owner",4,2,GpuFormat.RGBA8_UNORM,GpuFormat.D32_FLOAT);
            try(var hdr=GpuFramebuffer.builder("composition owned HDR",4,2).color(GpuFormat.RGBA16_FLOAT).build(device);
                var readback=GpuFramebuffer.builder("composition pixels",4,2).color(GpuFormat.RGBA8_UNORM).build(device);
                var mismatch=GpuFramebuffer.builder("composition mismatched size",2,2).depth(GpuFormat.D32_FLOAT).build(device);
                var blit=new GpuFullscreenPass(device,client.getResourceManager(),Runnable::run,com.mojang.renderpearl.api.textures.FilterMode.NEAREST,null)) {
                final var depth=owner.getDepthTexture();final var depthView=owner.getDepthTextureView();final var color=hdr.color(0).texture();final var colorView=hdr.color(0).view();
                final var combined=GpuFramebuffer.borrowAttachments(device,"owned HDR plus native depth",List.of(colorView),depthView);
                check(combined.color(0).view()==colorView&&combined.depth().view()==depthView,"Composite retains exact actual caller-owned views");
                final var encoder=device.createCommandEncoder();encoder.clearColorTexture(owner.getColorTexture(),new Vector4f(0,1,0,1));encoder.clearDepthTexture(depth,.75);hdr.clear(encoder,new Vector4f(0),0);encoder.submit();
                final var pipeline=RenderPipeline.builder().withLocation(Identifier.parse("veil:native/composition_occlusion"))
                        .withVertexShader(Identifier.parse("veil:core/gpu_blit")).withFragmentShader(Identifier.parse("veil:core/native_composition"))
                        .withPrimitiveTopology(PrimitiveTopology.TRIANGLES).withCull(false).withDepthStencilState(new DepthStencilState(CompareOp.GREATER_THAN,false))
                        .withColorTargetState(new ColorTargetState(Optional.empty(),GpuFormat.RGBA16_FLOAT,ColorTargetState.WRITE_ALL)).build();
                try(var source=new GpuShaderSource(client.getResourceManager());var shader=new GpuShaderProgram(device,pipeline,source,Runnable::run)) {
                    final var commands=device.createCommandEncoder();shader.prepare(commands);try(var pass=combined.beginPass(commands)){shader.bind(pass);pass.draw(3,1,0,0);}blit.blit(commands,colorView,readback.color(0).view());commands.submit();
                }
                final var pixels=read(device,readback.color(0).texture());for(int y=0;y<2;y++)for(int x=0;x<4;x++)for(int channel:new int[]{x<2?0:255,0,0,x<2?0:255})check((pixels.get()&255)==channel,"Borrowed native depth correctly occludes HDR output");
                final var original=read(device,owner.getColorTexture());while(original.hasRemaining())for(int channel:new int[]{0,255,0,255})check((original.get()&255)==channel,"Composite draws preserve the native owner's color");
                final var depths=read(device,depth).order(ByteOrder.nativeOrder());while(depths.hasRemaining())check(Math.abs(depths.getFloat()-.75f)<.00001f,"Read-only borrowed depth pixels remain intact");
                expectFailure(()->combined.resize(8,8));expectFailure(()->GpuFramebuffer.borrowAttachments(device,"bad size",List.of(colorView),mismatch.depth().view()));expectFailure(()->GpuFramebuffer.borrowAttachments(device,"bad color aspect",List.of(depthView),null));
                combined.close();check(!color.isClosed()&&!colorView.isClosed()&&!depth.isClosed()&&!depthView.isClosed(),"Composite closure never disposes either actual owner");
                final var stale=GpuFramebuffer.borrowAttachments(device,"expired owner",List.of(colorView),depthView);owner.destroyBuffers();expectFailure(stale::depth);stale.close();check(!color.isClosed(),"Native-owner disposal expires its borrow while independent HDR survives");expectFailure(()->GpuFramebuffer.borrowAttachments(device,"closed view",List.of(colorView),depthView));
            } finally {owner.destroyBuffers();}
            report.put("status","passed");report.put("checks",List.of("real RGBA16F color plus borrowed native D32 depth view","native reverse-depth occlusion without color/depth copies","caller color and borrowed depth pixels unchanged","dimension/aspect/closed/resize validation and independent ownership"));
        }catch(Throwable failure){failure.printStackTrace();report.put("status","failed");report.put("error",failure.toString());}
        finally {try{Files.writeString(Path.of(System.getProperty("veil.framebuffer.composition.report")),new GsonBuilder().setPrettyPrinting().create().toJson(report));}catch(Exception failure){throw new RuntimeException(failure);}client.stop();}
    });}
    private static ByteBuffer read(GpuDevice device,com.mojang.renderpearl.api.textures.GpuTexture texture) {
        final int size=texture.getWidth(0)*texture.getHeight(0)*texture.getFormat().blockSize();
        try(var buffer=device.createBuffer(()->"new mixed ownership readback",GpuBuffer.USAGE_COPY_DST|GpuBuffer.USAGE_MAP_READ,size)){
            final var encoder=device.createCommandEncoder();encoder.copyTextureToBuffer(texture,buffer,0,()->{},0);try(var fence=encoder.createFence()){
                encoder.submit();check(fence.awaitCompletion(10_000_000_000L),"Mixed attachment readback completed");try(var mapped=buffer.map(true,false)){final var result=ByteBuffer.allocate(size);result.put(mapped.data()).flip();return result;}
            }
        }
    }
    private static void expectFailure(Runnable action){try{action.run();throw new AssertionError("Expected genuine attachment/ownership rejection");}catch(IllegalArgumentException|IllegalStateException expected){}}
    private static void check(boolean value,String message){if(!value)throw new AssertionError(message);}
}
