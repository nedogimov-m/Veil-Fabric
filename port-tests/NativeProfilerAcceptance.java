package foundry.veil.porttest;

import com.google.gson.GsonBuilder;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.pipeline.*;
import foundry.veil.api.client.render.ext.VeilDebug;
import foundry.veil.api.client.render.gpu.*;
import foundry.veil.api.client.render.profiler.*;
import foundry.veil.impl.client.render.profiler.VeilRenderProfilerImpl;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.resources.Identifier;
import org.joml.Vector4f;

import java.nio.file.*;
import java.util.*;

/** Actual query results, native period conversion, pass replay and failure/disposal ownership. */
public final class NativeProfilerAcceptance implements ClientModInitializer {
    private int ticks;private boolean ran;
    private static void check(boolean condition,String message){if(!condition)throw new AssertionError(message);}
    @Override public void onInitializeClient(){ClientTickEvents.END_CLIENT_TICK.register(client->{
        if(ran||++ticks<5||!client.isGameLoadFinished())return;ran=true;var report=new LinkedHashMap<String,Object>();var device=RenderSystem.getDevice();
        try{
            report.put("backend",device.getDeviceInfo().backendName());report.put("timestampPeriod",device.getDeviceInfo().timestampPeriod());
            check(device.getDeviceInfo().backendName().toLowerCase(Locale.ROOT).contains("vulkan"),"Vulkan required");
            VeilRenderProfilerImpl.bindRuntime(device,()->true);check(VeilRenderProfilerImpl.isSupported(),"Actual native GPU timestamp period must be supported");
            var pipeline=RenderPipeline.builder().withLocation(Identifier.parse("veil:native_profiler_work"))
                    .withVertexShader(Identifier.parse("veil:core/gpu_blit")).withFragmentShader(Identifier.parse("veil:core/gpu_debug"))
                    .withPrimitiveTopology(PrimitiveTopology.TRIANGLES).withCull(false).withDepthStencilState(Optional.empty()).withColorTargetState(ColorTargetState.DEFAULT).build();
            try(var source=new GpuShaderSource(client.getResourceManager());var program=new GpuShaderProgram(device,pipeline,source,Runnable::run);
                var output=GpuFramebuffer.builder("native timestamp work",256,256).color(GpuFormat.RGBA8_UNORM).build(device)){
                VeilRenderProfilerImpl.setEnabled(Set.of("root","root\u001echild"),RenderProfilerCounter.ALL);
                var profiler=VeilRenderProfiler.get();profiler.push("root");VeilDebug.get().pushDebugGroup("timestamp-root");
                var encoder=device.createCommandEncoder();output.clear(encoder,new Vector4f(0),0);
                try(var pass=output.beginPass(encoder)){
                    check(VeilDebug.get().activePasses()==1&&VeilDebug.get().activeGroups().equals(List.of("timestamp-root")),"Actual createRenderPass hook replays CPU debug scope");
                    program.bind(pass);profiler.push("child");for(int i=0;i<64;i++)pass.draw(3,1,0,0);profiler.pop();for(int i=0;i<16;i++)pass.draw(3,1,0,0);
                }
                check(VeilDebug.get().activePasses()==0,"Actual pass close hook restores diagnostic scope");
                try(var pass=output.beginPass(encoder)){program.bind(pass);for(int i=0;i<32;i++)pass.draw(3,1,0,0);}
                profiler.pop();VeilDebug.get().popDebugGroup();
                try(var fence=encoder.createFence()){
                    VeilRenderProfilerImpl.endFrame();check(VeilRenderProfilerImpl.getCounters("",RenderProfilerCounter.GPU_TIME_NANOS).isEmpty(),"Unsubmitted timestamp values are not published or CPU-estimated");
                    encoder.submit();check(fence.awaitCompletion(10_000_000_000L),"Native timing work completed");
                }
                var root=VeilRenderProfilerImpl.getCounters("",RenderProfilerCounter.GPU_TIME_NANOS).getFirst();
                var child=VeilRenderProfilerImpl.getCounters("root",RenderProfilerCounter.GPU_TIME_NANOS).getFirst();
                long total=root.count()[0],nested=child.count()[0];check(total>0&&nested>0&&total>=nested,"Inclusive parent aggregates actual GPU time across passes");
                check(root.getPercentage(RenderProfilerCounter.GPU_TIME_NANOS)==100&&child.getGlobalPercentage(RenderProfilerCounter.GPU_TIME_NANOS)>0,"Timing percentages use actual completed values");
                root.count()[0]=0;check(root.count()[0]==total,"Published history arrays are detached");
                report.put("parentNanos",total);report.put("childNanos",nested);report.put("actualQueriesAndReplay","PASS");
                VeilRenderProfilerImpl.setEnabled(Set.of("standalone"),RenderProfilerCounter.ALL);
                encoder=device.createCommandEncoder();
                try(var pass=output.beginPass(encoder)){
                    try(var scope=VeilRenderProfilerImpl.begin(pass,"standalone")){program.bind(pass);for(int i=0;i<32;i++)pass.draw(3,1,0,0);throw new IllegalStateException("intentional scoped producer failure");}
                    catch(IllegalStateException expected){check(expected.getMessage().contains("intentional"),"Producer exception retained");}
                    for(int i=0;i<300;i++)try(var scope=VeilRenderProfilerImpl.begin(pass,"standalone")){pass.draw(3,1,0,0);}
                }
                try(var fence=encoder.createFence()){VeilRenderProfilerImpl.endFrame();encoder.submit();check(fence.awaitCompletion(10_000_000_000L),"Standalone timing completed");}
                var standalone=VeilRenderProfilerImpl.getCounters("",RenderProfilerCounter.GPU_TIME_NANOS);
                int availabilityPolls=0;
                while((standalone.isEmpty()||!standalone.getFirst().name().equals("standalone"))&&availabilityPolls++<100){
                    java.util.concurrent.locks.LockSupport.parkNanos(1_000_000L);
                    standalone=VeilRenderProfilerImpl.getCounters("",RenderProfilerCounter.GPU_TIME_NANOS);
                }
                report.put("availabilityPolls",availabilityPolls);
                report.put("standaloneResults",standalone);
                check(standalone.size()==1&&standalone.getFirst().name().equals("standalone")&&standalone.getFirst().count()[0]>=0,"Explicit scope closes and publishes after producer failure: "+standalone);
                check(VeilDebug.get().activePasses()==0&&VeilDebug.get().activeGroups().isEmpty(),"All actual diagnostic pass/group ownership restored");
                report.put("explicitFailureScope","PASS");
            }
            VeilRenderProfilerImpl.unbindRuntime(device);check(!VeilRenderProfilerImpl.isSupported(),"Device binding retired");
            VeilRenderProfilerImpl.bindRuntime(device,()->false);check(VeilRenderProfilerImpl.isSupported(),"Capability remains real when viewer disabled");
            VeilRenderProfiler.get().push(()->{throw new AssertionError("Disabled profiler evaluated lazy section");});VeilRenderProfiler.get().pop();
            VeilRenderProfilerImpl.endFrame();VeilRenderProfilerImpl.unbindRuntime(device);report.put("lifecycleAndDisabled","PASS");report.put("checks",3);report.put("status","passed");
        }catch(Throwable failure){failure.printStackTrace();report.put("status","failed");report.put("error",failure.toString());}
        finally{try{VeilRenderProfilerImpl.unbindRuntime(device);Files.writeString(Path.of(System.getProperty("veil.profiler.report")),new GsonBuilder().setPrettyPrinting().create().toJson(report));}catch(Exception failure){throw new RuntimeException(failure);}client.stop();}
    });}
}
