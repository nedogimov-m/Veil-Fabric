import com.google.gson.GsonBuilder;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.device.GpuDevice;
import com.mojang.renderpearl.api.pipeline.*;
import foundry.veil.api.client.render.VeilShaderBufferLayout;
import foundry.veil.api.client.render.gpu.*;
import foundry.veil.api.client.render.shader.*;
import foundry.veil.api.client.render.shader.block.ShaderBlock;
import foundry.veil.api.client.render.texture.TextureFilter;
import foundry.veil.api.client.render.vertex.VertexArray;
import foundry.veil.impl.client.render.LayoutSerializer;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.resources.Identifier;
import net.minecraft.util.profiling.InactiveProfiler;
import org.joml.*;
import java.lang.Math;
import java.nio.*;
import java.nio.file.*;
import java.util.*;

/** New light algorithms/instance layouts/R8 DDA only; no full Veil bootstrap claim. */
public final class NativeLightAcceptance implements ClientModInitializer {
    private int ticks; private boolean ran;
    @Override public void onInitializeClient() { ClientTickEvents.END_CLIENT_TICK.register(client -> {
        if (ran || ++ticks < 5 || !client.isGameLoadFinished()) return; ran = true;
        var report = new LinkedHashMap<String,Object>(); var checks = new ArrayList<String>();
        var device = RenderSystem.getDevice(); report.put("backend",device.getDeviceInfo().backendName());
        // An explicit identity extracted-camera fixture; no global camera update or registry stub.
        var cameraLayout = VeilShaderBufferLayout.<Matrix4f>builder().name("NativeLightCamera")
                .mat4("ProjMat", v->v).mat4("IProjMat",v->v).mat4("ViewMat",v->v).mat4("IViewMat",v->v)
                .mat3("IViewRotMat",v->new Matrix3f()).vec3("CameraPosition",v->new Vector3f())
                .f32("NearPlane",v->0f).vec3("CameraBobOffset",v->new Vector3f()).f32("FarPlane",v->1f).build();
        NativeShaderBlocks.bindRuntime(()->Map.of(id("camera"),cameraLayout));
        class Shaders extends ShaderManager {
            Shaders(){super(device,ShaderManager.PROGRAM_SET,new ShaderPreDefinitions());}
            void load(){var resources=client.getResourceManager();apply(prepare(resources,InactiveProfiler.INSTANCE),resources,InactiveProfiler.INSTANCE);}
        }
        try (var shaders=new Shaders(); var grid=new NativeVoxelGridTexture(device);
             var scene=GpuFramebuffer.builder("Light source",8,8).color(GpuFormat.RGBA16_FLOAT).color(GpuFormat.RGBA16_FLOAT).depth(GpuFormat.D32_FLOAT).build(device);
             var target=GpuFramebuffer.builder("Actual native lights",8,8).color(GpuFormat.RGBA16_FLOAT).depth(GpuFormat.D32_FLOAT).build(device);
             var camera=LayoutSerializer.create(cameraLayout,device);var mesh=cube(device)) {
            check(device.getDeviceInfo().backendName().toLowerCase(Locale.ROOT).contains("vulkan"),"Actual Vulkan required");
            shaders.load();camera.set(new Matrix4f());
            var encoder=device.createCommandEncoder();grid.upload(encoder,ByteBuffer.allocateDirect(64*64*64));
            encoder.clearColorTexture(scene.color(0).texture(),new Vector4f(.5f,.25f,.75f,1));
            encoder.clearColorTexture(scene.color(1).texture(),new Vector4f(0,0,1,0));
            encoder.clearDepthTexture(scene.depth().texture(),.5);encoder.submit();
            Set<String> selected=Set.of(System.getProperty("veil.lights.cases","directional,point,area,voxel").split(","));
            if(selected.contains("directional")) {
                var program=shaders.getShader(id("light/directional"),NativeLightPass.pipeline(id("light/directional"),Map.of(),PrimitiveTopology.TRIANGLES,target,false),Runnable::run);
                textures(program,scene,grid);program.getUniformSafe("LightDirection").setVector(0,0,-1);
                clear(device,target,.5);program.getUniformSafe("LightColor").setVector(.4f,.8f,.2f);
                draw(device,target,program,null,1,camera,cameraLayout.name());
                assertPixel(device,target,4,4,.21f,.23f,.1525f,1);
                program.getUniformSafe("LightColor").setVector(.2f,.1f,.3f);draw(device,target,program,null,1,camera,cameraLayout.name());
                assertPixel(device,target,4,4,.315f,.25875f,.38125f,2);
                checks.add("directional: genuine original shader, typed managed uniforms, actual registered camera block, HDR additive accumulation");
            }
            if(selected.contains("point")) {
                var bytes=ByteBuffer.allocateDirect(64).order(ByteOrder.nativeOrder());
                for(float x:new float[]{-.5f,.5f})bytes.putFloat(x).putFloat(0).putFloat(.7f).putFloat(x<0?1:0).putFloat(x>0?1:0).putFloat(0).putFloat(.45f).putFloat(0);
                encoder=device.createCommandEncoder();mesh.uploadVertexBuffer(encoder,1,bytes.flip(),NativeLightInstances.POINT,VertexArray.DrawUsage.STREAM);encoder.submit();
                var program=shaders.getShader(id("light/point"),NativeLightPass.pipeline(id("light/point"),mesh.formats(),mesh.getDrawMode(),target,true),Runnable::run);
                textures(program,scene,grid);clear(device,target,.5);draw(device,target,program,mesh,2,camera,cameraLayout.name());
                float[] left=pixel(device,target,1,4),right=pixel(device,target,6,4);
                check(left[0]>.005f&&left[1]<.001f&&right[1]>.005f&&right[0]<.001f,"Two genuine native point-light instances must select distinct locations/colors: "+Arrays.toString(left)+" / "+Arrays.toString(right));
                clear(device,target,0);draw(device,target,program,mesh,2,camera,cameraLayout.name());assertPixel(device,target,1,4,0,0,0,0);
                checks.add("point: original volume shader, actual native second vertex binding/instance step rate, independent light payloads and reversed depth rejection");
            }
            if(selected.contains("area")) {
                var packed=ByteBuffer.allocateDirect(94).order(ByteOrder.nativeOrder());new Matrix4f().get(0,packed);packed.position(64);
                packed.putFloat(.8f).putFloat(.4f).putFloat(.2f).putFloat(1).putFloat(1).putShort((short)8192).putFloat(1).putFloat(0).flip();
                var aligned=ByteBuffer.allocateDirect(96).order(ByteOrder.nativeOrder());NativeLightInstances.expandArea(packed,aligned);check(aligned.position()==96,"Real area instance must have aligned 96-byte stride");
                check(Math.abs(aligned.getFloat(84)-8192/65535f)<1e-7f&&aligned.getFloat(88)==1&&aligned.getFloat(92)==0,"Original UNORM16 angle/distance/occlusion must survive repacking");
                encoder=device.createCommandEncoder();mesh.uploadVertexBuffer(encoder,1,aligned.flip(),NativeLightInstances.AREA,VertexArray.DrawUsage.STREAM);
                encoder.clearColorTexture(scene.color(1).texture(),new Vector4f(0,0,-1,0));encoder.submit();
                var program=shaders.getShader(id("light/area"),NativeLightPass.pipeline(id("light/area"),mesh.formats(),mesh.getDrawMode(),target,true),Runnable::run);
                textures(program,scene,grid);clear(device,target,.5);draw(device,target,program,mesh,1,camera,cameraLayout.name());
                assertPixel(device,target,4,4,.0525f,.014375f,.0190625f,1);
                checks.add("area: actual original matrix/angle/closest-plane/attenuation shader and native aligned instance attributes preserve original quantized angle");
            }
            if(selected.contains("voxel")) {
                var program=shaders.getShader(id("native_voxel_test"),RenderPipeline.builder().withLocation(id("native_voxel_test")).withVertexShader(id("blit_screen")).withFragmentShader(id("native_voxel_test")).withBindGroupLayout(BindGroupLayout.builder().withUniform("BlockGrid",UniformType.COMBINED_IMAGE_SAMPLER).build()).withPrimitiveTopology(PrimitiveTopology.TRIANGLES).withCull(false).withColorTargetState(new ColorTargetState(Optional.empty(),GpuFormat.RGBA16_FLOAT,ColorTargetState.WRITE_ALL)).build(),Runnable::run);
                program.setTexture("BlockGrid",grid.view(),grid.sampler());program.getUniformSafe("GridOrigin").setVector(-12,20,-50);
                var cells=ByteBuffer.allocateDirect(64*64*64);cells.put(8+10*64+40*4096,(byte)255);
                encoder=device.createCommandEncoder();grid.upload(encoder,cells);encoder.submit();clear(device,target,0);
                draw(device,target,program,null,1,camera,cameraLayout.name());assertPixel(device,target,4,4,0,1,0,1);
                boolean rejected=false;try{grid.upload(device.createCommandEncoder(),ByteBuffer.allocateDirect(4));}catch(IllegalArgumentException expected){rejected=true;}check(rejected,"Malformed voxel uploads must fail before recording");
                cells.put(8+10*64+40*4096,(byte)0);encoder=device.createCommandEncoder();grid.upload(encoder,cells);encoder.submit();clear(device,target,0);
                draw(device,target,program,null,1,camera,cameraLayout.name());assertPixel(device,target,4,4,1,1,1,1);
                checks.add("voxel: genuine native R8 upload and original 64-cubed DDA in both directions, flattened high-Z cell indexing, translated origin, empty replacement and invalid upload rejection");
            }
            report.put("status","passed");
        } catch(Throwable failure){failure.printStackTrace();report.put("status","failed");report.put("error",failure.toString());}
        finally{NativeShaderBlocks.unbindRuntime();}
        report.put("checks",checks);report.put("cases",checks.size());
        try{Files.writeString(Path.of(System.getProperty("veil.lights.report")),new GsonBuilder().setPrettyPrinting().create().toJson(report));}catch(Exception failure){throw new RuntimeException(failure);}client.stop();
    });}
    private static void textures(GpuShaderProgram program,GpuFramebuffer scene,NativeVoxelGridTexture grid){
        program.setTexture("AlbedoSampler",scene.color(0).view(),TextureFilter.CLAMP);program.setTexture("NormalSampler",scene.color(1).view(),TextureFilter.CLAMP);
        if(program.hasUniform("DepthSampler"))program.setTexture("DepthSampler",scene.depth().view(),TextureFilter.CLAMP);
        if(program.hasUniform("BlockGrid"))program.setTexture("BlockGrid",grid.view(),grid.sampler());
        program.getUniformSafe("ScreenSize").setVector(8,8);program.getUniformSafe("GridOrigin").setVector(0,0,0);
    }
    private static void draw(GpuDevice device,GpuFramebuffer target,GpuShaderProgram program,VertexArray mesh,int instances,ShaderBlock<Matrix4f> camera,String cameraName){
        var encoder=device.createCommandEncoder();camera.prepare(encoder);
        try(var lease=program.retain();var draw=mesh==null?null:mesh.retain()){
            NativeLightPass.record(encoder,target,program,draw,instances,program.hasUniform(cameraName)?Map.of(cameraName,camera.slice()):Map.of());encoder.submit();
        }
    }
    private static void clear(GpuDevice device,GpuFramebuffer target,double depth){var encoder=device.createCommandEncoder();target.clear(encoder,new Vector4f(0),depth);encoder.submit();}
    private static VertexArray cube(GpuDevice device){
        float[] vertices={-1,1,1, 1,1,1, -1,-1,1, 1,-1,1, 1,-1,-1, 1,1,1, 1,1,-1, -1,1,1, -1,1,-1, -1,-1,1, -1,-1,-1, 1,-1,-1, -1,1,-1, 1,1,-1};
        var bytes=ByteBuffer.allocateDirect(vertices.length*4).order(ByteOrder.nativeOrder());for(float value:vertices)bytes.putFloat(value);
        var mesh=VertexArray.create(device,"Native light volume");var encoder=device.createCommandEncoder();mesh.uploadVertexBuffer(encoder,0,bytes.flip(),DefaultVertexFormat.POSITION,VertexArray.DrawUsage.STATIC);mesh.setDrawMode(PrimitiveTopology.TRIANGLE_STRIP);encoder.submit();return mesh;
    }
    private static float[] pixel(GpuDevice device,GpuFramebuffer target,int x,int y){
        try(var buffer=device.createBuffer(()->"Native light actual pixels",GpuBuffer.USAGE_COPY_DST|GpuBuffer.USAGE_MAP_READ,8*8*8)){
            var encoder=device.createCommandEncoder();encoder.copyTextureToBuffer(target.color(0).texture(),buffer,0,()->{},0);
            try(var fence=encoder.createFence()){encoder.submit();check(fence.awaitCompletion(10_000_000_000L),"Native light readback timed out");
                try(var mapped=buffer.map(true,false)){var data=mapped.data().order(ByteOrder.nativeOrder());var result=new float[4];for(int i=0;i<4;i++)result[i]=Float.float16ToFloat(data.getShort((y*8+x)*8+i*2));return result;}
            }
        }
    }
    private static void assertPixel(GpuDevice device,GpuFramebuffer target,int x,int y,float... expected){float[] actual=pixel(device,target,x,y);for(int i=0;i<4;i++)check(Math.abs(actual[i]-expected[i])<.002f,"Native light pixel "+Arrays.toString(actual)+" expected "+Arrays.toString(expected));}
    private static Identifier id(String path){return Identifier.fromNamespaceAndPath("veil",path);}
    private static void check(boolean value,String message){if(!value)throw new AssertionError(message);}
}
