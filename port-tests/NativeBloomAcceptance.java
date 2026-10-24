import com.google.gson.GsonBuilder;
import com.google.gson.JsonParser;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.device.GpuDevice;
import com.mojang.renderpearl.api.pipeline.*;
import com.mojang.serialization.JsonOps;
import foundry.veil.api.client.registry.PostPipelineStageRegistry;
import foundry.veil.api.client.render.framebuffer.*;
import foundry.veil.api.client.render.gpu.*;
import foundry.veil.api.client.render.post.PostProcessingManager;
import foundry.veil.api.client.render.shader.*;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.*;
import org.joml.Vector4f;
import java.nio.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.CompletableFuture;

/** Only the new native Bloom lifecycle and the unchanged original seven-stage algorithm. */
public final class NativeBloomAcceptance implements ModInitializer,ClientModInitializer {
    private int ticks;private boolean ran;
    @Override public void onInitialize(){PostPipelineStageRegistry.bootstrap();}
    @Override public void onInitializeClient(){ClientTickEvents.END_CLIENT_TICK.register(client->{
        if(ran||++ticks<5||!client.isGameLoadFinished())return;ran=true;
        final var report=new LinkedHashMap<String,Object>();final var device=RenderSystem.getDevice();
        report.put("backend",device.getDeviceInfo().backendName());report.put("device",device.getDeviceInfo().name());
        final var main=new TextureTarget("native Bloom caller",16,16,GpuFormat.RGBA8_UNORM,GpuFormat.D32_FLOAT);
        final var manager=new FramebufferManager(device,16,16);
        try(var bloom=new NativeBloomTarget(device,16,16);
            var shaders=new ShaderManager(device,ShaderManager.PROGRAM_SET,new ShaderPreDefinitions())) {
            final var resources=client.getResourceManager();reload(shaders,resources);manager.setMainFramebuffer(main);
            final var definition=FramebufferDefinition.CODEC.parse(JsonOps.INSTANCE,JsonParser.parseString(resources.getResourceOrThrow(Identifier.parse("veil:pinwheel/framebuffers/post.json")).readAllAsString())).getOrThrow();
            manager.setDefinition(VeilFramebuffers.POST,definition);manager.setFramebuffer(VeilFramebuffers.BLOOM,bloom.framebuffer());
            check(bloom.framebuffer().color(0).texture().getFormat()==GpuFormat.RGBA16_FLOAT,"Bloom uses genuine HDR RGBA16F");
            final var initial=device.createCommandEncoder();initial.clearColorTexture(main.getColorTexture(),new Vector4f(.05f,.1f,.2f,1));initial.clearDepthTexture(main.getDepthTexture(),.75);
            bloom.framebuffer().clear(initial,new Vector4f(1),0);initial.submit();
            // Record and abandon first-use clear. Its successor must still clear stale HDR.
            try(var abandoned=bloom.acquire(main.getDepthTextureView())) {
                expectFailure(abandoned.submitted());abandoned.prepare().accept(device.createCommandEncoder());
                check(!bloom.hasRendered(),"Preparing commands does not publish output");
            }
            final var pipeline=RenderPipeline.builder().withLocation(Identifier.parse("veil:native/bloom_acceptance"))
                    .withVertexShader(Identifier.parse("veil:core/gpu_blit")).withFragmentShader(Identifier.parse("veil:core/native_bloom_emission"))
                    .withPrimitiveTopology(PrimitiveTopology.TRIANGLES).withCull(false)
                    .withDepthStencilState(new DepthStencilState(CompareOp.GREATER_THAN,false))
                    .withColorTargetState(new ColorTargetState(Optional.empty(),GpuFormat.RGBA16_FLOAT,ColorTargetState.WRITE_ALL)).build();
            try(var source=new GpuShaderSource(resources);var shader=new GpuShaderProgram(device,pipeline,source,Runnable::run)) {
                try(var output=bloom.acquire(main.getDepthTextureView())) {
                    final var commands=device.createCommandEncoder();output.prepare().accept(commands);shader.prepare(commands);
                    try(var pass=output.framebuffer().beginPass(commands)){shader.bind(pass);pass.draw(3,1,0,0);}
                    check(!bloom.hasRendered(),"Recorded draw remains unpublished before actual submission");commands.submit();output.submitted().run();
                }
                check(bloom.hasRendered(),"Actual encoder submission commits Bloom");
                // A second real draw must retain the first draw outside its scissor.
                try(var output=bloom.acquire(main.getDepthTextureView())) {
                    final var commands=device.createCommandEncoder();output.prepare().accept(commands);shader.prepare(commands);
                    try(var pass=output.framebuffer().beginPass(commands)){shader.bind(pass);pass.enableScissor(12,0,4,16);pass.draw(3,1,0,0);}
                    commands.submit();output.submitted().run();
                }
            }
            final var hdr=read(device,bloom.framebuffer().color(0).texture()).order(ByteOrder.nativeOrder());
            for(int y=0;y<16;y++)for(int x=0;x<16;x++) {
                int offset=(y*16+x)*8;
                check(hdr.getShort(offset)==(short)(x<8?0:0x3800),"Bloom clears once and retains HDR red outside later draw");
                check(hdr.getShort(offset+6)==(short)(x<8?0:0x3c00),"Bloom uses actual read-only caller depth");
            }
            final var depthBefore=read(device,main.getDepthTexture());
            final var post=new PostProcessingManager(device,resources,shaders,manager,Runnable::run,type->{throw new AssertionError("Original Bloom requests no dynamic buffers");},()->0,mask->check(mask==0,"Unexpected Bloom dynamic buffers"));
            try {
                reload(post,resources);final var original=post.getPipeline(Identifier.parse("veil:core/bloom"));check(original!=null,"Actual original core/bloom JSON loads");
                post.runPipeline(original);final var pixels=read(device,main.getColorTexture());
                final int left=(8*16+2)*4,right=(8*16+13)*4;
                check((pixels.get(left)&255)>25,"Original down/up blur spreads emission into occluded area");
                check((pixels.get(right)&255)>(pixels.get(left)&255)+20,"Original alpha threshold preserves stronger visible emission");
                check((pixels.get(right+1)&255)>26&&(pixels.get(right+2)&255)>51,"Original tone mapping contributes all emitted RGB channels");
                check((pixels.get(left+3)&255)==255&&(pixels.get(right+3)&255)==255,"Post resolve preserves main alpha");
                check(depthBefore.equals(read(device,main.getDepthTexture())),"Original Bloom post leaves the caller depth unchanged");
                final var clear=device.createCommandEncoder();bloom.clearRendered(clear);clear.submit();check(!bloom.hasRendered(),"Post consumption resets committed state");
                final var cleared=read(device,bloom.framebuffer().color(0).texture());while(cleared.hasRemaining())check(cleared.get()==0,"Consumed Bloom clears every HDR RGBA component to zero");
                report.put("sampleRgb",List.of(pixels.get(left)&255,pixels.get(right)&255,pixels.get(right+1)&255,pixels.get(right+2)&255));
            } finally {post.free();}
            final var retiring=new NativeBloomTarget(device,2,2);final var retained=retiring.acquire(null);final var texture=retiring.framebuffer().color(0).texture();
            retiring.close();check(!texture.isClosed(),"Pending lease retains resized Bloom owner");retained.close();check(texture.isClosed(),"Last pending lease retires HDR owner");
            check(!main.getColorTexture().isClosed()&&!main.getDepthTexture().isClosed(),"Bloom retirement preserves caller resources");
            report.put("status","passed");report.put("checks",List.of("real RGBA16F emission with borrowed reverse depth","aborted preparation, submission callback and clear-once multi-draw accumulation","actual original seven-stage core/bloom JSON, packed HDR down/up blur and tone mapping","native main resolve, caller-depth preservation and final RGBA0 clear","retired HDR owner retained by actual pending leases"));
        }catch(Throwable failure){failure.printStackTrace();report.put("status","failed");report.put("error",failure.toString());}
        finally {manager.free();main.destroyBuffers();try{Files.writeString(Path.of(System.getProperty("veil.bloom.report")),new GsonBuilder().setPrettyPrinting().create().toJson(report));}catch(Exception failure){throw new RuntimeException(failure);}client.stop();}
    });}
    private static void reload(PreparableReloadListener listener,ResourceManager resources) {
        listener.reload(new PreparableReloadListener.SharedState(resources),Runnable::run,new PreparableReloadListener.PreparationBarrier(){public <T> CompletableFuture<T> wait(T value){return CompletableFuture.completedFuture(value);}},Runnable::run).join();
    }
    private static ByteBuffer read(GpuDevice device,com.mojang.renderpearl.api.textures.GpuTexture texture) {
        final int size=texture.getWidth(0)*texture.getHeight(0)*texture.getFormat().blockSize();
        try(var buffer=device.createBuffer(()->"new Bloom readback",GpuBuffer.USAGE_COPY_DST|GpuBuffer.USAGE_MAP_READ,size)) {
            final var encoder=device.createCommandEncoder();encoder.copyTextureToBuffer(texture,buffer,0,()->{},0);
            try(var fence=encoder.createFence()){encoder.submit();check(fence.awaitCompletion(10_000_000_000L),"Bloom readback completed");try(var mapped=buffer.map(true,false)){final var result=ByteBuffer.allocate(size);result.put(mapped.data()).flip();return result;}}
        }
    }
    private static void expectFailure(Runnable action){try{action.run();throw new AssertionError("Expected genuine Bloom lifecycle rejection");}catch(IllegalStateException expected){}}
    private static void check(boolean value,String message){if(!value)throw new AssertionError(message);}
}
