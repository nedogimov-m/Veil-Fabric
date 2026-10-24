import com.google.gson.GsonBuilder;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.device.GpuDevice;
import com.mojang.renderpearl.api.pipeline.*;
import foundry.veil.api.client.render.gpu.*;
import foundry.veil.api.client.render.texture.*;
import foundry.veil.ext.TextureManagerExtension;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.renderer.texture.*;
import net.minecraft.core.Direction;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.PreparableReloadListener;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;

/** New actual cube/array resources, uploads, shader sampling and native texture-manager lifecycle only. */
public final class NativeLayeredTextureAcceptance implements ClientModInitializer {
    private int ticks;private boolean ran;private TextureManager manager;private CompletableFuture<?> preload;
    private static Identifier id(String path){return Identifier.fromNamespaceAndPath("veil",path);}
    @Override public void onInitializeClient() {
        ClientTickEvents.END_CLIENT_TICK.register(client->{
            if(ran||++ticks<5||!client.isGameLoadFinished())return;
            if(manager==null) {
                manager=new TextureManager(client.getResourceManager());
                preload=((TextureManagerExtension)manager).veil$registerPreloadedTexture(id("native/preloaded"),new SimpleCubemapTexture(id("textures/test/native_cube.png")),net.minecraft.util.Util.backgroundExecutor());
                return;
            }
            if(!preload.isDone())return;ran=true;final var report=new LinkedHashMap<String,Object>();
            final var device=RenderSystem.getDevice();report.put("backend",device.getDeviceInfo().backendName());report.put("device",device.getDeviceInfo().name());
            try {
                preload.join();check(device.getDeviceInfo().backendName().toLowerCase(Locale.ROOT).contains("vulkan"),"Actual Vulkan required");
                final var cube=manager.getTexture(id("textures/test/native_cube.png"));check(cube instanceof SimpleCubemapTexture,"Actual texture-manager metadata hook creates a native reloadable cube");
                final var single=manager.getTexture(id("textures/test/native_array_a.png"));check(single instanceof SimpleArrayTexture,"Actual metadata hook creates a native one-layer array view");
                final var array=new SimpleArrayTexture(id("textures/test/native_array_a.png"),id("textures/test/native_array_b.png"));
                array.preload(client.getResourceManager(),Runnable::run).join();manager.registerAndLoad(id("native/array"),array);
                try(var source=new GpuShaderSource(client.getResourceManager())) {
                    draw(device,source,cube,"cube",6,(face,x,y)->color(face,x,y));
                    draw(device,source,manager.getTexture(id("native/preloaded")),"cube",6,(face,x,y)->color(face,x,y));
                    draw(device,source,array,"array",2,(face,x,y)->color(face+6,x,y));
                    draw(device,source,single,"array",1,(face,x,y)->color(6,x,y));
                    final var dynamic=new DynamicCubemapTexture();dynamic.init(2,2);
                    try(var image=new NativeImage(2,2,false)) {
                        for(int y=0;y<2;y++)for(int x=0;x<2;x++)image.setPixel(x,y,0xff00ff00);
                        dynamic.upload(Direction.NORTH,image);check(image.getPixel(0,0)==0xff00ff00,"Dynamic upload retains caller image ownership");
                        draw(device,source,dynamic,"cube",6,(face,x,y)->face==5?0xff00ff00:0xffffffff);
                        manager.register(id("native/dynamic"),dynamic);
                        final var previousCube=cube.getTextureView();final var previousArray=array.getTextureView();final var previousDynamic=dynamic.getTextureView();
                        reload(manager,client.getResourceManager());
                        check(previousCube.isClosed()&&previousArray.isClosed()&&previousDynamic.isClosed(),"Real native reload retires all prior views");
                        draw(device,source,cube,"cube",6,(face,x,y)->color(face,x,y));
                        draw(device,source,array,"array",2,(face,x,y)->color(face+6,x,y));
                        draw(device,source,dynamic,"cube",6,(face,x,y)->face==5?0xff00ff00:0xffffffff);
                        for(int y=0;y<2;y++)for(int x=0;x<2;x++)image.setPixel(x,y,0xffff0000);
                        dynamic.upload(image);draw(device,source,dynamic,"cube",6,(face,x,y)->0xffff0000);
                        final var retained=dynamic.getTextureView();
                        try(var wrong=new NativeImage(3,3,false)){expectFailure(()->dynamic.upload(Direction.UP,wrong));}
                        expectFailure(()->dynamic.apply(new TextureContents(new NativeImage(2,7,false),null)));
                        check(dynamic.getTextureView()==retained&&!retained.isClosed(),"Invalid size/reload input preserves actual last-good cube/view");
                    }
                }
                report.put("status","passed");report.put("checks",List.of("actual native cube face/corner shader pixels","real 2D array shader sampling including one-layer view","native TextureManager metadata and asynchronous preload registration","resource reload retires views and retains dynamic face pixels","dynamic full/single-face upload, caller ownership and rejected-size preservation"));
            } catch(Throwable failure){failure.printStackTrace();report.put("status","failed");report.put("error",failure.toString());}
            finally {manager.close();try{Files.writeString(Path.of(System.getProperty("veil.layered.texture.report")),new GsonBuilder().setPrettyPrinting().create().toJson(report));}catch(Exception failure){throw new RuntimeException(failure);}client.stop();}
        });
    }
    private interface Pixels {int get(int face,int x,int y);}
    private static int color(int face,int x,int y){return 0xff000000|((face*24+16)&255)<<16|(40+x*80)<<8|40+y*80;}
    private static void draw(GpuDevice device,GpuShaderSource source,AbstractTexture texture,String kind,int faces,Pixels expected) {
        final var definition=RenderPipeline.builder().withLocation(id("native/test_"+kind))
                .withVertexShader(id("core/gpu_blit")).withFragmentShader(id("core/native_layered_"+kind))
                .withBindGroupLayout(BindGroupLayout.builder().withUniform("InSampler",UniformType.COMBINED_IMAGE_SAMPLER).build())
                .withPrimitiveTopology(PrimitiveTopology.TRIANGLES).withCull(false).withDepthStencilState(Optional.empty()).withColorTargetState(ColorTargetState.DEFAULT).build();
        try(var shader=new GpuShaderProgram(device,definition,source,Runnable::run);
            var output=GpuFramebuffer.builder("new layered-texture pixels",faces*2,2).color(GpuFormat.RGBA8_UNORM).build(device)) {
            shader.setTexture("InSampler",texture.getTextureView(),texture.getSampler());final var encoder=device.createCommandEncoder();shader.prepare(encoder);
            try(var pass=output.beginPass(encoder)){shader.bind(pass);pass.draw(3,1,0,0);}encoder.submit();
            final int size=faces*2*2*4;
            try(var buffer=device.createBuffer(()->"new layer sampling readback",GpuBuffer.USAGE_COPY_DST|GpuBuffer.USAGE_MAP_READ,size)) {
                final var read=device.createCommandEncoder();read.copyTextureToBuffer(output.color(0).texture(),buffer,0,()->{},0);
                try(var fence=read.createFence()) {
                    read.submit();check(fence.awaitCompletion(10_000_000_000L),"Layer sample readback completed");
                    try(var mapped=buffer.map(true,false)) {
                        final var bytes=mapped.data();
                        for(int y=0;y<2;y++)for(int x=0;x<faces*2;x++) {
                            int wanted=expected.get(x/2,x%2,y);int[] rgba={wanted>>>16&255,wanted>>>8&255,wanted&255,wanted>>>24&255};
                            for(int channel:rgba){int actual=bytes.get()&255;check(Math.abs(actual-channel)<=1,"Actual "+kind+" sample at "+x+","+y+": "+actual+" expected "+channel);}
                        }
                    }
                }
            }
        }
    }
    private static void reload(TextureManager manager,net.minecraft.server.packs.resources.ResourceManager resources) {
        manager.reload(new PreparableReloadListener.SharedState(resources),Runnable::run,new PreparableReloadListener.PreparationBarrier(){
            @Override public <T> CompletableFuture<T> wait(T value){return CompletableFuture.completedFuture(value);}
        },Runnable::run).join();
    }
    private static void expectFailure(Runnable action){try{action.run();throw new AssertionError("Expected genuine invalid-size rejection");}catch(IllegalArgumentException expected){}}
    private static void check(boolean condition,String message){if(!condition)throw new AssertionError(message);}
}
