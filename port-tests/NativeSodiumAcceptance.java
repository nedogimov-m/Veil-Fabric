package foundry.veil.porttest;

import com.google.gson.GsonBuilder;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.pipeline.*;
import foundry.veil.api.compat.SodiumCompat;
import foundry.veil.api.client.render.gpu.*;
import foundry.veil.api.client.render.vertex.VertexArray;
import foundry.veil.ext.NativePerspectiveLevelExtension;
import foundry.veil.ext.sodium.ChunkVertexEncoderVertexExtension;
import foundry.veil.fabric.compat.sodium.VeilChunkVertex;
import foundry.veil.fabric.compat.sodium.VeilFabricSodiumCompat;
import foundry.veil.fabric.mixin.compat.sodium.*;
import net.caffeinemc.mods.sodium.api.util.NormI8;
import net.caffeinemc.mods.sodium.client.render.SodiumWorldRenderer;
import net.caffeinemc.mods.sodium.client.render.chunk.vertex.format.ChunkVertexEncoder;
import net.caffeinemc.mods.sodium.client.render.chunk.vertex.format.ChunkMeshFormats;
import net.caffeinemc.mods.sodium.client.render.chunk.vertex.format.impl.CompactChunkVertex;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.minecraft.resources.Identifier;
import org.joml.Vector4f;
import org.lwjgl.system.MemoryUtil;

import java.nio.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;

