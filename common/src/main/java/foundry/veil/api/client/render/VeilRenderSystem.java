package foundry.veil.api.client.render;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.commands.CommandEncoder;
import com.mojang.renderpearl.api.device.DeviceInfo;
import foundry.imgui.api.ImGuiMC;
import foundry.veil.Veil;
import foundry.veil.api.client.necromancer.render.NecromancerRenderer;
import foundry.veil.api.client.registry.VeilShaderBufferRegistry;
import foundry.veil.api.client.render.dynamicbuffer.DynamicBufferType;
import foundry.veil.api.client.render.framebuffer.VeilFramebuffers;
import foundry.veil.api.client.render.gpu.GpuFramebuffer;
import foundry.veil.api.client.render.gpu.GpuShaderProgram;
import foundry.veil.api.client.render.shader.block.ShaderBlock;
import foundry.veil.api.client.render.shader.program.ShaderProgram;
import foundry.veil.api.client.render.texture.VeilPreloadedTexture;
import foundry.veil.api.compat.SodiumCompat;
import foundry.veil.api.event.VeilRenderLevelStageEvent;
import foundry.veil.api.flare.modifier.RandomnessController;
import foundry.veil.api.quasar.registry.RenderStyleRegistry;
import foundry.veil.ext.LevelRendererExtension;
import foundry.veil.ext.TextureManagerExtension;
import foundry.veil.impl.client.necromancer.render.NecromancerRenderDispatcher;
import foundry.veil.impl.client.render.light.VoxelShadowGrid;
import foundry.veil.impl.client.render.pipeline.VeilShaderBlockState;
import foundry.veil.impl.client.render.pipeline.VeilShaderBufferCache;
import foundry.veil.impl.client.render.profiler.VeilRenderProfilerImpl;
import foundry.veil.impl.client.render.wrapper.VanillaAdvancedFboWrapper;
import foundry.veil.platform.VeilEventPlatform;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.ReloadableTexture;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.ReloadableResourceManager;
import net.minecraft.util.profiling.ProfilerFiller;
import org.jetbrains.annotations.Nullable;
import org.joml.Vector3f;
import org.joml.Vector3fc;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.function.Supplier;

/** Native rendering services. Textures, meshes and shader bindings belong to explicit passes and encoders. */
public final class VeilRenderSystem {
    private static final VeilShaderBlockState UNIFORM_BLOCK_STATE=new VeilShaderBlockState();
    private static final VeilShaderBufferCache SHADER_BUFFER_CACHE=new VeilShaderBufferCache(RenderSystem::getDevice,
            ()->VeilShaderBufferRegistry.REGISTRY.stream().toList(),UNIFORM_BLOCK_STATE);
    private static final Vector3f LIGHT0_DIRECTION=new Vector3f(),LIGHT1_DIRECTION=new Vector3f(),CAMERA_BOB_OFFSET=new Vector3f();
    private static final Executor RENDER_THREAD_EXECUTOR=task->{
        if(RenderSystem.isOnRenderThread())task.run();else Minecraft.getInstance().execute(task);
    };
    private static VeilRenderer renderer;
    private static VanillaAdvancedFboWrapper mainTarget;
    private static ShaderProgram selectedShader;
    private static boolean bootstrapped;

