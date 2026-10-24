package foundry.veil.impl.client.render.pipeline;

import foundry.veil.Veil;
import foundry.veil.api.client.render.VeilRenderSystem;
import foundry.veil.api.client.render.framebuffer.VeilFramebuffers;
import foundry.veil.api.client.render.gpu.NativeFirstPersonPass;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.Identifier;

/** Hand rendering uses a caller-color copy and a real independent native depth attachment. */
public final class VeilFirstPersonRenderer {
    private static final Identifier FIRST_PERSON=Veil.veilPath("core/first_person");
    private static NativeFirstPersonPass pass;
    public static NativeFirstPersonPass.Scope begin(){
        if(pass==null)pass=new NativeFirstPersonPass();
        var scope=pass.begin(Minecraft.getInstance().gameRenderer.mainRenderTarget(),target->{
            var client=Minecraft.getInstance();var profiler=net.minecraft.util.profiling.Profiler.get();
            boolean lights=VeilRenderSystem.drawLights(profiler,VeilRenderSystem.getCullingFrustum(),target);
            if(lights)VeilRenderSystem.compositeLights(profiler);
            var manager=VeilRenderSystem.renderer().getPostProcessingManager();
            var pipeline=manager.getPipeline(FIRST_PERSON);
            if(pipeline!=null)manager.runPipeline(pipeline,false);
        });
        VeilRenderSystem.renderer().getFramebufferManager().setFramebuffer(VeilFramebuffers.FIRST_PERSON,scope.framebuffer());
        return scope;
    }
    public static void free(){
        if(pass!=null){VeilRenderSystem.renderer().getFramebufferManager().removeFramebuffer(VeilFramebuffers.FIRST_PERSON);pass.close();pass=null;}
    }
    private VeilFirstPersonRenderer(){}
}
