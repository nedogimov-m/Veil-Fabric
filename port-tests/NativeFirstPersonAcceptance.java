import com.google.gson.GsonBuilder;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import foundry.veil.api.client.render.gpu.*;
import foundry.veil.ext.RenderTargetExtension;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import org.joml.Vector4f;
import java.nio.*;
import java.nio.file.*;
import java.util.*;

/** Only new first-person target composition/nesting/recovery; no full renderer bootstrap. */
public final class NativeFirstPersonAcceptance implements ClientModInitializer {
    private int ticks;private boolean ran;
    @Override public void onInitializeClient(){ClientTickEvents.END_CLIENT_TICK.register(client->{
        if(ran||++ticks<5||!client.isGameLoadFinished())return;ran=true;
        var report=new LinkedHashMap<String,Object>();var device=RenderSystem.getDevice();report.put("backend",device.getDeviceInfo().backendName());
        var main=new TextureTarget("First-person caller",4,3,GpuFormat.RGBA8_UNORM,GpuFormat.D32_FLOAT);
        try(var pass=new NativeFirstPersonPass();var nested=new NativeFirstPersonPass();var original=GpuFramebuffer.borrow(device,"Caller handles",main)){
            check(device.getDeviceInfo().backendName().toLowerCase(Locale.ROOT).contains("vulkan"),"Actual Vulkan required");
            clear(original,new Vector4f(.2f,.4f,.6f,.8f),.75);var extension=(RenderTargetExtension)main;
            var color=original.color(0).view();var depth=original.depth().view();var calls=new int[1];
            try(var scope=pass.begin(main,target->{
                check(extension.veil$getWrapper()==null&&main.getColorTextureView()==color&&main.getDepthTextureView()==depth,"Completion must see restored native caller");calls[0]++;
            })){
                check(main.getColorTextureView()==scope.framebuffer().color(0).view(),"Actual native render target must redirect to hand color");
                check(main.getDepthTextureView()!=depth,"Hand must own independent native depth");
                assertColor(scope.framebuffer(),51,102,153,204);check(readDepth(scope.framebuffer())==0,"Hand clear depth must be native reversed zero");
                clear(scope.framebuffer(),new Vector4f(.8f,.4f,.2f,1),.5);
            }
            check(calls[0]==1,"Completion must run exactly once");assertColor(original,204,102,51,255);check(readDepth(original)==.75f,"Caller world depth must survive hand rendering");
            try(var outer=pass.begin(main,target->{})){
                clear(outer.framebuffer(),new Vector4f(1,0,0,1),.6);
                try(var inner=nested.begin(main,target->{})){
                    check(main.getDepthTextureView()==inner.framebuffer().depth().view(),"Nested hand must have its own owner");
                    clear(inner.framebuffer(),new Vector4f(0,1,0,1),.4);
                }
                check(extension.veil$getWrapper()==outer.framebuffer(),"Nested completion must restore outer native views");
                assertColor(outer.framebuffer(),0,255,0,255);check(readDepth(outer.framebuffer())==.6f,"Nested scope must not overwrite outer depth");
            }
            boolean failed=false;
            try(var scope=pass.begin(main,target->{throw new IllegalStateException("deliberate completion failure");})){clear(scope.framebuffer(),new Vector4f(0,0,1,1),.3);}
            catch(IllegalStateException expected){failed=true;}
            check(failed&&extension.veil$getWrapper()==null,"Completion failure must still restore real native ownership");
            try(var scope=pass.begin(main,target->{})){assertColor(scope.framebuffer(),0,0,255,255);}
            check(!color.isClosed()&&!depth.isClosed(),"Caller resources remain borrowed");
            report.put("cases",3);report.put("status","passed");
        }catch(Throwable failure){failure.printStackTrace();report.put("status","failed");report.put("error",failure.toString());}
        finally{main.destroyBuffers();}
        try{Files.writeString(Path.of(System.getProperty("veil.firstperson.report")),new GsonBuilder().setPrettyPrinting().create().toJson(report));}catch(Exception failure){throw new RuntimeException(failure);}client.stop();
    });}
    private static void clear(GpuFramebuffer target,Vector4f color,double depth){var encoder=RenderSystem.getDevice().createCommandEncoder();target.clear(encoder,color,depth);encoder.submit();}
    private static byte[] read(GpuFramebuffer target,boolean depth){
        var texture=depth?target.depth().texture():target.color(0).texture();int size=target.width()*target.height()*4;var device=RenderSystem.getDevice();
        try(var data=device.createBuffer(()->"First-person actual pixels",GpuBuffer.USAGE_COPY_DST|GpuBuffer.USAGE_MAP_READ,size)){
            var encoder=device.createCommandEncoder();encoder.copyTextureToBuffer(texture,data,0,()->{},0);
            try(var fence=encoder.createFence()){encoder.submit();check(fence.awaitCompletion(10_000_000_000L),"First-person pixels timed out");
                try(var mapped=data.map(true,false)){byte[] bytes=new byte[size];mapped.data().get(bytes);return bytes;}
            }
        }
    }
    private static float readDepth(GpuFramebuffer target){return ByteBuffer.wrap(read(target,true)).order(ByteOrder.nativeOrder()).getFloat();}
    private static void assertColor(GpuFramebuffer target,int... expected){var values=read(target,false);for(int i=0;i<values.length;i++)check(Math.abs((values[i]&255)-expected[i%4])<=1,"First-person pixel mismatch at"+i);}
    private static void check(boolean value,String message){if(!value)throw new AssertionError(message);}
}