    public static DeviceInfo deviceInfo(){return RenderSystem.getDevice().getDeviceInfo();}
    public static int maxColorAttachments(){return deviceInfo().limits().maxColorAttachments();}
    public static int maxFramebufferWidth(){return deviceInfo().limits().maxTextureSize();}
    public static int maxFramebufferHeight(){return deviceInfo().limits().maxTextureSize();}
    public static float maxTextureAnisotropy(){return deviceInfo().limits().maxAnisotropy();}
    public static boolean textureAnisotropySupported(){return maxTextureAnisotropy()>1;}
    public static int uniformBufferAlignment(){return deviceInfo().limits().minUniformOffsetAlignment();}
    public static boolean drawIndirectSupported(){return deviceInfo().features().drawIndirect();}
    public static boolean multiDrawIndirectSupported(){return deviceInfo().features().multiDrawIndirect();}
    public static Executor renderThreadExecutor(){return RENDER_THREAD_EXECUTOR;}
    public static VeilRenderer renderer(){return Objects.requireNonNull(renderer,"Veil renderer is not initialized");}
    public static GpuFramebuffer getMainFramebuffer(){
        if(mainTarget==null)mainTarget=new VanillaAdvancedFboWrapper(RenderSystem.getDevice(),()->Minecraft.getInstance().gameRenderer.mainRenderTarget());
        return mainTarget.framebuffer();
    }
    public static GpuVendor gpuVendor(){
        String vendor=deviceInfo().vendorName().toLowerCase(Locale.ROOT);
        return vendor.contains("intel")?GpuVendor.INTEL:vendor.contains("nvidia")?GpuVendor.NVIDIA:
                vendor.contains("amd")||vendor.contains("ati")?GpuVendor.AMD:vendor.contains("apple")?GpuVendor.APPLE:GpuVendor.OTHER;
    }
    public static <T extends ReloadableTexture & VeilPreloadedTexture> CompletableFuture<?> registerPreloadedTexture(Identifier path,T texture){
        return registerPreloadedTexture(path,texture,net.minecraft.util.Util.backgroundExecutor());
    }
    public static <T extends ReloadableTexture & VeilPreloadedTexture> CompletableFuture<?> registerPreloadedTexture(Identifier path,T texture,Executor executor){
        return ((TextureManagerExtension)Minecraft.getInstance().getTextureManager()).veil$registerPreloadedTexture(path,texture,executor);
    }
    /** Select a CPU shader owner; actual texture/uniform binding occurs only in its caller's native pass. */
    public static GpuShaderProgram setShader(Identifier shader,com.mojang.renderpearl.api.pipeline.RenderPipeline state){
        var program=renderer().getShaderManager().getShader(shader,state,RENDER_THREAD_EXECUTOR);selectedShader=program;return program;
    }
    public static @Nullable ShaderProgram setShader(@Nullable ShaderProgram shader){selectedShader=shader;return shader;}
    public static @Nullable ShaderProgram setShader(Supplier<ShaderProgram> shader){return setShader(shader.get());}
    public static @Nullable ShaderProgram getShader(){return selectedShader;}
    /** Caller retains returned program lease through encoder.submit(). */
    public static GpuShaderProgram.Lease drawScreenQuad(CommandEncoder encoder,GpuFramebuffer target,GpuShaderProgram shader){
        var lease=shader.retain();
        try{shader.prepare(encoder);try(var pass=target.beginPass(encoder)){shader.bind(pass);pass.draw(3,1,0,0);}return lease;}
        catch(RuntimeException|Error failure){lease.close();throw failure;}
    }
    public static void rebuildChunks(){var client=Minecraft.getInstance();client.execute(()->client.levelExtractor.allChanged());}
    public static Map<String,ShaderBlock.Snapshot> captureShaderBlockSnapshots(Collection<String> requestedNames){return UNIFORM_BLOCK_STATE.snapshot(requestedNames);}
    public static void bind(CharSequence name,ShaderBlock<?> block){RenderSystem.assertOnRenderThread();UNIFORM_BLOCK_STATE.bind(name,block);}
    public static void bind(VeilShaderBufferLayout<?> layout){RenderSystem.assertOnRenderThread();SHADER_BUFFER_CACHE.bind(layout);}
    public static void unbind(ShaderBlock<?> block){RenderSystem.assertOnRenderThread();UNIFORM_BLOCK_STATE.unbind(block);}
    public static void unbind(VeilShaderBufferLayout<?> layout){RenderSystem.assertOnRenderThread();SHADER_BUFFER_CACHE.unbind(layout);}
    public static <T> @Nullable ShaderBlock<T> getBlock(VeilShaderBufferLayout<T> layout){return SHADER_BUFFER_CACHE.getBlock(layout);}
    public static Vector3fc getLight0Direction(){return LIGHT0_DIRECTION;}
    public static Vector3fc getLight1Direction(){return LIGHT1_DIRECTION;}
    public static Vector3fc getCameraBobOffset(){return CAMERA_BOB_OFFSET;}
    public static void setShaderLights(Vector3fc first,Vector3fc second){LIGHT0_DIRECTION.set(first);LIGHT1_DIRECTION.set(second);}
    public static void setCameraBobOffset(Vector3fc offset){CAMERA_BOB_OFFSET.set(offset);}
    public static boolean hasImGui(){return Veil.IMGUIMC&&ImGuiMC.isImguiLoaded();}
    public static CullFrustum getCullingFrustum(){return ((LevelRendererExtension)Minecraft.getInstance().levelRenderer).veil$getCullFrustum();}
    public static NecromancerRenderer getNecromancerRenderer(){return NecromancerRenderDispatcher.getRenderer();}

