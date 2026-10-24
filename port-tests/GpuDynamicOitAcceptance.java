import com.google.gson.GsonBuilder;
import com.mojang.blaze3d.ProjectionType;
import com.mojang.blaze3d.pipeline.PipelineCache;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.commands.*;
import com.mojang.renderpearl.api.device.GpuDevice;
import com.mojang.renderpearl.api.pipeline.*;
import com.mojang.renderpearl.api.textures.*;
import foundry.veil.api.client.render.dynamicbuffer.DynamicBufferType;
import foundry.veil.api.client.render.gpu.*;
import foundry.veil.api.client.render.shader.ShaderPreDefinitions;
import foundry.veil.impl.client.render.dynamicbuffer.*;
import foundry.veil.impl.client.render.shader.processor.NativeShaderProcessor;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.oit.*;
import net.minecraft.resources.Identifier;
import org.joml.Matrix4f;
import org.joml.Vector4f;

import java.nio.ByteOrder;
import java.nio.file.*;
import java.util.*;

/** Test-only: genuine native OIT algorithms, generic vec4 payloads, and automatic production interception. */
public final class GpuDynamicOitAcceptance implements ClientModInitializer {
    private int ticks;
    private boolean ran;
    @Override public void onInitializeClient() {
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (this.ran || ++this.ticks < 5 || !client.isGameLoadFinished()) return;
            this.ran = true; var report = new LinkedHashMap<String,Object>(); var checks = new ArrayList<String>(); report.put("checks",checks);
            try {
                var device = RenderSystem.getDevice(); report.put("backend",device.getDeviceInfo().backendName()); report.put("device",device.getDeviceInfo().name());
                check(device.getDeviceInfo().backendName().toLowerCase(Locale.ROOT).contains("vulkan"),"Real Vulkan required");
                if (System.getProperty("veil.oit.scope","component").equals("integration")) integration(client,device,checks);
                else component(client,device,checks);
                report.put("cases",checks.size()); report.put("status","passed");
            } catch (Throwable failure) { failure.printStackTrace();report.put("status","failed");report.put("error",failure.toString()); }
            finally {
                try { Files.writeString(Path.of(System.getProperty("veil.oit.report")),new GsonBuilder().setPrettyPrinting().create().toJson(report)+"\n"); }
                catch(Exception failure){throw new RuntimeException(failure);} client.stop();
            }
        });
    }
    private static final int ALL=DynamicBufferType.encode(DynamicBufferType.values());
    private static final Vector4f OPAQUE=new Vector4f(.125f,.25f,.375f,.5f);
    private static final Vector4f PAYLOAD=new Vector4f(-2,3,.75f,-4);
    private static OitPipelineSet pipelines(String suffix, boolean additive, boolean unsupported) {
        var builder=RenderPipeline.builder().withVertexShader(Identifier.parse("veil:core/gpu_oit")).withFragmentShader(Identifier.parse("veil:core/gpu_oit"))
                .withPrimitiveTopology(PrimitiveTopology.TRIANGLES).withCull(false)
                .withBindGroupLayout(BindGroupLayout.builder().withUniform("Surface",UniformType.UNIFORM_BUFFER).build());
        if(additive)builder.withShaderDefine("OIT_ADDITIVE"); if(unsupported)builder.withShaderDefine("NO_PROPERTIES");
        return OitPipelineSet.builder("veil_oit_acceptance_"+suffix,builder).withoutDepthTest().build();
    }
    private static GpuShaderSource processed(Minecraft client,GpuDevice device,RenderPipeline original) {
        var processor=NativeShaderProcessor.forMinecraft(device,client.getResourceManager(),new ShaderPreDefinitions(),original,ALL,
                List.of((resources,registry)->registry.addPreprocessor(new DynamicBufferProcessor(),false),
                        (resources,registry)->registry.addPreprocessor(new NativeDynamicOitProcessor(),false)));
        return new GpuShaderSource(client.getResourceManager(),null,"shaders/include",Map.of(),processor);
    }
    private static GpuUniformBuffer surface(GpuDevice device,String label,Vector4f payload,float alpha) {
        var surface=new GpuUniformBuffer(device,label,GpuUniformBuffer.layoutBuilder().field("Color",GpuUniformBuffer.ValueType.VEC4)
                .field("Payload",GpuUniformBuffer.ValueType.VEC4).field("DeviceDepth",GpuUniformBuffer.ValueType.FLOAT).build());
        surface.setVector("Color",.25f,.5f,.75f,alpha);surface.setVector("Payload",payload.x,payload.y,payload.z,payload.w);surface.setFloat("DeviceDepth",.5f);return surface;
    }
    private static void component(Minecraft client,GpuDevice device,List<String> checks) {
        var set=pipelines("component",false,false);var additive=pipelines("additive",true,false);
        try(var owner=new NativeDynamicOit(device,client.getResourceManager(),2,2,ALL);
            var frame=owner.retain();var targets=new Targets(device);
            var rawSource=new GpuShaderSource(client.getResourceManager());var nativeCache=new PipelineCache(device,rawSource);
            var source=processed(client,device,set.accumulatePipeline());var program=new GpuShaderProgram(device,frame.pipeline(set.accumulatePipeline()),source,Runnable::run);
            var addSource=processed(client,device,additive.accumulatePipeline());var addProgram=new GpuShaderProgram(device,frame.pipeline(additive.accumulatePipeline()),addSource,Runnable::run);
            var surface=surface(device,"native ordinary OIT surface",PAYLOAD,.5f)) {
            check(frame.pipeline(set.accumulatePipeline()).getColorTargetStates().size()==8,"All five properties need native color plus five vec4 accumulators and two packed weight targets");
            var encoder=device.createCommandEncoder();surface.upload(encoder);targets.clear(encoder);frame.clear(encoder);
            stages(targets,set,nativeCache,surface);
            var descriptor=RenderPassDescriptor.builder(()->"native accumulation with typed properties").withColorAttachment(targets.accum.color(0).view()).withDepthAttachment(targets.main.depth().view()).build();
            try(var pass=encoder.createRenderPass(frame.extend(descriptor))){program.setUniformBlock("Surface",surface.buffer().slice());program.setUniformBlock("Projection",targets.projection.buffer().slice());bindOit(program,targets);program.bind(pass);pass.draw(3,1,0,0);}
            nativeComposite(targets,nativeCache);frame.compose(encoder,targets::property,targets.accum.color(0).view(),targets.bounds.color(0).view(),targets.coefficients());encoder.submit();
            var nativePixel=readPixel(device,targets.reference.color(0).texture());float coverage=nativePixel.z/.75f;
            check(coverage>.1f&&coverage<.9f,"Native composite fixture produced no fractional coverage: "+nativePixel);
            float nativeWeight=readPixel(device,targets.accum.color(0).texture()).w;
            assertVector(device,frame.texture(DynamicBufferType.DEBUG).texture(),new Vector4f(PAYLOAD).mul(nativeWeight),.01f);
            assertVector(device,frame.producedWeights(1).texture(),new Vector4f(nativeWeight,0,0,0),.005f);
            assertVector(device,targets.property(DynamicBufferType.DEBUG).texture(),blend(PAYLOAD,OPAQUE,coverage),.025f);
            assertVector(device,targets.property(DynamicBufferType.ALBEDO).texture(),blend(new Vector4f(.25f,.5f,.75f,.25f),OPAQUE,coverage),.015f);
            checks.add("real native depth-bounds/transmittance/accumulation and resolve preserve arbitrary negative/>1 debug vec4 including w; all five properties fit eight attachments and packed produced weights match native coverage");
            encoder=device.createCommandEncoder();targets.clear(encoder);frame.clear(encoder);stages(targets,additive,nativeCache,surface);
            try(var pass=encoder.createRenderPass(frame.extend(descriptor))){addProgram.setUniformBlock("Surface",surface.buffer().slice());addProgram.setUniformBlock("Projection",targets.projection.buffer().slice());bindOit(addProgram,targets);addProgram.bind(pass);pass.draw(3,1,0,0);}
            frame.compose(encoder,targets::property,targets.accum.color(0).view(),targets.bounds.color(0).view(),targets.coefficients());encoder.submit();
            assertVector(device,targets.property(DynamicBufferType.DEBUG).texture(),new Vector4f(PAYLOAD).mul(.5f).add(OPAQUE),.02f);
            assertVector(device,frame.producedWeights(1).texture(),new Vector4f(0),.005f);
            checks.add("genuine native additive stage contributes weighted generic payload while produced coverage stays zero and opaque property remains unattenuated");
            var old=frame.texture(DynamicBufferType.DEBUG);try(var nested=owner.retain()) {check(nested.texture(DynamicBufferType.DEBUG)!=old,"Concurrent same-size OIT frames alias storage");owner.configure(4,2,DynamicBufferType.DEBUG.getMask());owner.close();check(!old.isClosed()&&!nested.texture(DynamicBufferType.DEBUG).isClosed(),"Owner retirement closed retained frames");}
            check(!old.isClosed(),"Closing newer frame retired old storage");
            checks.add("same-size nested OIT generations and resized owner retirement retain independent live storage until each submitted frame lease closes");
        }
    }
    private static void stages(Targets targets,OitPipelineSet set,PipelineCache cache,GpuUniformBuffer surface) {
        for(var stage:List.of(OitStage.DEPTH_BOUNDS,OitStage.TRANSMITTANCE)) try(var pass=OitRenderPassProvider.createRenderPass(stage,()->"actual native OIT fixture",targets.parameters())) {
            pass.setPipeline(cache.get(set.getPipeline(stage)));pass.setUniform("Surface",surface.buffer().slice());pass.setUniform("Projection",targets.projection.buffer().slice());pass.draw(3,1,0,0);
        }
    }
    private static void bindOit(GpuShaderProgram program,Targets targets) {
        if(program.hasUniform("Globals"))program.setUniformBlock("Globals",RenderSystem.getGlobalSettingsUniform().slice());
        var sampler=RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST);
        if(program.hasUniform("DepthBoundsSampler"))program.setTexture("DepthBoundsSampler",targets.bounds.color(0).view(),sampler);
        for(int i=0;i<2;i++)if(program.hasUniform("Coeff"+i))program.setTexture("Coeff"+i,targets.coefficients()[i],sampler);
    }
    private static void nativeComposite(Targets targets,PipelineCache cache) {
        var encoder=RenderSystem.getDevice().createCommandEncoder();var sampler=RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST);
        try(var pass=encoder.createRenderPass(RenderPassDescriptor.builder(()->"genuine vanilla OIT composite coverage oracle")
                .withColorAttachment(targets.reference.color(0).view()).withDepthAttachment(targets.reference.depth().view()).build())) {
            pass.setPipeline(cache.get(RenderPipelines.OIT_COMPOSITE));RenderSystem.bindDefaultUniforms(pass);pass.setUniform("Projection",targets.projection.buffer().slice());
            pass.setUniform("Sampler0",targets.accum.color(0).view(),sampler);pass.setUniform("DepthBoundsSampler",targets.bounds.color(0).view(),sampler);
            for(int i=0;i<2;i++)pass.setUniform("Coeff"+i,targets.coefficients()[i],sampler);pass.draw(3,1,0,0);
        }
    }
    private static void integration(Minecraft client,GpuDevice device,List<String> checks) {
        var compiler=new VanillaShaderCompiler(device,Runnable::run,Runnable::run);
        var manager=new DynamicBufferManager(device,client.getResourceManager(),2,2,compiler,event->{});
        var set=pipelines("integrated",false,false);var unsupported=pipelines("unsupported",false,true);
        NativeDynamicBuffers.bindRuntime(manager);
        try(compiler;manager;var targets=new Targets(device);var rawSource=new GpuShaderSource(client.getResourceManager());
            var cache=new PipelineCache(device,rawSource);var surface=surface(device,"production automatic OIT surface",PAYLOAD,.5f)) {
            manager.setActiveBuffers(Identifier.parse("veil_test:oit"),ALL);
            for(var pipelines:List.of(set,unsupported))for(var stage:OitStage.values())cache.get(pipelines.getPipeline(stage));
            var encoder=device.createCommandEncoder();surface.upload(encoder);targets.clear(encoder);
            GpuTextureView debug;
            try(var scope=NativeDynamicBuffers.beginWorld(targets.main.color(0).view(),targets.main.depth().view())) {
                for(var type:DynamicBufferType.values())encoder.clearColorTexture(manager.getBufferTexture(type).texture(),OPAQUE);
                automaticStages(targets,set,cache,surface);
                nativeComposite(targets,cache);
                NativeDynamicBuffers.composeOit(targets.accum.color(0).view(),targets.bounds.color(0).view(),targets.coefficients());
                debug=manager.getBufferTexture(DynamicBufferType.DEBUG);
                encoder.submit();
                var nativePixel=readPixel(device,targets.reference.color(0).texture());float coverage=nativePixel.z/.75f;
                check(coverage>.1f&&coverage<.9f,"Automatic native composite produced no fractional coverage: "+nativePixel);
                assertVector(device,debug.texture(),blend(PAYLOAD,OPAQUE,coverage),.025f);
                assertVector(device,manager.getBufferTexture(DynamicBufferType.NORMAL).texture(),blend(new Vector4f(-.5f,.25f,.5f,-.25f),OPAQUE,coverage),.015f);
                assertVector(device,manager.getBufferTexture(DynamicBufferType.LIGHT_UV).texture(),blend(new Vector4f(.75f,.25f,0,1),OPAQUE,coverage),.015f);
                assertVector(device,manager.getBufferTexture(DynamicBufferType.LIGHT_COLOR).texture(),blend(new Vector4f(.75f,.5f,.25f,.5f),OPAQUE,coverage),.015f);
            }
            check(compiler.variantCount()==1,"Only the actual native ACCUMULATE stage should acquire a dynamic variant");
            checks.add("actual native OIT provider and frontend/pass mixins automatically append five properties plus packed weights, compile real stage variants and resolve expected generic vec4/normal/light pixels without fixture attachment or pipeline adaptation");
            encoder=device.createCommandEncoder();targets.clear(encoder);
            var reads=new ArrayList<GpuBuffer>();var views=new ArrayList<GpuTextureView>();
            try {
                try(var scope=NativeDynamicBuffers.beginWorld(targets.main.color(0).view(),targets.main.depth().view())) {
                    for(var type:DynamicBufferType.values())encoder.clearColorTexture(manager.getBufferTexture(type).texture(),OPAQUE);
                    automaticStages(targets,unsupported,cache,surface);
                    NativeDynamicBuffers.composeOit(targets.accum.color(0).view(),targets.bounds.color(0).view(),targets.coefficients());
                    for(var type:DynamicBufferType.values()) {
                        var view=manager.getBufferTexture(type);views.add(view);int size=type==DynamicBufferType.DEBUG?32:type==DynamicBufferType.LIGHT_UV?8:16;
                        var read=device.createBuffer(()->"unsupported native OIT preservation",GpuBuffer.USAGE_COPY_DST|GpuBuffer.USAGE_MAP_READ,size);reads.add(read);encoder.copyTextureToBuffer(view.texture(),read,0,()->{},0);
                    }
                    manager.resizeFramebuffers(4,4);manager.close();compiler.close();
                }
                check(views.stream().noneMatch(GpuTextureView::isClosed),"World/manager retirement closed queued native OIT targets before submit");
                try(var fence=encoder.createFence()){encoder.submit();check(fence.awaitCompletion(10_000_000_000L),"Automatic OIT retirement readback timeout");}
                check(views.stream().allMatch(GpuTextureView::isClosed)&&compiler.retirement().isDone(),"Submission did not retire exact OIT world targets/pipelines");
                for(int t=0;t<DynamicBufferType.values().length;t++)try(var mapped=reads.get(t).map(true,false)) {
                    var type=DynamicBufferType.values()[t];var data=mapped.data().order(ByteOrder.nativeOrder());boolean half=type==DynamicBufferType.DEBUG;int components=type==DynamicBufferType.LIGHT_UV?2:4;
                    for(int p=0;p<4;p++)for(int c=0;c<components;c++){int i=p*components+c;float actual=half?Float.float16ToFloat(data.getShort(i*2)):type==DynamicBufferType.NORMAL?data.get(i)/127f:(data.get(i)&255)/255f;
                        check(Math.abs(actual-OPAQUE.get(c))<.015f,"Unsupported native OIT shader changed "+type+" component "+c+": "+actual);}
                }
                check(!targets.main.color(0).view().isClosed()&&!targets.bounds.color(0).view().isClosed(),"OIT manager closed borrowed native targets");
            } finally {reads.forEach(GpuBuffer::close);}
            checks.add("transparent shaders with no produced properties preserve every opaque property through actual automatic native OIT resolve; resize/manager close retain queued resources through shared encoder submit and preserve borrowed native attachments");
        } finally {NativeDynamicBuffers.unbindRuntime(manager);}
    }
    private static void automaticStages(Targets targets,OitPipelineSet set,PipelineCache cache,GpuUniformBuffer surface) {
        for(var stage:OitStage.values())try(var pass=OitRenderPassProvider.createRenderPass(stage,()->"unmodified native OIT provider fixture",targets.parameters())) {
            pass.setPipeline(cache.get(set.getPipeline(stage)));pass.setUniform("Surface",surface.buffer().slice());pass.setUniform("Projection",targets.projection.buffer().slice());pass.draw(3,1,0,0);
        }
    }
    private static final class Targets implements AutoCloseable {
        final GpuFramebuffer main,bounds,coeff,accum,properties,reference;final GpuUniformBuffer projection;
        Targets(GpuDevice device){main=GpuFramebuffer.builder("borrowed opaque world",2,2).color(GpuFormat.RGBA8_UNORM).depth(GpuFormat.D32_FLOAT).build(device);
            bounds=GpuFramebuffer.builder("genuine native depth bounds",2,2).color(GpuFormat.RGBA32_FLOAT).build(device);
            coeff=GpuFramebuffer.builder("genuine native coefficient targets",2,2).color(GpuFormat.RGBA16_FLOAT).color(GpuFormat.RGBA16_FLOAT).build(device);
            accum=GpuFramebuffer.builder("genuine native vanilla accumulation",2,2).color(GpuFormat.RGBA16_FLOAT).build(device);
            reference=GpuFramebuffer.builder("genuine vanilla composite oracle",2,2).color(GpuFormat.RGBA8_UNORM).depth(GpuFormat.D32_FLOAT).build(device);
            var builder=GpuFramebuffer.builder("final opaque property targets",2,2);for(var type:DynamicBufferType.values())builder.color(type.gpuFormat());properties=builder.build(device);
            projection=new GpuUniformBuffer(device,"native OIT fixture projection",GpuUniformBuffer.layoutBuilder().field("ProjMat",GpuUniformBuffer.ValueType.MAT4).build());
            projection.setMatrix("ProjMat",new Matrix4f().zero().m00(1).m11(1).m23(-1).m32(1));}
        GpuTextureView[] coefficients(){return new GpuTextureView[]{coeff.color(0).view(),coeff.color(1).view()};}
        OitRenderPassProvider.Parameters parameters(){return new OitRenderPassProvider.Parameters(bounds.color(0).view(),coefficients(),accum.color(0).view(),main.depth().view());}
        GpuTextureView property(DynamicBufferType type){return properties.color(type.ordinal()).view();}
        void clear(CommandEncoder encoder){projection.upload(encoder);main.clear(encoder,new Vector4f(0),0);reference.clear(encoder,new Vector4f(0),0);bounds.clear(encoder,new Vector4f(-Float.MAX_VALUE,0,0,0),0);coeff.clear(encoder,new Vector4f(0),0);accum.clear(encoder,new Vector4f(0),0);properties.clear(encoder,OPAQUE,0);}
        @Override public void close(){projection.close();properties.close();reference.close();accum.close();coeff.close();bounds.close();main.close();}
    }
    private static Vector4f blend(Vector4f value,Vector4f opaque,float alpha){return new Vector4f(value).mul(alpha).fma(1-alpha,opaque);}
    private static Vector4f readPixel(GpuDevice device,GpuTexture texture) {
        boolean half=texture.getFormat()==GpuFormat.RGBA16_FLOAT;int bytes=half?32:16;
        try(var read=device.createBuffer(()->"genuine native OIT composite oracle pixel",GpuBuffer.USAGE_COPY_DST|GpuBuffer.USAGE_MAP_READ,bytes)) {
            var encoder=device.createCommandEncoder();encoder.copyTextureToBuffer(texture,read,0,()->{},0);try(var fence=encoder.createFence()){encoder.submit();check(fence.awaitCompletion(10_000_000_000L),"Native oracle readback timeout");
                try(var mapped=read.map(true,false)){var data=mapped.data().order(ByteOrder.nativeOrder());var value=new Vector4f(0);for(int c=0;c<4;c++)value.setComponent(c,half?Float.float16ToFloat(data.getShort(c*2)):(data.get(c)&255)/255f);return value;}
            }
        }
    }
    private static void assertVector(GpuDevice device,GpuTexture texture,Vector4f expected,float tolerance) {
        int components=texture.getFormat()==GpuFormat.RG8_UNORM?2:4;boolean half=texture.getFormat()==GpuFormat.RGBA16_FLOAT;int bytes=4*components*(half?2:1);
        try(var read=device.createBuffer(()->"native OIT exact property readback",GpuBuffer.USAGE_COPY_DST|GpuBuffer.USAGE_MAP_READ,bytes)) {
            var encoder=device.createCommandEncoder();encoder.copyTextureToBuffer(texture,read,0,()->{},0);try(var fence=encoder.createFence()){encoder.submit();check(fence.awaitCompletion(10_000_000_000L),"OIT readback timeout");
                try(var mapped=read.map(true,false)){var data=mapped.data().order(ByteOrder.nativeOrder());for(int p=0;p<4;p++)for(int c=0;c<components;c++){
                    int i=p*components+c;float actual=half?Float.float16ToFloat(data.getShort(i*2)):texture.getFormat()==GpuFormat.RGBA8_SNORM?data.get(i)/127f:(data.get(i)&255)/255f;
                    check(Math.abs(actual-expected.get(c))<tolerance,"OIT "+texture.getFormat()+" pixel "+p+" channel "+c+" was "+actual+", expected "+expected.get(c));
                }}
            }
        }
    }
    private static void check(boolean value,String message){if(!value)throw new AssertionError(message);}
}