/** Genuine0.9.2 Fabric startup/world provider, native compact bytes/SNORM pixels and reload/list identity. */
public final class NativeSodiumAcceptance implements FabricClientGameTest {
    private static void check(boolean condition,String message){if(!condition)throw new AssertionError(message);}
    @Override public void runTest(ClientGameTestContext test){var report=new LinkedHashMap<String,Object>();var reload=new AtomicReference<CompletableFuture<Void>>();var token=new AtomicReference<AutoCloseable>();var before=new AtomicReference<Object>();
        try{
            test.runOnClient(client->{
                report.put("backend",RenderSystem.getDevice().getDeviceInfo().backendName());check(report.get("backend").toString().equalsIgnoreCase("vulkan"),"Actual Vulkan required");
                check(SodiumCompat.INSTANCE instanceof VeilFabricSodiumCompat,"Actual service provider activates against installed native Sodium");
                check(ChunkMeshFormats.COMPACT instanceof VeilChunkVertex,"Actual native Sodium compact format replaced by real normal-bearing layout");
                compact();pixels(client);report.put("nativeCompactAndNormalPixels","PASS");
            });
            try(var world=test.worldBuilder().create()){
                test.waitFor(client->SodiumWorldRenderer.instanceNullable()!=null&&SodiumWorldRenderer.instanceNullable().getVisibleChunkCount()>0,400);
                test.runOnClient(client->{
                    var compat=SodiumCompat.INSTANCE;var manager=((SodiumWorldRendererAccessor)SodiumWorldRenderer.instance()).getRenderSectionManager();var access=(RenderSectionManagerAccessor)manager;
                    check(!compat.getPipelines().isEmpty()&&compat.getPipelines().values().stream().allMatch(p->p.getVertexFormatBinding(0).equals(VeilChunkVertex.VERTEX_FORMAT)),"Real Sodium pipeline maps expose actual native vertex layout without GL handles");
                    var lists=access.veil$renderLists();var tasks=access.veil$taskLists();
                    try(var scope=compat.preserveVisibility()){
                        compat.setSortedRenderLists(null);compat.setTaskList(null);check(access.veil$renderLists()!=lists&&access.veil$taskLists()!=tasks,"Actual native list and deferred task references can be scoped");
                    }catch(Exception failure){throw new RuntimeException(failure);}
                    check(access.veil$renderLists()==lists&&access.veil$taskLists()==tasks,"Native list/task owner identity restored exactly");
                    var projection=RenderSystem.getProjectionMatrixBuffer();var main=client.gameRenderer.mainRenderTarget();var color=main.getColorTextureView();var depth=main.getDepthTextureView();
                    boolean rejected=false;try{new NativeLevelPerspective(client);}catch(UnsupportedOperationException expected){rejected=expected.getMessage().contains("Sodium 0.9.2");}
                    check(rejected&&access.veil$renderLists()==lists&&access.veil$taskLists()==tasks&&projection==RenderSystem.getProjectionMatrixBuffer()&&color==main.getColorTextureView()&&depth==main.getDepthTextureView(),"Unsupported independent Sodium graph rejects before changing world/target state");
                    compat.markChunksDirty();report.put("nativeProviderVisibilityAndCapability","PASS");
                    token.set(compat.preserveVisibility());before.set(manager);reload.set(compat.recompile());
                });
                test.waitFor(client->reload.get()!=null&&reload.get().isDone(),400);
                test.runOnClient(client->{
                    reload.get().join();var manager=((SodiumWorldRendererAccessor)SodiumWorldRenderer.instance()).getRenderSectionManager();check(manager!=before.get(),"Actual native resource reload replaces resident world manager");
                    var access=(RenderSectionManagerAccessor)manager;var lists=access.veil$renderLists();var tasks=access.veil$taskLists();
                    try{token.getAndSet(null).close();}catch(Exception failure){throw new RuntimeException(failure);}
                    check(access.veil$renderLists()==lists&&access.veil$taskLists()==tasks,"Closing old visibility scope cannot overwrite reloaded native owner");
                    report.put("actualReloadOwnerRetirement","PASS");
                });
            }
            report.put("checks",3);report.put("status","passed");
        }catch(Throwable failure){failure.printStackTrace();report.put("status","failed");report.put("error",failure.toString());throw failure;}
        finally{try{Files.writeString(Path.of(System.getProperty("veil.sodium.report")),new GsonBuilder().setPrettyPrinting().create().toJson(report));}catch(Exception failure){throw new RuntimeException(failure);}}
    }
    private static void compact(){
        var random=new Random(438);var nativeEncoder=new CompactChunkVertex().getEncoder();var extended=new VeilChunkVertex().getEncoder();
        long base=MemoryUtil.nmemAlloc(80),actual=MemoryUtil.nmemAlloc(96);
        try{
            for(int sample=0;sample<128;sample++){
                var vertices=ChunkVertexEncoder.Vertex.uninitializedQuad();for(var vertex:vertices){vertex.x=random.nextFloat()*32-8;vertex.y=random.nextFloat()*32-8;vertex.z=random.nextFloat()*32-8;vertex.u=random.nextFloat();vertex.v=random.nextFloat();vertex.color=random.nextInt();vertex.ao=random.nextFloat();vertex.light=random.nextInt();((ChunkVertexEncoderVertexExtension)vertex).veil$setNormal(NormI8.pack(-1,0,1));}
                int material=random.nextInt(),section=random.nextInt();nativeEncoder.write(base,material,vertices,section);check(extended.write(actual,material,vertices,section)==actual+96,"Extended quad stride");
                for(int i=0;i<4;i++){for(int j=0;j<20;j++)check(MemoryUtil.memGetByte(base+i*20L+j)==MemoryUtil.memGetByte(actual+i*24L+j),"Exact genuine compact payload retained");check(MemoryUtil.memGetInt(actual+i*24L+20)==NormI8.pack(-1,0,1),"Packed normal retained");}
                var copy=new ChunkVertexEncoder.Vertex();ChunkVertexEncoder.Vertex.copyVertexTo(vertices[0],copy);check(((ChunkVertexEncoderVertexExtension)copy).veil$getPackedNormal()==NormI8.pack(-1,0,1),"Actual static native vertex copy retains normal");
            }
        }finally{MemoryUtil.nmemFree(base);MemoryUtil.nmemFree(actual);}
    }
    private static void pixels(net.minecraft.client.Minecraft client){var device=RenderSystem.getDevice();
        var pipeline=RenderPipeline.builder().withLocation(Identifier.parse("veil:native_sodium_normal"))
                .withVertexShader(Identifier.parse("veil:core/gpu_sodium_normal")).withFragmentShader(Identifier.parse("veil:core/gpu_sodium_normal"))
                .withVertexBinding(0,VeilChunkVertex.VERTEX_FORMAT).withPrimitiveTopology(PrimitiveTopology.QUADS).withCull(false).withDepthStencilState(Optional.empty()).withColorTargetState(ColorTargetState.DEFAULT).build();
        try(var source=new GpuShaderSource(client.getResourceManager());var program=new GpuShaderProgram(device,pipeline,source,Runnable::run);var mesh=VertexArray.create(device,"native Sodium packed normal quad");
            var output=GpuFramebuffer.builder("native Sodium SNORM",2,2).color(GpuFormat.RGBA8_UNORM).build(device);var read=device.createBuffer(()->"native Sodium normal pixels",com.mojang.renderpearl.api.buffers.GpuBuffer.USAGE_MAP_READ|com.mojang.renderpearl.api.buffers.GpuBuffer.USAGE_COPY_DST,16)){
            var vertices=ChunkVertexEncoder.Vertex.uninitializedQuad();float[][] points={{-1,-1},{1,-1},{1,1},{-1,1}};
            for(int i=0;i<4;i++){vertices[i].x=points[i][0];vertices[i].y=points[i][1];vertices[i].z=.5f;vertices[i].color=-1;vertices[i].ao=1;((ChunkVertexEncoderVertexExtension)vertices[i]).veil$setNormal(NormI8.pack(-1,0,1));}
            ByteBuffer bytes=MemoryUtil.memAlloc(96);try{new VeilChunkVertex().getEncoder().write(MemoryUtil.memAddress(bytes),0,vertices,0);var encoder=device.createCommandEncoder();mesh.uploadVertexBuffer(encoder,0,bytes,VeilChunkVertex.VERTEX_FORMAT,VertexArray.DrawUsage.STATIC);
                ByteBuffer indices=ByteBuffer.allocateDirect(12).order(ByteOrder.nativeOrder());for(int value:new int[]{0,1,2,2,3,0})indices.putShort((short)value);indices.flip();mesh.uploadIndexBuffer(encoder,indices,IndexType.SHORT);mesh.setDrawMode(PrimitiveTopology.QUADS);
                output.clear(encoder,new Vector4f(0),0);try(var draw=mesh.retain();var pass=output.beginPass(encoder)){program.bind(pass);draw.draw(pass);}encoder.copyTextureToBuffer(output.color(0).texture(),read,0,()->{},0);
                try(var fence=encoder.createFence()){encoder.submit();check(fence.awaitCompletion(10_000_000_000L),"Native normal read completed");try(var mapped=read.map(true,false)){for(int i=0;i<16;i++){int expected=new int[]{0,128,255,255}[i%4];check(Math.abs((mapped.data().get(i)&255)-expected)<=1,"Actual signed-normalized normal drives exact native pixel");}}}
            }finally{MemoryUtil.memFree(bytes);}
        }
    }
}