    public static void bootstrap(){
        if(bootstrapped)return;bootstrapped=true;
        VeilEventPlatform.INSTANCE.onVeilShaderCompile((manager,programs)->SHADER_BUFFER_CACHE.onShaderCompile(programs));
        VeilEventPlatform.INSTANCE.onVeilRenderLevelStageFinal((stage,context)->{
            if(stage==VeilRenderLevelStageEvent.Stage.END_MAIN){
                foundry.veil.api.client.render.rendertype.VeilRenderType.drawPending(stage,context);
                foundry.veil.impl.quasar.QuasarParticleHandler.renderPending(context);
                if(!VeilLevelPerspectiveRenderer.isRenderingPerspective()){
                    var profiler=net.minecraft.util.profiling.Profiler.get();
                    if(drawLights(profiler,VeilRenderBridge.create(context.camera().cullFrustum)))compositeLights(profiler);
                    renderPost(stage);
                }
            }
        });
        VeilEventPlatform.INSTANCE.onVeilDynamicBuffersChanged(change->{
            if(change.hasChanged(DynamicBufferType.NORMAL))rebuildChunks();
        });
        VeilEventPlatform.INSTANCE.onVeilRegisterGlobalControllers(registry->registry.registerGlobalController(RandomnessController.INSTANCE));
    }
    public static void init(){
        var client=Minecraft.getInstance();
        if(!(client.getResourceManager() instanceof ReloadableResourceManager resources))throw new IllegalStateException("Veil needs the actual reloadable resource manager");
        renderer=new VeilRenderer(resources,client.getWindow());
        foundry.veil.api.client.render.rendertype.VeilRenderType.bindUniformBlocks(UNIFORM_BLOCK_STATE);
    }
    public static void beginFrame(){SHADER_BUFFER_CACHE.bind();}
    public static void endFrame(){
        if(renderer==null)return;
        foundry.veil.impl.quasar.QuasarParticleHandler.endFrame();
        renderer.endFrame();VeilRenderProfilerImpl.endFrame();UNIFORM_BLOCK_STATE.clearUsedBindings();
        renderer.getVanillaShaderCompiler().removeRetiredBases();
    }
    public static void clearShaderBlocks(){UNIFORM_BLOCK_STATE.clear();}
    public static void shaderUpdate(){selectedShader=null;}
    public static void resize(int width,int height){if(renderer!=null)renderer.resize(width,height);}
    public static void renderPost(@Nullable VeilRenderLevelStageEvent.Stage stage){
        if(stage==null||stage==VeilRenderLevelStageEvent.Stage.END_MAIN)foundry.veil.impl.client.render.pipeline.VeilBloomRenderer.flush();
        renderer().getPostProcessingManager().runDefaultPipeline(stage);
    }
    public static boolean drawLights(ProfilerFiller profiler,CullFrustum frustum){
        return drawLights(profiler,frustum,getMainFramebuffer());
    }
    public static boolean drawLights(ProfilerFiller profiler,CullFrustum frustum,GpuFramebuffer sceneSource){
        var target=renderer().getFramebufferManager().getFramebuffer(VeilFramebuffers.LIGHT);if(target==null)return false;
        profiler.push("draw_lights");try{return renderer().getLightRenderer().render(frustum,target,sceneSource);}finally{profiler.pop();}
    }
    public static void compositeLights(ProfilerFiller profiler){
        var manager=renderer().getPostProcessingManager();var pipeline=manager.getPipeline(VeilRenderer.COMPOSITE);
        if(pipeline!=null){profiler.push("composite_lights");try{manager.runPipeline(pipeline);}finally{profiler.pop();}}
    }
    public static void clearLevel(){NecromancerRenderDispatcher.delete();VoxelShadowGrid.clearLevel();}
    public static void close(){
        selectedShader=null;
        if(renderer!=null){
            // These owners unregister their framebuffers from the live renderer.
            // GameRenderer.close may run later and can safely call free again.
            foundry.veil.impl.client.render.pipeline.VeilFirstPersonRenderer.free();
            foundry.veil.impl.client.render.pipeline.VeilBloomRenderer.free();
            renderer.free();renderer=null;
        }
        if(mainTarget!=null){mainTarget.close();mainTarget=null;}
        VoxelShadowGrid.close();SHADER_BUFFER_CACHE.free();UNIFORM_BLOCK_STATE.clear();RenderStyleRegistry.freeRenderStyles();
    }
    private VeilRenderSystem(){}
}
