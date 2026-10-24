import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.device.GpuDevice;
import com.mojang.renderpearl.api.pipeline.*;
import foundry.veil.api.client.render.gpu.*;
import foundry.veil.api.client.render.shader.*;
import foundry.veil.api.client.render.shader.program.ShaderUniformCache;
import foundry.veil.api.client.render.texture.TextureFilter;
import io.github.ocelot.glslprocessor.api.GlslParser;
import io.github.ocelot.glslprocessor.api.node.variable.GlslVariableNode;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.Identifier;
import net.minecraft.util.profiling.InactiveProfiler;
import org.joml.Vector4f;

import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** Discover actual sampled-image declarations, preserving explicit typed texture ownership across native reload. */
public final class GpuManagedSamplerAcceptance {
    public static void run(Minecraft client,GpuDevice device,List<String> checks) {
        var definitions=new ShaderPreDefinitions();
        class Managed extends ShaderManager {
            Managed(){super(device,ShaderManager.PROGRAM_SET,definitions);}
            void load(){var resources=client.getResourceManager();this.apply(this.prepare(resources,InactiveProfiler.INSTANCE),resources,InactiveProfiler.INSTANCE);}
        }
        try(var shaders=new Managed();
            var input=GpuFramebuffer.builder("actual discovered sampled-image input",2,2).color(GpuFormat.RGBA8_UNORM).build(device);
            var output=GpuFramebuffer.builder("actual discovered sampled-image output",2,2).color(GpuFormat.RGBA8_UNORM).build(device);
            var second=GpuFramebuffer.builder("actual renamed sampled-image output",2,2).color(GpuFormat.RGBA8_UNORM).build(device)) {
            var rename=new AtomicBoolean();var invalid=new AtomicBoolean();
            shaders.addPreprocessorRegistry((resources,registry)->registry.addPreprocessor((context,tree)->{
                if(!context.isSourceFile()||!context.isFragment())return;
                if(rename.get()) {
                    tree.field("AutoSampler").orElseThrow().setName("ChangedSampler");
                    for(var node:tree.getBody())node.stream().forEach(child->{if(child instanceof GlslVariableNode variable&&variable.getName().equals("AutoSampler"))variable.setName("ChangedSampler");});
                }
                if(invalid.get())tree.mainFunction().orElseThrow().getBody().add(GlslParser.parseExpression("fragColor *= MissingNativeSamplerEdit"));
            },false));
            shaders.setGlobal(program->{
                check(program.resourceDefinition().orElseThrow().samplers().isEmpty(),"Sampler fixture must not declare JSON texture bindings");
                String sampler=program.hasUniform("ChangedSampler")?"ChangedSampler":"AutoSampler";
                check(program.bindingTypes().get(sampler)==UniformType.COMBINED_IMAGE_SAMPLER
                                &&new ShaderUniformCache(program).hasSampler(sampler),
                        "Actual sampled-image AST declaration must enter the genuine native binding schema and metadata");
                program.getUniform("Tint").setVector(.5f,1,.25f,1);
                program.setTexture(sampler,input.color(0).view(),TextureFilter.CLAMP);
            });
            var encoder=device.createCommandEncoder();input.clear(encoder,new Vector4f(.4f,.5f,1,1),0);encoder.submit();
            shaders.load();var id=Identifier.parse("veil:gpu_managed_sampler");
            var state=RenderPipeline.builder().withLocation(Identifier.parse("veil_test:managed_sampler"))
                    .withVertexShader(Identifier.parse("veil:core/dynamic_coverage")).withFragmentShader(Identifier.parse("veil:core/gpu_managed_sampler"))
                    .withPrimitiveTopology(PrimitiveTopology.TRIANGLES).withCull(false).withColorTargetState(ColorTargetState.DEFAULT).build();
            var original=shaders.getShader(id,state,Runnable::run);draw(device,original,output);assertColor(device,output,.2f,.5f,.25f,1);
            var oldSampler=original.snapshotTextures().get("AutoSampler").sampler();
            try(var oldLease=original.retain()) {
                invalid.set(true);shaders.scheduleRecompile(id);
                try{shaders.getShader(id,state,Runnable::run);throw new AssertionError("Invalid actual sampler-source edit replaced native program");}
                catch(RuntimeException expected){check(!oldSampler.isClosed(),"Failed native source refresh retired working sampled resources");}
                check(shaders.getShader(id,state,Runnable::run)==original,"Failed sampled-image edit retried without another source revision");
                draw(device,original,output);assertColor(device,output,.2f,.5f,.25f,1);
                invalid.set(false);rename.set(true);shaders.scheduleRecompile(id);
                var replacement=shaders.getShader(id,state,Runnable::run);
                check(new ShaderUniformCache(replacement).hasSampler("ChangedSampler")&&!new ShaderUniformCache(replacement).hasSampler("AutoSampler")
                                &&replacement!=original&&!oldSampler.isClosed(),
                        "A valid changed source must expose only its real new sampled-image name while a queued original owner retains its old sampler");
                checks.add("real sampler declaration absent JSON generates native combined-image binding and ShaderUniformCache metadata, actual typed view/sampler produces exact pixels, invalid native source preserves old owner/pixels without repeated retries, and valid sampler rename creates fresh metadata without stale names");
                replacement.getUniform("Tint").setVector(.25f,.5f,1,1);
                var newSampler=replacement.snapshotTextures().get("ChangedSampler").sampler();
                try(var newLease=replacement.retain()) {
                    encoder=device.createCommandEncoder();original.prepare(encoder);replacement.prepare(encoder);
                    try(var pass=output.beginPass(encoder)){original.bind(pass);pass.draw(3,1,0,0);}
                    try(var pass=second.beginPass(encoder)){replacement.bind(pass);pass.draw(3,1,0,0);}
                    shaders.close();check(!oldSampler.isClosed()&&!newSampler.isClosed(),"Retired native program leases closed queued sampled resources before submission");
                    encoder.submit();assertColor(device,output,.2f,.5f,.25f,1);assertColor(device,second,.1f,.25f,1,1);
                }
                check(newSampler.isClosed()&&!oldSampler.isClosed(),"Releasing renamed draw owner must close only its own actual sampler");
            }
            check(oldSampler.isClosed()&&!input.color(0).view().isClosed(),"Final native draw owner must close its sampler while retaining the caller's borrowed image");
            checks.add("queued original and renamed native program owners retain distinct real sampled resources and typed field values through manager retirement/submission; independent draw lease release closes each owned sampler while preserving borrowed texture storage");
        }
    }
    private static void draw(GpuDevice device,GpuShaderProgram program,GpuFramebuffer output){
        var encoder=device.createCommandEncoder();program.prepare(encoder);try(var pass=output.beginPass(encoder)){program.bind(pass);pass.draw(3,1,0,0);}encoder.submit();
    }
    private static void assertColor(GpuDevice device,GpuFramebuffer target,float... expected){
        try(var read=device.createBuffer(()->"discovered native sampler pixel readback",GpuBuffer.USAGE_COPY_DST|GpuBuffer.USAGE_MAP_READ,16)){
            var encoder=device.createCommandEncoder();encoder.copyTextureToBuffer(target.color(0).texture(),read,0,()->{},0);
            try(var fence=encoder.createFence()){encoder.submit();check(fence.awaitCompletion(10_000_000_000L),"Native sampled-image readback timed out");
                try(var mapped=read.map(true,false)){for(int index=0;index<16;index++){float actual=(mapped.data().get(index)&255)/255f;check(Math.abs(actual-expected[index%4])<.005f,"Native sampled-image component "+index+" was "+actual+", expected "+expected[index%4]);}}
            }
        }
    }
    private static void check(boolean value,String message){if(!value)throw new AssertionError(message);}
}
