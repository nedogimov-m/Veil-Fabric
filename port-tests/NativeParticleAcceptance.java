import com.google.gson.GsonBuilder;
import com.mojang.blaze3d.ProjectionType;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.*;
import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.device.GpuDevice;
import com.mojang.renderpearl.api.pipeline.*;
import foundry.veil.api.client.render.CachedBufferSource;
import foundry.veil.api.client.render.gpu.*;
import foundry.veil.api.client.render.shader.*;
import foundry.veil.api.client.render.texture.TextureFilter;
import foundry.veil.api.client.render.vertex.*;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.state.OptionsRenderState;
import net.minecraft.resources.Identifier;
import net.minecraft.util.profiling.InactiveProfiler;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.joml.Vector4f;
import java.nio.*;
import java.nio.file.*;
import java.util.*;

/** New native particle payload/shader/instance lifetime and detached trail capture checks only. */
public final class NativeParticleAcceptance implements ClientModInitializer {
    private int ticks;private boolean ran;
    @Override public void onInitializeClient(){ClientTickEvents.END_CLIENT_TICK.register(client->{
        if(ran||++ticks<5||!client.isGameLoadFinished())return;ran=true;
        var report=new LinkedHashMap<String,Object>();var checks=new ArrayList<String>();var device=RenderSystem.getDevice();report.put("backend",device.getDeviceInfo().backendName());
        class Shaders extends ShaderManager{Shaders(){super(device,PROGRAM_SET,new ShaderPreDefinitions());}void load(){var resources=client.getResourceManager();apply(prepare(resources,InactiveProfiler.INSTANCE),resources,InactiveProfiler.INSTANCE);}}
        var fogLayout=GpuUniformBuffer.layoutBuilder().field("FogColor",GpuUniformBuffer.ValueType.VEC4);
        for(String name:new String[]{"FogEnvironmentalStart","FogEnvironmentalEnd","FogRenderDistanceStart","FogRenderDistanceEnd","FogSkyEnd","FogCloudsEnd"})fogLayout.field(name,GpuUniformBuffer.ValueType.FLOAT);
        try(var shaders=new Shaders();var source=GpuFramebuffer.builder("Particle source",2,1).color(GpuFormat.RGBA8_UNORM).build(device);
            var lightmap=GpuFramebuffer.builder("Particle native packed light",16,16).color(GpuFormat.RGBA8_UNORM).build(device);
            var target=GpuFramebuffer.builder("Actual particle output",16,16).color(GpuFormat.RGBA16_FLOAT).depth(GpuFormat.D32_FLOAT).build(device);
            var projection=new GpuUniformBuffer(device,"Particle explicit native Projection",GpuUniformBuffer.layoutBuilder().field("ProjMat",GpuUniformBuffer.ValueType.MAT4).build());
            var fog=new GpuUniformBuffer(device,"Particle explicit native Fog",fogLayout.build());var mesh=quad(device)){
            check(report.get("backend").toString().equalsIgnoreCase("vulkan"),"Actual Vulkan required");shaders.load();projection.setMatrix("ProjMat",new Matrix4f());fog.setVector("FogColor",0,0,0,0);
            for(String name:new String[]{"FogEnvironmentalStart","FogRenderDistanceStart"})fog.setFloat(name,1000);
            for(String name:new String[]{"FogEnvironmentalEnd","FogRenderDistanceEnd","FogSkyEnd","FogCloudsEnd"})fog.setFloat(name,2000);
            var encoder=device.createCommandEncoder();
            try(var image=new NativeImage(2,1,false);var lights=new NativeImage(16,16,false)){
                image.setPixel(0,0,0xffff0000);image.setPixel(1,0,0xff00ff00);
                for(int y=0;y<16;y++)for(int x=0;x<16;x++){int c=Math.round((x+y)/30f*255);lights.setPixel(x,y,0xff000000|c<<16|c<<8|c);}
                encoder.writeToTexture(source.color(0).texture(),image);encoder.writeToTexture(lightmap.color(0).texture(),lights);encoder.submit();
            }
            var selected=Set.of(System.getProperty("veil.particles.cases","payload,particles,additive,trails").split(","));
            var bytes=ByteBuffer.allocateDirect(208).order(ByteOrder.nativeOrder());
            for(int i=0;i<2;i++){
                var transform=NativeParticlePass.transform(1e10+(i==0?-.5:.5),2e10,3e10+.5,1e10,2e10,3e10,new Vector3f(),null);
                NativeParticlePass.writeInstance(bytes,transform,.3f,i==0?0:.51f,0,i==0?.49f:1,1,1,1,1,.5f,i==0?0x00f000f0:0x00f00000);
            }
            bytes.flip();
            if(selected.contains("payload")){
                check(bytes.remaining()==208&&NativeParticlePass.INSTANCES.getVertexSize()==104,"Actual native particle stride must match both styles");
                check(bytes.getFloat(48)==-.5f&&bytes.getFloat(104+48)==.5f&&Math.abs(bytes.getFloat(56)-.5f)<.00001f,"Camera-relative subtraction must occur in double precision before upload");
                check((bytes.getShort(100)&65535)==240&&(bytes.getShort(102)&65535)==240&&(bytes.getShort(204)&65535)==0&&(bytes.getShort(206)&65535)==240,"Original full packed light components must occupy native signed-short attributes");
                var orientation=new Quaternionf().rotationY((float)Math.PI/2);var copy=new Quaternionf(orientation);
                var billboard=NativeParticlePass.transform(2,3,4,2,3,4,new Vector3f(),orientation).transformDirection(1,0,0,new Vector3f());
                check(orientation.equals(copy)&&Math.abs(billboard.z+1)<.00001f,"Billboard orientation must rotate geometry without mutating extracted camera");
                checks.add("payload: actual producer helper preserves 104-byte native layout, large-coordinate subtraction, cube/billboard orientation, independent UV/color and packed light");
            }
            if(selected.contains("particles")){
                var program=shaders.getShader(id(),pipeline(target,false),Runnable::run);textures(program,source,lightmap);clear(device,target,0);
                draw(device,target,program,mesh,bytes,projection,fog);assertPixel(device,target,4,8,.5f,0,0,.5f);assertPixel(device,target,12,8,0,128/255f*.5f,0,.5f);
                clear(device,target,.9);draw(device,target,program,mesh,bytes,projection,fog);assertPixel(device,target,4,8,0,0,0,0);
                checks.add("particles: original native particle shader, actual matrix/scale/UV/RGBA/light instance attributes, real atlas cells+lightmap, translucent alpha and reversed depth");
            }
            if(selected.contains("additive")){
                var program=shaders.getShader(id(),pipeline(target,true),Runnable::run);textures(program,source,lightmap);clear(device,target,0);
                draw(device,target,program,mesh,bytes,projection,fog);draw(device,target,program,mesh,bytes,projection,fog);assertPixel(device,target,4,8,2,0,0,1);
                boolean invalid=false;try(var retained=mesh.retain()){NativeParticlePass.record(device,device.createCommandEncoder(),target,program,retained,bytes.duplicate().limit(4),2,Map.of());}catch(IllegalArgumentException expected){invalid=true;}
                check(invalid,"Invalid native instance ranges must fail before upload/pass creation");
                checks.add("additive: genuine independent blend variant and HDR repeated instances; invalid instance range rejected before recording");
            }
            if(selected.contains("trails")){
                var type=RenderTypes.debugQuads();var capture=new NativeVertexSnapshot.Capture(type.format());
                for(float[] v:new float[][]{{-.5f,-.5f,.5f},{.5f,-.5f,.5f},{.5f,.5f,.5f},{-.5f,.5f,.5f}})capture.addVertex(v[0],v[1],v[2]).setColor(51,102,153,255);
                var snapshot=capture.finish();capture.addVertex(1000,1000,1000).setColor(255,0,0,0);
                check(snapshot.vertices().size()==4&&snapshot.vertices().getFirst().color()==0xff336699,"Deferred procedural snapshot must detach complete attributes");
                clear(device,target,0);var options=new OptionsRenderState();options.improvedTransparency=false;
                var inputs=new NativeFeatureScene.Inputs(options,lightmap.color(0).view(),fog.buffer().slice(),RenderSystem.getShaderLights(),RenderSystem.getGlobalSettingsUniform());
                try(var trailTarget=GpuFramebuffer.builder("Native trail target",16,16).color(GpuFormat.RGBA8_UNORM).depth(GpuFormat.D32_FLOAT).build(device);var scene=new NativeFeatureScene(client,client.getResourceManager())){clear(device,trailTarget,0);scene.render(trailTarget,new Matrix4f(),ProjectionType.ORTHOGRAPHIC,new Matrix4f(),inputs,nodes->nodes.submitCustomGeometry(new PoseStack(),type,snapshot));assertPixel(device,trailTarget,8,8,51/255f,102/255f,153/255f,1);}
                var sourceCapture=new CachedBufferSource();var collected=new net.minecraft.client.renderer.SubmitNodeStorage();
                for(float[] v:new float[][]{{-.1f,-.1f,.5f},{.1f,-.1f,.5f},{.1f,.1f,.5f},{-.1f,.1f,.5f}})sourceCapture.getBuffer(type).addVertex(v[0],v[1],v[2]).setColor(-1);
                sourceCapture.submit(collected,new PoseStack());sourceCapture.free();check(!collected.getSubmitsPerOrder().isEmpty(),"Native cached source must submit actual immutable custom geometry");
                checks.add("trails: generic detached procedural vertices replay exact native feature-scene pixels after source mutation, and actual cached VertexSource submits native collector geometry before source free");
            }
            report.put("status","passed");
        }catch(Throwable failure){failure.printStackTrace();report.put("status","failed");report.put("error",failure.toString());}
        report.put("checks",checks);report.put("cases",checks.size());try{Files.writeString(Path.of(System.getProperty("veil.particles.report")),new GsonBuilder().setPrettyPrinting().create().toJson(report));}catch(Exception failure){throw new RuntimeException(failure);}client.stop();
    });}
    private static RenderPipeline pipeline(GpuFramebuffer target,boolean additive){
        var bindings=BindGroupLayout.builder();for(String name:new String[]{"Projection","DynamicTransforms","Fog"})bindings.withUniform(name,UniformType.UNIFORM_BUFFER);for(String name:new String[]{"Sampler0","Sampler2"})bindings.withUniform(name,UniformType.COMBINED_IMAGE_SAMPLER);
        return RenderPipeline.builder().withLocation(Identifier.parse("veil_test:particle_"+additive)).withVertexShader(id()).withFragmentShader(id()).withBindGroupLayout(bindings.build())
                .withVertexBinding(0,VeilVertexFormat.QUASAR_PARTICLE).withVertexBinding(1,NativeParticlePass.INSTANCES).withPrimitiveTopology(PrimitiveTopology.QUADS).withCull(false)
                .withDepthStencilState(new DepthStencilState(CompareOp.GREATER_THAN_OR_EQUAL,true)).withColorTargetState(new ColorTargetState(Optional.of(additive?new BlendFunction(BlendFactor.ONE,BlendFactor.ONE):BlendFunction.TRANSLUCENT),GpuFormat.RGBA16_FLOAT,ColorTargetState.WRITE_ALL)).build();
    }
    private static Identifier id(){return Identifier.parse("veil:quasar/particle");}
    private static void textures(GpuShaderProgram program,GpuFramebuffer source,GpuFramebuffer lightmap){program.setTexture("Sampler0",source.color(0).view(),TextureFilter.CLAMP);program.setTexture("Sampler2",lightmap.color(0).view(),TextureFilter.CLAMP);}
    private static void draw(GpuDevice device,GpuFramebuffer target,GpuShaderProgram program,VertexArray mesh,ByteBuffer data,GpuUniformBuffer projection,GpuUniformBuffer fog){
        var encoder=device.createCommandEncoder();projection.upload(encoder);fog.upload(encoder);var transforms=RenderSystem.getDynamicUniforms().writeTransform(new Matrix4f());
        try(var lease=program.retain();var geometry=mesh.retain();var instances=NativeParticlePass.record(device,encoder,target,program,geometry,data,2,Map.of("Projection",projection.buffer().slice(),"DynamicTransforms",transforms,"Fog",fog.buffer().slice()))){encoder.submit();}
    }
    private static VertexArray quad(GpuDevice device){var mesh=VertexArray.create(device,"Actual particle quad");var encoder=device.createCommandEncoder();try(var storage=new ByteBufferBuilder(256)){var builder=new BufferBuilder(storage,PrimitiveTopology.QUADS,VeilVertexFormat.QUASAR_PARTICLE);for(float[] p:new float[][]{{1,-1,0},{1,1,0},{-1,1,0},{-1,-1,0}})builder.addVertex(p[0],p[1],p[2]).setNormal(0,0,-1);mesh.upload(encoder,builder.buildOrThrow(),VertexArray.DrawUsage.STATIC);encoder.submit();}return mesh;}
    private static void clear(GpuDevice device,GpuFramebuffer target,double depth){var encoder=device.createCommandEncoder();target.clear(encoder,new Vector4f(0),depth);encoder.submit();}
    private static void assertPixel(GpuDevice device,GpuFramebuffer target,int x,int y,float... expected){try(var buffer=device.createBuffer(()->"Particle actual pixels",GpuBuffer.USAGE_COPY_DST|GpuBuffer.USAGE_MAP_READ,8)){var encoder=device.createCommandEncoder();encoder.copyTextureToBuffer(target.color(0).texture(),buffer,0,()->{},0,x,y,1,1);try(var fence=encoder.createFence()){encoder.submit();check(fence.awaitCompletion(10_000_000_000L),"Particle read timeout");}try(var mapped=buffer.map(true,false)){var bytes=mapped.data().order(ByteOrder.nativeOrder());var actual=new float[4];for(int i=0;i<4;i++)actual[i]=target.color(0).texture().getFormat()==GpuFormat.RGBA16_FLOAT?Float.float16ToFloat(bytes.getShort(i*2)):(bytes.get(i)&255)/255f;for(int i=0;i<4;i++)check(Math.abs(actual[i]-expected[i])<.005f,"Native particle pixel "+Arrays.toString(actual)+" expected "+Arrays.toString(expected));}}}
    private static void check(boolean value,String message){if(!value)throw new AssertionError(message);}
}
