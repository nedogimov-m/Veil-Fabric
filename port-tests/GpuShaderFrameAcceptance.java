package foundry.veil.porttest;

import com.google.gson.GsonBuilder;
import com.mojang.blaze3d.pipeline.PipelineCache;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.commands.RenderPassDescriptor;
import com.mojang.renderpearl.api.device.GpuDevice;
import com.mojang.renderpearl.api.pipeline.*;
import foundry.veil.api.client.render.gpu.*;
import foundry.veil.api.client.render.shader.*;
import foundry.veil.api.client.render.shader.block.ShaderBlock;
import foundry.veil.api.client.render.shader.texture.ShaderTextureSource;
import foundry.veil.impl.client.render.pipeline.VeilShaderBlockState;
import foundry.veil.impl.client.render.shader.injection.*;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.*;
import org.joml.Vector4f;

import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

/** Test-only actual GameRenderer frame preparation, detached named block values and exceptional scope cleanup. */
public final class GpuShaderFrameAcceptance implements ClientModInitializer {
    private boolean ran;private int ticks;private static State state;
    @Override public void onInitializeClient(){ClientTickEvents.END_CLIENT_TICK.register(client->{
        if(this.ran||++this.ticks<5||!client.isGameLoadFinished())return;this.ran=true;
        var report=new LinkedHashMap<String,Object>();var checks=new ArrayList<String>();report.put("checks",checks);
        try{var device=RenderSystem.getDevice();report.put("backend",device.getDeviceInfo().backendName());report.put("device",device.getDeviceInfo().name());
            check(device.getDeviceInfo().backendName().toLowerCase(Locale.ROOT).contains("vulkan"),"Real Vulkan required");run(client,device,checks);report.put("cases",checks.size());report.put("status","passed");
        }catch(Throwable failure){failure.printStackTrace();report.put("status","failed");report.put("error",failure.toString());}
        finally{state=null;try{Files.writeString(Path.of(System.getProperty("veil.frames.report")),new GsonBuilder().setPrettyPrinting().create().toJson(report)+"\n");}
            catch(Exception failure){throw new RuntimeException(failure);}client.stop();}
    });}
    public static boolean probe(){State current=state;if(current==null)return false;current.draw();return true;}
    private static void run(Minecraft client,GpuDevice device,List<String> checks)throws Exception {
        var definitions=new ShaderPreDefinitions();var injections=new ShaderInjectionManager();reload(injections,client.getResourceManager());
        try(var programs=new ShaderManager(device,ShaderManager.PROGRAM_SET,definitions);var source=new GpuShaderSource(client.getResourceManager());var cache=new PipelineCache(device,source)) {
            reload(programs,client.getResourceManager());
            try(var service=new NativeShaderInjectionService(injections,programs::getDefinitions,definitions);
                var output=GpuFramebuffer.builder("actual native shader frame output",2,2).color(GpuFormat.RGBA8_UNORM).depth(GpuFormat.D32_FLOAT).build(device);
                var input=GpuFramebuffer.builder("borrowed frame sampler",2,2).color(GpuFormat.RGBA8_UNORM).build(device);
                var borrowedSampler=device.createSampler(com.mojang.renderpearl.api.textures.AddressMode.CLAMP_TO_EDGE,com.mojang.renderpearl.api.textures.AddressMode.CLAMP_TO_EDGE,
                        com.mojang.renderpearl.api.textures.FilterMode.NEAREST,com.mojang.renderpearl.api.textures.FilterMode.NEAREST,1,OptionalDouble.empty())) {
                NativePipelineBridge.bindRuntime(definitions,service,List.of());
                var original=RenderPipeline.builder().withLocation(Identifier.parse("veil_test:native_frame"))
                        .withVertexShader(Identifier.parse("veil:core/gpu_injection_vertex")).withFragmentShader(Identifier.parse("veil:core/gpu_frame"))
                        .withPrimitiveTopology(PrimitiveTopology.TRIANGLES).withCull(false).withDepthStencilState(new DepthStencilState(CompareOp.GREATER_THAN_OR_EQUAL,true))
                        .withBindGroupLayout(BindGroupLayout.builder().withUniform("Projection",UniformType.UNIFORM_BUFFER).build())
                        .withColorTargetState(new ColorTargetState(Optional.of(BlendFunction.TRANSLUCENT),GpuFormat.RGBA8_UNORM,ColorTargetState.WRITE_ALL)).build();
                var compiled=cache.get(original);var blockState=new VeilShaderBlockState();var captures=new AtomicInteger();var textureResolutions=new AtomicInteger();
                ShaderBlock<Vector4f> tint=ShaderBlock.withSize(device,ShaderBlock.BufferBinding.UNIFORM,16,(value,bytes)->{for(int c=0;c<4;c++)bytes.putFloat(c*4,value.get(c));});
                ShaderBlock<Object> forbidden=ShaderBlock.withSize(device,ShaderBlock.BufferBinding.UNIFORM,16,(value,bytes)->{throw new AssertionError("Inherited native Projection was captured at frame head");});
                blockState.bind("Projection",forbidden);tint.set(new Vector4f(.5f,1,1,.5f));blockState.bind("ReplacementTint",tint);
                var encoder=device.createCommandEncoder();input.clear(encoder,new Vector4f(.8f,.4f,.2f,1),0);output.clear(encoder,new Vector4f(0,.25f,.5f,1),0);encoder.submit();
                var context=new ShaderTextureSource.Context(){
                    @Override public GpuFramebuffer getFramebuffer(Identifier name){return input;}
                    @Override public com.mojang.renderpearl.api.textures.GpuTextureView getTexture(Identifier name){check(state==null||!state.inDraw,"Frame texture resolved inside draw/pass");textureResolutions.incrementAndGet();return input.color(0).view();}
                    @Override public com.mojang.renderpearl.api.textures.GpuSampler getSampler(Identifier name){return borrowedSampler;}
                };
                try(var frames=new NativeShaderInjectionFrames(service,device,context,names->{
                    check(names.equals(Set.of("ReplacementTint")),"Frame captured unrelated/inherited native blocks: "+names);captures.incrementAndGet();return blockState.snapshot(names);
                })) {
                    NativeShaderInjectionFrames.bindRuntime(frames);
                    state=new State(device,output,compiled,tint);client.gameRenderer.render();state=null;
                    check(captures.get()==1,"Actual GameRenderer.render did not enter exactly one shader frame scope");
                    // The source block was freed inside the real frame, after CPU capture and before the native draw.
                    NativePipelineBridge.flush();assertPixels(device,output,13,61,102,255);
                    boolean unscoped=false;
                    try(var pass=device.createCommandEncoder().createRenderPass(RenderPassDescriptor.builder(()->"unscoped replacement must be rejected").withColorAttachment(output.color(0).view()).withDepthAttachment(output.depth().view()).build())) {
                        try{pass.setPipeline(compiled);}catch(IllegalStateException expected){unscoped=expected.getMessage().contains("Prepare native replacement resources");}
                    }
                    check(unscoped,"Actual native frame scope failed to pop after GameRenderer.render returned");
                    checks.add("actual zero-argument GameRenderer.render wrapper prepares named typed block snapshots and JSON samplers before passes; original Projection is excluded, source block mutation/free before draw leaves exact detached native pixels, and returned frame scope is popped");
                    ShaderBlock<Vector4f> second=ShaderBlock.withSize(device,ShaderBlock.BufferBinding.UNIFORM,16,(value,bytes)->{for(int c=0;c<4;c++)bytes.putFloat(c*4,value.get(c));});
                    second.set(new Vector4f(1,0,0,1));blockState.bind("ReplacementTint",second);
                    state=new State(device,output,compiled,second);state.fail=true;boolean failed=false;
                    try{client.gameRenderer.render();}catch(IllegalStateException expected){failed=expected.getMessage().equals("deliberate native frame body failure");}finally{state=null;}
                    check(failed,"Native frame fixture did not throw its intended body exception");
                    var recovery=ShaderBlock.<Vector4f>withSize(device,ShaderBlock.BufferBinding.UNIFORM,16,(value,bytes)->{for(int c=0;c<4;c++)bytes.putFloat(c*4,value.get(c));});
                    recovery.set(new Vector4f(1,0,0,1));blockState.bind("ReplacementTint",recovery);
                    encoder=device.createCommandEncoder();output.clear(encoder,new Vector4f(0),0);encoder.submit();
                    state=new State(device,output,compiled,recovery);client.gameRenderer.render();state=null;NativePipelineBridge.flush();assertPixels(device,output,51,0,0,128);
                    check(captures.get()==3&&textureResolutions.get()==6,"Frame preparation did not run independently for successful, exceptional and next actual frames");
                    check(!input.isClosed()&&!borrowedSampler.isClosed(),"Frame helper closed borrowed texture/sampler resources");
                    second.free();forbidden.free();
                    checks.add("exception from real native frame body closes exact prepared scope; a following actual GameRenderer frame captures new values and renders successfully while borrowed inputs survive");
                }finally{state=null;blockState.clear();NativePipelineBridge.flush();NativePipelineBridge.unbindRuntime();}
            }
        }
    }
    private static final class State {
        final GpuDevice device;final GpuFramebuffer output;final CompiledRenderPipeline pipeline;final ShaderBlock<Vector4f> source;boolean inDraw,fail;
        State(GpuDevice device,GpuFramebuffer output,CompiledRenderPipeline pipeline,ShaderBlock<Vector4f> source){this.device=device;this.output=output;this.pipeline=pipeline;this.source=source;}
        void draw(){if(this.fail)throw new IllegalStateException("deliberate native frame body failure");this.inDraw=true;
            this.source.set(new Vector4f(0));this.source.free();
            try(var pass=this.device.createCommandEncoder().createRenderPass(RenderPassDescriptor.builder(()->"actual frame replacement content").withColorAttachment(this.output.color(0).view()).withDepthAttachment(this.output.depth().view()).build())){
                pass.setPipeline(this.pipeline);pass.setUniform("Projection",RenderSystem.getProjectionMatrixBuffer());pass.draw(3,1,0,0);
            }finally{this.inDraw=false;}
        }
    }
    private static void reload(PreparableReloadListener listener,ResourceManager resources){var state=new PreparableReloadListener.SharedState(resources);
        var barrier=new PreparableReloadListener.PreparationBarrier(){@Override public<T>java.util.concurrent.CompletableFuture<T>wait(T value){return java.util.concurrent.CompletableFuture.completedFuture(value);}};
        listener.reload(state,Runnable::run,barrier,Runnable::run).join();}
    private static void assertPixels(GpuDevice device,GpuFramebuffer output,int...expected){try(var read=device.createBuffer(()->"native frame pixel readback",GpuBuffer.USAGE_COPY_DST|GpuBuffer.USAGE_MAP_READ,16)){
        var encoder=device.createCommandEncoder();encoder.copyTextureToBuffer(output.color(0).texture(),read,0,()->{},0);try(var fence=encoder.createFence()){encoder.submit();check(fence.awaitCompletion(10_000_000_000L),"Native frame readback timed out");
            try(var mapped=read.map(true,false)){for(int i=0;i<16;i++)check(Math.abs((mapped.data().get(i)&255)-expected[i%4])<=2,"Frame pixel component "+i+" was "+(mapped.data().get(i)&255)+", expected "+expected[i%4]);}
        }}}
    private static void check(boolean value,String message){if(!value)throw new AssertionError(message);}
}
