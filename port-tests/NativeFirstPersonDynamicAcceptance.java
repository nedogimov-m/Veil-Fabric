import com.google.gson.GsonBuilder;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import foundry.veil.api.client.render.dynamicbuffer.DynamicBufferType;
import foundry.veil.api.client.render.gpu.NativeFirstPersonPass;
import foundry.veil.impl.client.render.dynamicbuffer.*;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.resources.Identifier;
import org.joml.Vector4f;
import java.nio.*;
import java.nio.file.*;
import java.util.*;

/** Only the new hand-after-world dynamic-buffer isolation edge. */
public final class NativeFirstPersonDynamicAcceptance implements ClientModInitializer {
    private boolean ran;private int ticks;
    @Override public void onInitializeClient(){ClientTickEvents.END_CLIENT_TICK.register(client->{
        if(ran||++ticks<5||!client.isGameLoadFinished())return;ran=true;var device=RenderSystem.getDevice();var report=new LinkedHashMap<String,Object>();
        report.put("backend",device.getDeviceInfo().backendName());
        var target=new TextureTarget("Hand-after-world caller",4,3,GpuFormat.RGBA8_UNORM,GpuFormat.D32_FLOAT);
        try(var compiler=new VanillaShaderCompiler(device,Runnable::run,Runnable::run);
            var manager=new DynamicBufferManager(device,client.getResourceManager(),4,3,compiler,changes->{});
            var hand=new NativeFirstPersonPass()){
            check(device.getDeviceInfo().backendName().toLowerCase(Locale.ROOT).contains("vulkan"),"Actual Vulkan required");
            manager.setActiveBuffers(Identifier.parse("veil:hand_isolation"),DynamicBufferType.encode(DynamicBufferType.DEBUG));
            NativeDynamicBuffers.bindRuntime(manager);
            try{
                var mainDebug=manager.getBufferTexture(DynamicBufferType.DEBUG);
                var encoder=device.createCommandEncoder();encoder.clearColorTexture(mainDebug.texture(),new Vector4f(.25f,.5f,.75f,1));encoder.submit();
                final GpuTextureView[] borrowed={null};var callbacks=new int[1];
                try(var scope=hand.begin(target,frame->{
                    check(manager.getBufferTexture(DynamicBufferType.DEBUG)==borrowed[0],"First-person post callback must see its independent G-buffer");callbacks[0]++;
                })){
                    borrowed[0]=manager.getBufferTexture(DynamicBufferType.DEBUG);
                    check(borrowed[0]!=mainDebug,"Hand after closed main-world scope must own a fresh G-buffer");
                    assertValues(borrowed[0],0,0,0,0);
                    encoder=device.createCommandEncoder();encoder.clearColorTexture(borrowed[0].texture(),new Vector4f(1,0,1,1));encoder.submit();
                }
                check(callbacks[0]==1&&manager.getBufferTexture(DynamicBufferType.DEBUG)==mainDebug,"Hand completion must restore published world G-buffer");
                assertValues(mainDebug,.25f,.5f,.75f,1);
                check(borrowed[0].isClosed()&&!mainDebug.isClosed(),"Independent hand owners retire after actual submit while world owner stays live");
                report.put("cases",1);report.put("status","passed");
            }finally{NativeDynamicBuffers.flush(manager);NativeDynamicBuffers.unbindRuntime(manager);}
        }catch(Throwable failure){failure.printStackTrace();report.put("status","failed");report.put("error",failure.toString());}
        finally{target.destroyBuffers();}
        try{Files.writeString(Path.of(System.getProperty("veil.firstperson.report")),new GsonBuilder().setPrettyPrinting().create().toJson(report));}catch(Exception failure){throw new RuntimeException(failure);}client.stop();
    });}
    private static void assertValues(GpuTextureView view,float... expected){
        var device=RenderSystem.getDevice();long size=(long)view.getWidth(0)*view.getHeight(0)*8;
        try(var data=device.createBuffer(()->"Hand dynamic payload pixels",GpuBuffer.USAGE_COPY_DST|GpuBuffer.USAGE_MAP_READ,size)){
            var encoder=device.createCommandEncoder();encoder.copyTextureToBuffer(view.texture(),data,0,()->{},0);
            try(var fence=encoder.createFence()){encoder.submit();check(fence.awaitCompletion(10_000_000_000L),"Dynamic hand read timed out");
                try(var mapped=data.map(true,false)){var bytes=mapped.data().order(ByteOrder.nativeOrder());for(int i=0;i<4;i++)check(Math.abs(Float.float16ToFloat(bytes.getShort(i*2))-expected[i])<.0001f,"Hand dynamic payload mismatch component"+i);}
            }
        }
    }
    private static void check(boolean value,String message){if(!value)throw new AssertionError(message);}
}
