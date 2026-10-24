package foundry.veil.impl.quasar;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import com.mojang.renderpearl.api.pipeline.*;
import foundry.veil.api.client.render.CachedBufferSource;
import foundry.veil.api.client.render.MatrixStack;
import foundry.veil.api.client.render.VeilRenderSystem;
import foundry.veil.api.client.render.gpu.*;
import foundry.veil.api.client.render.vertex.VertexArray;
import foundry.veil.api.client.render.rendertype.VeilRenderTypeBuilder;
import foundry.veil.api.client.render.shader.ShaderManager;
import foundry.veil.api.event.VeilRenderLevelStageEvent;
import foundry.veil.impl.client.render.pipeline.VeilShaderBlockState;
import foundry.veil.impl.client.render.rendertype.NativeRenderTypeDraws;
import foundry.veil.platform.VeilEventPlatform;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.resources.Identifier;
import org.joml.Matrix4f;
import org.jetbrains.annotations.Nullable;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.*;

/** Capture particles/trails in COLLECT_SUBMITS; encode instance batches after native passes close. */
public final class QuasarParticleHandler {
    private static final Map<LevelRenderer,List<Pending>> pending=new IdentityHashMap<>();
    private static final Map<Key,RenderPipeline> pipelines=new HashMap<>();
    private static @Nullable List<Pending> collecting;
    private static @Nullable ShaderManager shaders;
    private static boolean registered;
    public static void init(){
        if(registered)return;registered=true;
        VeilEventPlatform.INSTANCE.onFreeNativeResources(QuasarParticleHandler::free);
        VeilEventPlatform.INSTANCE.onVeilRenderLevelStage((stage,context)->{
            if(stage!=VeilRenderLevelStageEvent.Stage.COLLECT_SUBMITS)return;
            discard(context.levelRenderer());var batches=new ArrayList<Pending>();var previous=collecting;collecting=batches;
            var source=new CachedBufferSource();
            try{
                var poses=new PoseStack();var camera=context.camera();
                VeilRenderSystem.renderer().getParticleManager().render((MatrixStack)poses,source,camera,
                        foundry.veil.api.client.render.VeilRenderBridge.create(camera.cullFrustum),Minecraft.getInstance().getDeltaTracker().getGameTimeDeltaPartialTick(false));
                source.submit(context.submissions(),new PoseStack());pending.put(context.levelRenderer(),batches);
            }catch(RuntimeException|Error failure){batches.forEach(Pending::close);throw failure;}
            finally{collecting=previous;source.free();}
        });
    }
    public static void queue(RenderType type,VertexArray mesh,ByteBuffer bytes,int count,CameraRenderState camera){
        if(collecting==null)throw new IllegalStateException("Capture Quasar particles inside native feature collection");
        while(type instanceof foundry.veil.api.client.render.rendertype.VeilRenderType.RenderTypeWrapper wrapper)type=Objects.requireNonNull(wrapper.get(),"Unresolved particle render type");
        if(type instanceof foundry.veil.api.client.render.rendertype.VeilRenderType.LayeredRenderType layers){for(var layer:layers.getAllLayers())queue(layer,mesh,bytes,count,camera);return;}
        var copy=ByteBuffer.allocateDirect(bytes.remaining()).order(ByteOrder.nativeOrder());copy.put(bytes.duplicate()).flip();
        collecting.add(new Pending(type,mesh.retain(),mesh.formats().get(0),mesh.getDrawMode(),copy.asReadOnlyBuffer().order(ByteOrder.nativeOrder()),count,new Matrix4f(camera.projectionMatrix),new Matrix4f(camera.viewRotationMatrix)));
    }
    /** Called exactly at END_MAIN before deferred lighting/post processing, also for nested native perspectives. */
    public static void renderPending(VeilRenderLevelStageEvent.Context context){
        var batches=pending.remove(context.levelRenderer());if(batches==null)return;
        var owner=VeilRenderSystem.renderer();var manager=owner.getShaderManager();
        if(shaders!=manager){releasePipelines();shaders=manager;}
        try(var main=GpuFramebuffer.borrow(RenderSystem.getDevice(),"Quasar native caller",context.mainTarget())){
            for(var batch:batches){
                var state=Objects.requireNonNull(NativeRenderTypeDraws.state(batch.type),"Particle RenderType needs its native Veil shader state");
                var provider=state.output()==null?null:NativeRenderTypeDraws.output(state.output());
                try(var output=provider==null?null:provider.acquire()){
                    if(provider!=null&&output==null)continue;
                    var target=output!=null?output.framebuffer():state.output()==null?main:owner.getFramebufferManager().getFramebuffer(state.output());
                    if(target==null)throw new IllegalStateException("Unknown particle output framebuffer: "+state.output());
                    draw(context,batch,state,manager,owner,target,output);
                }
            }
        }finally{batches.forEach(Pending::close);}
    }
    private static void draw(VeilRenderLevelStageEvent.Context context,Pending batch,VeilRenderTypeBuilder.State state,
            ShaderManager manager,foundry.veil.api.client.render.VeilRenderer owner,GpuFramebuffer target,
            @Nullable foundry.veil.api.client.render.rendertype.NativeRenderTypeOutput.Target output){
        Identifier shader=Objects.requireNonNull(state.veilShader(),"Instanced particles require a compatible native particle program");
        var colors=java.util.stream.IntStream.range(0,target.colorCount()).mapToObj(index->target.color(index).texture().getFormat()).toList();
        var key=new Key(batch.type,state,batch.format,batch.topology,colors,target.hasDepth());
        var pipeline=pipelines.computeIfAbsent(key,ignored->VeilRenderTypeBuilder.pipeline(shader,state,Map.of(0,batch.format,1,NativeParticlePass.INSTANCES),batch.topology,
                colors,target.hasDepth()?target.depth().texture().getFormat():null));
        var program=manager.getShader(shader,pipeline,Runnable::run);program.clearTextureBindings();
        manager.bindTextures(shader,program,name->owner.getFramebufferManager().getFramebuffer(name));
        for(var texture:batch.type.prepare().textures())if(program.hasUniform(texture.name()))program.setTexture(texture.name(),texture.textureView(),texture.sampler());
        var encoder=context.createCommandEncoder();
        if(output!=null)output.prepare().accept(encoder);
        var bindings=new HashMap<String,GpuBufferSlice>();
        bindings.put("DynamicTransforms",RenderSystem.getDynamicUniforms().writeTransform(new Matrix4f(batch.view)));
        var projection=ByteBuffer.allocateDirect(64).order(ByteOrder.nativeOrder());batch.projection.get(projection);
        bindings.put("Projection",encoder.transientMemory().uploadGpu(projection,RenderSystem.getDevice().getDeviceInfo().limits().minUniformOffsetAlignment(),GpuBuffer.USAGE_UNIFORM));
        if(RenderSystem.getShaderFog()!=null)bindings.put("Fog",RenderSystem.getShaderFog());
        if(RenderSystem.getShaderLights()!=null)bindings.put("Lighting",RenderSystem.getShaderLights());
        if(RenderSystem.getGlobalSettingsUniform()!=null)bindings.put("Globals",RenderSystem.getGlobalSettingsUniform().slice());
        bindings.putAll(VeilShaderBlockState.prepareSnapshots(encoder,VeilRenderSystem.captureShaderBlockSnapshots(program.uniformBlockSizes().keySet())));
        bindings.keySet().removeIf(name->program.bindingTypes().get(name)!=UniformType.UNIFORM_BUFFER);
        try(var lease=program.retain();var instances=NativeParticlePass.record(RenderSystem.getDevice(),encoder,target,program,batch.geometry,batch.bytes,batch.count,bindings)){
            encoder.submit();if(output!=null)output.submitted().run();
        }
    }
    private static void discard(LevelRenderer renderer){var batches=pending.remove(renderer);if(batches!=null)batches.forEach(Pending::close);}
    /** Release abandoned CPU captures after the frame, without recording a fake draw. */
    public static void endFrame(){for(var batches:pending.values())batches.forEach(Pending::close);pending.clear();}
    private static void releasePipelines(){if(shaders!=null)pipelines.forEach((key,value)->{if(key.state.veilShader()!=null)shaders.releaseShader(key.state.veilShader(),value);});pipelines.clear();shaders=null;}
    public static void free(){endFrame();releasePipelines();}
    public static void setLevel(@Nullable ClientLevel level){VeilRenderSystem.renderer().getParticleManager().setLevel(level);free();}
    private record Key(RenderType type,VeilRenderTypeBuilder.State state,com.mojang.renderpearl.api.vertex.VertexFormat format,PrimitiveTopology topology,List<GpuFormat> colors,boolean depth){}
    private record Pending(RenderType type,VertexArray.Draw geometry,com.mojang.renderpearl.api.vertex.VertexFormat format,PrimitiveTopology topology,ByteBuffer bytes,int count,Matrix4f projection,Matrix4f view)implements AutoCloseable{
        @Override public void close(){geometry.close();}
    }
    private QuasarParticleHandler(){}
}
