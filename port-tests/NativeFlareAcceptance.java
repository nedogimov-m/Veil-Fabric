package foundry.veil.porttest;

import com.google.gson.GsonBuilder;
import com.mojang.blaze3d.ProjectionType;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.*;
import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.pipeline.*;
import foundry.veil.api.client.render.framebuffer.FramebufferManager;
import foundry.veil.api.client.render.gpu.*;
import foundry.veil.api.client.render.rendertype.*;
import foundry.veil.api.client.render.shader.*;
import foundry.veil.api.client.render.texture.TextureFilter;
import foundry.veil.api.flare.data.model.*;
import foundry.veil.impl.client.render.rendertype.NativeRenderTypeDraws;
import foundry.veil.impl.client.render.shader.injection.NativeShaderViews;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.ProjectionMatrixBuffer;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.PreparableReloadListener;
import org.joml.*;

import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

/** Native shell layout/retention and exact managed material/texture/transform/layer submission pixels. */
public final class NativeFlareAcceptance implements ClientModInitializer {
    private boolean ran; private int ticks;
    private static final Identifier SHADER = Identifier.parse("veil:gpu_flare_material");
    private static final GpuUniformBuffer.Layout MATERIAL=GpuUniformBuffer.layoutBuilder().field("Tint",GpuUniformBuffer.ValueType.VEC4).build();
    private static void check(boolean condition,String message){if(!condition)throw new AssertionError(message);}
    @Override public void onInitializeClient(){ClientTickEvents.END_CLIENT_TICK.register(client->{
        if(ran||++ticks<5||!client.isGameLoadFinished())return;ran=true;var report=new LinkedHashMap<String,Object>();
        try{report.put("backend",RenderSystem.getDevice().getDeviceInfo().backendName());
            check(report.get("backend").toString().toLowerCase(Locale.ROOT).contains("vulkan"),"Vulkan required");
            cpu();report.put("immutableLayouts","PASS");draw(client);report.put("retainedMaterialTextureTransform","PASS");report.put("layerAndNestedCapture","PASS");report.put("status","passed");report.put("checks",3);
        }catch(Throwable failure){failure.printStackTrace();report.put("status","failed");report.put("error",failure.toString());}
        finally{try{Files.writeString(Path.of(System.getProperty("veil.flare.report")),new GsonBuilder().setPrettyPrinting().create().toJson(report));}catch(Exception failure){throw new RuntimeException(failure);}client.stop();}
    });}
    private static FlareBakedQuad quad(){return new FlareBakedQuad(new float[]{
        -.5f,-1,.5f,0,0,1,0,0, .5f,-1,.5f,0,0,1,1,0, .5f,1,.5f,0,0,1,1,1, -.5f,1,.5f,0,0,1,0,1});}
    private static void cpu(){
        float[] mutable=quad().vertexData();var immutable=new FlareBakedQuad(mutable);mutable[0]=999;
        var list=new ArrayList<FlareBakedQuad>();list.add(immutable);var shell=new SimpleBakedShell(list);list.clear();
        check(shell.getQuads().size()==1&&immutable.vertexData()[0]==-.5f,"Constructor data/list detached");
        immutable.vertexData()[0]=555;check(immutable.vertexData()[0]==-.5f,"Returned vertex data detached");
        for(var format:List.of(DefaultVertexFormat.BLOCK,DefaultVertexFormat.ENTITY))try(var bytes=new ByteBufferBuilder(1024)){
            var builder=new BufferBuilder(bytes,PrimitiveTopology.QUADS,format);immutable.putBakedQuadInto(builder,format);
            try(var mesh=builder.buildOrThrow()){check(mesh.drawState().vertexCount()==4&&mesh.vertexBuffer().remaining()==4*format.getVertexSize(),"Actual native format fully populated");}
        }
        check(!DefaultVertexFormat.BLOCK.contains("Normal")&&DefaultVertexFormat.ENTITY.contains("Normal"),"Actual normal-bearing format selected explicitly");shell.free();
    }
    private static RenderType type(String name,boolean blend){return VeilRenderType.create("veil:flare_"+name,DefaultVertexFormat.ENTITY,PrimitiveTopology.QUADS,256,false,false,false,
        new VeilRenderTypeBuilder().shaderState(SHADER,true).standardUniforms(false).depthTestState(CompareOp.ALWAYS_PASS)
        .cullState(foundry.veil.api.client.render.rendertype.layer.CullLayer.CullFace.NONE)
        .transparencyState(blend?Optional.of(BlendFunction.TRANSLUCENT):Optional.empty()).create());}
    private static void configure(GpuShaderProgram p,GpuFramebuffer white,float r,float g,float b,float a){
        if(p.getUniformBlock("FlareMaterial")==null)p.createUniformBlock("FlareMaterial",MATERIAL);
        p.getUniformBlock("FlareMaterial").setVector("Tint",r,g,b,a);p.setTexture("MaterialTex",white.color(0).view(),TextureFilter.CLAMP);
    }
    private static void draw(Minecraft client){var device=RenderSystem.getDevice();var manager=new FramebufferManager(device,8,4);
        try(var shaders=new ShaderManager(device,ShaderManager.PROGRAM_SET,new ShaderPreDefinitions());
            var output=GpuFramebuffer.builder("Flare detached output",8,4).color(GpuFormat.RGBA8_UNORM).build(device);
            var nested=GpuFramebuffer.builder("Flare nested output",8,4).color(GpuFormat.RGBA8_UNORM).build(device);
            var white=GpuFramebuffer.builder("Flare sampler white",1,1).color(GpuFormat.RGBA8_UNORM).build(device);
            var black=GpuFramebuffer.builder("Flare sampler black",1,1).color(GpuFormat.RGBA8_UNORM).build(device);
            var projection=new ProjectionMatrixBuffer("Flare fixture projection")){
            reload(shaders,client);VeilRenderType.bindRuntime(shaders,manager,Runnable::run);
            var previous=RenderSystem.getProjectionMatrixBuffer();var previousType=RenderSystem.getProjectionType();
            RenderSystem.setProjectionMatrix(projection.getBuffer(new Matrix4f()),ProjectionType.ORTHOGRAPHIC);
            var solid=type("solid",false);var a=type("a",true);var b=type("b",true);var layered=VeilRenderType.layered(a,b);
            var debug=VeilRenderType.create("veil:actual_shell_debug",DefaultVertexFormat.ENTITY,PrimitiveTopology.QUADS,256,false,false,false,
                    new VeilRenderTypeBuilder().shaderState(Identifier.parse("veil:debug/shell"),true).standardUniforms(false).depthTestState(CompareOp.ALWAYS_PASS)
                            .cullState(foundry.veil.api.client.render.rendertype.layer.CullLayer.CullFace.NONE).create());
            try{
                var encoder=device.createCommandEncoder();output.clear(encoder,new Vector4f(0),0);nested.clear(encoder,new Vector4f(0),0);white.clear(encoder,new Vector4f(1),0);black.clear(encoder,new Vector4f(0,0,1,1),0);encoder.submit();
                var shell=new SimpleBakedShell(List.of(quad()));var mesh=shell.getVertexArray(DefaultVertexFormat.ENTITY);var source=mesh.vertexBuffer(0);
                var modelViewBefore=RenderSystem.getModelViewMatrixCopy();
                try(var scope=NativeShaderViews.begin(new NativeShaderViews.View(NativeShaderViews.Kind.SCENE,new Matrix4f(),new Matrix4f(),23,5,7,0));var capture=NativeRenderTypeDraws.beginCapture(output)){
                    check(NativeShaderViews.currentView().orElseThrow().x()==23,"Native view origin exposed to captured model properties");
                    NativeRenderTypeDraws.captureMesh(solid,mesh,new Matrix4f().translation(-.5f,0,0),p->configure(p,white,1,0,0,1));
                    try(var inner=NativeRenderTypeDraws.beginCapture(nested)){
                        NativeRenderTypeDraws.captureMesh(solid,mesh,new Matrix4f().scaling(2,1,1),p->configure(p,white,0,1,0,1));
                        inner.submit(device.createCommandEncoder());
                    }
                    GpuShaderProgram[] changed = {null};
                    NativeRenderTypeDraws.captureMesh(solid,mesh,new Matrix4f().translation(.5f,0,0),p->{configure(p,black,1,1,1,1);changed[0]=p;});
                    configure(changed[0],white,0,1,0,1); // Mutate both owned properties and sampler after capture.
                    shell.free();check(!source.isClosed(),"Captured mesh retains actual native geometry after shell disposal");
                    reload(shaders,client);capture.submit(device.createCommandEncoder());
                }
                check(source.isClosed(),"Submission closes retired source geometry after final lease");
                check(RenderSystem.getModelViewMatrixCopy().equals(modelViewBefore),"Explicit draw capture never changes caller modelview");
                check(NativeShaderViews.currentView().isEmpty(),"Native view scope restored");
                pixels(output,1,1,255,0,0,255);pixels(output,6,1,0,0,255,255);pixels(nested,2,1,0,255,0,255);
                shell=new SimpleBakedShell(List.of(quad()));mesh=shell.getVertexArray(DefaultVertexFormat.ENTITY);
                try(var capture=NativeRenderTypeDraws.beginCapture(nested)){
                    NativeRenderTypeDraws.captureMesh(debug,mesh,new Matrix4f().scaling(2,1,1),p->{});
                    shell.free();capture.submit(device.createCommandEncoder());
                }
                pixels(nested,3,1,0,0,255,255);
                shell=new SimpleBakedShell(List.of(quad()));mesh=shell.getVertexArray(DefaultVertexFormat.ENTITY);source=mesh.vertexBuffer(0);AtomicInteger layers=new AtomicInteger();
                encoder=device.createCommandEncoder();output.clear(encoder,new Vector4f(0),0);encoder.submit();
                try(var capture=NativeRenderTypeDraws.beginCapture(output)){
                    NativeRenderTypeDraws.captureMesh(layered,mesh,new Matrix4f().scaling(2,1,1),p->{int index=layers.getAndIncrement();configure(p,white,index==0?1:0,0,index==1?1:0,.5f);});
                    check(layers.get()==2,"Exactly one material capture per ordered native layer");
                    shell.free();capture.submit(device.createCommandEncoder());
                }
                pixels(output,3,1,64,0,128,191);check(source.isClosed(),"All layered mesh leases released");
                shell=new SimpleBakedShell(List.of(quad()));mesh=shell.getVertexArray(DefaultVertexFormat.ENTITY);source=mesh.vertexBuffer(0);
                try(var capture=NativeRenderTypeDraws.beginCapture(output)){
                    AtomicInteger attempts=new AtomicInteger();
                    try{NativeRenderTypeDraws.captureMesh(layered,mesh,new Matrix4f(),p->{if(attempts.getAndIncrement()==1)throw new IllegalStateException("intentional second-layer material failure");configure(p,white,1,0,0,1);});throw new AssertionError("Failure must propagate");}catch(IllegalStateException expected){}
                    shell.free();check(source.isClosed(),"Failed capture releases owned geometry lease");
                }
                try(var capture=NativeRenderTypeDraws.beginCapture(output)){capture.submit(device.createCommandEncoder());}
                pixels(output,3,1,64,0,128,191);
            }finally{VeilRenderType.release(debug);VeilRenderType.release(solid);VeilRenderType.release(layered);RenderSystem.setProjectionMatrix(previous,previousType);VeilRenderType.unbindRuntime();}
        }finally{manager.free();}
    }
    private static void reload(PreparableReloadListener listener,Minecraft client){listener.reload(new PreparableReloadListener.SharedState(client.getResourceManager()),Runnable::run,new PreparableReloadListener.PreparationBarrier(){public <T> java.util.concurrent.CompletableFuture<T> wait(T value){return java.util.concurrent.CompletableFuture.completedFuture(value);}},Runnable::run).join();}
    private static void pixels(GpuFramebuffer target,int x,int y,int...expected){var device=RenderSystem.getDevice();try(var buffer=device.createBuffer(()->"Flare pixel",GpuBuffer.USAGE_COPY_DST|GpuBuffer.USAGE_MAP_READ,4)){
        var encoder=device.createCommandEncoder();encoder.copyTextureToBuffer(target.color(0).texture(),buffer,0,()->{},0,x,y,1,1);
        try(var fence=encoder.createFence()){encoder.submit();check(fence.awaitCompletion(10_000_000_000L),"Pixel read timeout");try(var read=buffer.map(true,false)){for(int i=0;i<4;i++)check(java.lang.Math.abs((read.data().get(i)&255)-expected[i])<=1,"Pixel("+x+","+y+") channel"+i+" expected"+expected[i]+" got"+(read.data().get(i)&255));}}
    }}
}
