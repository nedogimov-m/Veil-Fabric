import com.google.gson.GsonBuilder;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.pipeline.PipelineCache;
import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.commands.RenderPassDescriptor;
import com.mojang.renderpearl.api.device.GpuDevice;
import com.mojang.renderpearl.api.pipeline.*;
import foundry.veil.api.client.render.gpu.*;
import foundry.veil.api.client.render.shader.*;
import foundry.veil.api.client.render.shader.texture.ShaderTextureSource;
import foundry.veil.impl.client.render.shader.injection.*;
import foundry.veil.impl.client.render.shader.processor.NativeShaderProcessor;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.*;
import org.joml.Vector4f;

import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

/** Test-only: real JSON redirects/replacement programs, native state and explicit resource retention. */
public final class GpuShaderInjectionAcceptance implements ClientModInitializer {
    private int ticks; private boolean ran;
    @Override public void onInitializeClient() {
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if(this.ran||++this.ticks<5||!client.isGameLoadFinished())return;
            this.ran=true;var report=new LinkedHashMap<String,Object>();var checks=new ArrayList<String>();report.put("checks",checks);
            try{var device=RenderSystem.getDevice();report.put("backend",device.getDeviceInfo().backendName());report.put("device",device.getDeviceInfo().name());
                check(device.getDeviceInfo().backendName().toLowerCase(Locale.ROOT).contains("vulkan"),"Real Vulkan required");
                switch(System.getProperty("veil.injection.scope","component")) {
                    case "reload" -> reloadSnapshots(client,device,checks);
                    case "integration" -> integration(client,device,checks);
                    default -> component(client,device,checks);
                }report.put("cases",checks.size());report.put("status","passed");
            }catch(Throwable failure){failure.printStackTrace();report.put("status","failed");report.put("error",failure.toString());}
            finally{try{Files.writeString(Path.of(System.getProperty("veil.injection.report")),new GsonBuilder().setPrettyPrinting().create().toJson(report)+"\n");}
                catch(Exception failure){throw new RuntimeException(failure);}client.stop();}
        });
    }
    private static RenderPipeline state(String fragment,boolean blend) {
        return RenderPipeline.builder().withLocation(Identifier.parse("veil_test:injected_native_"+fragment.substring(fragment.lastIndexOf('/')+1)))
                .withVertexShader(Identifier.parse("veil:core/gpu_injection_vertex")).withFragmentShader(Identifier.parse(fragment))
                .withPrimitiveTopology(PrimitiveTopology.TRIANGLES).withCull(false).withPolygonMode(PolygonMode.FILL)
                .withDepthStencilState(new DepthStencilState(CompareOp.GREATER_THAN_OR_EQUAL,true))
                .withColorTargetState(new ColorTargetState(blend?Optional.of(BlendFunction.TRANSLUCENT):Optional.empty(),GpuFormat.RGBA8_UNORM,ColorTargetState.WRITE_ALL)).build();
    }
    private static GpuPipeline compile(GpuDevice device,NativeShaderInjectionService.Plan plan,ShaderPreDefinitions definitions) {
        var processor=NativeShaderProcessor.forMinecraft(device,plan.resources(),definitions,plan.pipeline(),0,plan.registrars());
        try(var raw=plan.source();ShaderSource processed=new ShaderSource(){
            @Override public String getShader(Identifier id,ShaderType stage){return processor.process(id,stage,raw.getShader(id,stage));}
            @Override public CachedIncludeSource getInclude(Identifier id){return raw.getInclude(id);}
            @Override public void close(){}
        }) {return GpuPipeline.compile(device,plan.pipeline(),processed,Runnable::run);}
    }
    private static void component(Minecraft client,GpuDevice device,List<String> checks) throws Exception {
        var resources=client.getResourceManager();var definitions=new ShaderPreDefinitions();var manager=new ShaderInjectionManager();reload(manager,resources);
        try(var programs=new ShaderManager(device,ShaderManager.PROGRAM_SET,definitions);var originalSource=new GpuShaderSource(resources)) {
            reload(programs,resources);
            try(var service=new NativeShaderInjectionService(manager,programs::getDefinitions,definitions);
                var output=GpuFramebuffer.builder("native injection output",2,2).color(GpuFormat.RGBA8_UNORM).depth(GpuFormat.D32_FLOAT).build(device)) {
                var base=state("veil:core/gpu_injection",false);var plan=service.plan(base,originalSource,resources).orElseThrow();
                originalSource.close(); // CPU raw text is a plan recipe, not an alias to this closed include/source owner.
                try(var pipeline=compile(device,plan,definitions)) {
                    var encoder=device.createCommandEncoder();output.clear(encoder,new Vector4f(0),0);
                    try(var pass=encoder.createRenderPass(RenderPassDescriptor.builder(()->"actual JSON native source patches").withColorAttachment(output.color(0).view()).withDepthAttachment(output.depth().view()).build())) {
                        pipeline.bind(pass);plan.bind(pass);pass.draw(3,1,0,0);
                    }encoder.submit();assertPixels(device,output,64,128,128,255);
                }
                check(plan.pipeline()==base&&plan.retainFrame().isEmpty(),"Source-only modifications must preserve the original native state without requiring a replacement resource frame");
                checks.add("actual resource-loaded JSON root/include redirects modify native GPU pixels once across native source aliases; immutable raw source recipe recompiles after original source owner closes");
                // Construct a fresh source owner for the replacement plan; no original replacement fragment resource exists.
                try(var source=new GpuShaderSource(resources)) {
                    var original=state("veil:core/gpu_replaced",true);var replacement=service.plan(original,source,resources).orElseThrow();
                    var actual=replacement.pipeline();check(actual.getColorTargetStates().equals(original.getColorTargetStates())&&actual.getDepthStencilState().equals(original.getDepthStencilState())
                            &&actual.getVertexFormatBindings().equals(original.getVertexFormatBindings())&&actual.getPrimitiveTopology()==original.getPrimitiveTopology()&&actual.isCull()==original.isCull()
                            &&actual.getPolygonMode()==original.getPolygonMode()&&actual.pushConstantSize()==original.pushConstantSize(),"Replacement changed the original immutable native draw state");
                    check(replacement.definitionDependencies().contains("gain")&&actual.getShaderDefines().values().get("GAIN").equals("0.5"),"Replacement lost resource-definition macros/dependencies");
                    var closed=new AtomicInteger();
                    try{service.prepareFrame(request->new NativeShaderInjectionService.PreparedBindings(Map.of(),Map.of(),closed::incrementAndGet));throw new AssertionError("Incomplete native replacement resources accepted");}
                    catch(IllegalArgumentException expected){check(closed.get()==1,"Failed resource preparation leaked its exact acquired owner");}
                    try(var texture=GpuFramebuffer.builder("borrowed replacement source",2,2).color(GpuFormat.RGBA8_UNORM).build(device);
                        var nestedOutput=GpuFramebuffer.builder("nested replacement frame output",2,2).color(GpuFormat.RGBA8_UNORM).depth(GpuFormat.D32_FLOAT).build(device);
                        var sampler=device.createSampler(com.mojang.renderpearl.api.textures.AddressMode.CLAMP_TO_EDGE,com.mojang.renderpearl.api.textures.AddressMode.CLAMP_TO_EDGE,
                                com.mojang.renderpearl.api.textures.FilterMode.NEAREST,com.mojang.renderpearl.api.textures.FilterMode.NEAREST,1,OptionalDouble.empty());
                        var tint=new GpuUniformBuffer(device,"prepared replacement tint",GpuUniformBuffer.layoutBuilder().field("Tint",GpuUniformBuffer.ValueType.VEC4).build());
                        var innerTint=new GpuUniformBuffer(device,"nested prepared replacement tint",GpuUniformBuffer.layoutBuilder().field("Tint",GpuUniformBuffer.ValueType.VEC4).build());
                        var pipeline=compile(device,replacement,definitions)) {
                        tint.setVector("Tint",.5f,1,1,.5f);innerTint.setVector("Tint",1,0,0,1);
                        var encoder=device.createCommandEncoder();tint.upload(encoder);innerTint.upload(encoder);texture.clear(encoder,new Vector4f(.8f,.4f,.2f,1),0);
                        output.clear(encoder,new Vector4f(0,.25f,.5f,1),0);nestedOutput.clear(encoder,new Vector4f(0),0);
                        var frame=service.prepareFrame(request->new NativeShaderInjectionService.PreparedBindings(Map.of("ReplacementTint",tint.buffer().slice()),
                                Map.of("ReplacementInput",new NativePipelineBridge.Texture(texture.color(0).view(),sampler)),closed::incrementAndGet));
                        AutoCloseable queued=replacement.retainFrame().orElseThrow();
                        try(frame) {
                            try(var nested=service.prepareFrame(request->new NativeShaderInjectionService.PreparedBindings(Map.of("ReplacementTint",innerTint.buffer().slice()),
                                    Map.of("ReplacementInput",new NativePipelineBridge.Texture(texture.color(0).view(),sampler)),closed::incrementAndGet))) {
                                draw(device,nestedOutput,pipeline,replacement);
                            }
                            check(closed.get()==2,"Nested resource frame did not release independently");
                            draw(device,output,pipeline,replacement);
                        }
                        service.close();check(closed.get()==2,"Closing frame/service released a queued captured resource owner before submit");
                        try(var fence=encoder.createFence()){encoder.submit();check(fence.awaitCompletion(10_000_000_000L),"Native replacement submit timed out");}
                        queued.close();queued.close();check(closed.get()==3,"Submission lease did not retire its exact prepared resource owner once");
                        assertPixels(device,output,13,61,102,255);assertPixels(device,nestedOutput,51,0,0,128);
                        check(!texture.color(0).view().isClosed()&&!tint.buffer().isClosed()&&!sampler.isClosed(),"Replacement frame closed borrowed GPU resources");
                    }
                    checks.add("JSON replacement program binds explicitly prepared native UBO/texture resources and definition macros; original alpha blend/depth/vertex state survives replacement blend override and produces exact pixels");
                    checks.add("nested prepared replacement frames restore parent snapshots; failed preparation rolls back ownership; frame/service retirement preserves retained queued resources until submit and releases each owner once without closing borrowed resources");
                }
            }
        }
    }
    private static void draw(GpuDevice device,GpuFramebuffer output,GpuPipeline pipeline,NativeShaderInjectionService.Plan plan) {
        try(var pass=device.createCommandEncoder().createRenderPass(RenderPassDescriptor.builder(()->"prepared native replacement draw").withColorAttachment(output.color(0).view()).withDepthAttachment(output.depth().view()).build())) {
            pipeline.bind(pass);plan.bind(pass);pass.draw(3,1,0,0);
        }
    }
    private static void reloadSnapshots(Minecraft client,GpuDevice device,List<String> checks)throws Exception {
        var resources=client.getResourceManager();var editedFile=Identifier.parse("veil:pinwheel/shader_injection/gpu_root.glsl");
        var edited=new java.util.concurrent.atomic.AtomicReference<>(resources.getResourceOrThrow(editedFile).readAllAsString());
        var editable=(ResourceManager)java.lang.reflect.Proxy.newProxyInstance(ResourceManager.class.getClassLoader(),new Class<?>[]{ResourceManager.class},(proxy,method,args)->{
            if(method.getName().equals("getResource")&&editedFile.equals(args[0]))return Optional.of(new Resource(resources.getResourceOrThrow(editedFile).source(),
                    ()->new java.io.ByteArrayInputStream(edited.get().getBytes(java.nio.charset.StandardCharsets.UTF_8))));
            if(method.isDefault())return java.lang.reflect.InvocationHandler.invokeDefault(proxy,method,args);
            try{return method.invoke(resources,args);}catch(java.lang.reflect.InvocationTargetException failure){throw failure.getCause();}
        });
        var definitions=new ShaderPreDefinitions();var manager=new ShaderInjectionManager();reload(manager,editable);
        try(var source=new GpuShaderSource(resources);var service=new NativeShaderInjectionService(manager,Map::of,definitions);
            var output=GpuFramebuffer.builder("native injection reload generations",2,2).color(GpuFormat.RGBA8_UNORM).depth(GpuFormat.D32_FLOAT).build(device)) {
            var original=state("veil:core/gpu_injection",false);var first=service.plan(original,source,editable).orElseThrow();
            edited.set("void tail() { fragColor.r += 0.375; }");reload(manager,editable);
            var replacement=service.plan(original,source,editable).orElseThrow();source.close();
            try(var oldPipeline=compile(device,first,definitions);var newPipeline=compile(device,replacement,definitions)) {
                var encoder=device.createCommandEncoder();output.clear(encoder,new Vector4f(0),0);draw(device,output,oldPipeline,first);encoder.submit();assertPixels(device,output,64,128,128,255);
                encoder=device.createCommandEncoder();output.clear(encoder,new Vector4f(0),0);draw(device,output,newPipeline,replacement);encoder.submit();assertPixels(device,output,128,128,128,255);
                edited.set("void tail() { MissingNativeSymbol = 42.0; }");reload(manager,editable);
                try(var raw=new GpuShaderSource(resources)) {
                    var invalid=service.plan(original,raw,editable).orElseThrow();
                    try(var unexpected=compile(device,invalid,definitions)){throw new AssertionError("Invalid native injection was accepted");}
                    catch(RuntimeException expected){check(!oldPipeline.isClosed()&&!newPipeline.isClosed(),"Invalid native injection retired an earlier independent compiled owner");}
                }
                encoder=device.createCommandEncoder();output.clear(encoder,new Vector4f(0),0);draw(device,output,newPipeline,replacement);encoder.submit();assertPixels(device,output,128,128,128,255);
            }
        }
        checks.add("actual resource-listener reload retains old immutable source injection generations, new generation changes native pixels, invalid later GPU compilation leaves independently retained earlier pipelines/resources usable");
    }
    private static void integration(Minecraft client,GpuDevice device,List<String> checks)throws Exception {
        var resources=client.getResourceManager();var definitions=new ShaderPreDefinitions();var manager=new ShaderInjectionManager();reload(manager,resources);
        try(var programs=new ShaderManager(device,ShaderManager.PROGRAM_SET,definitions);var source=new GpuShaderSource(resources);var cache=new PipelineCache(device,source)) {
            reload(programs,resources);
            try(var service=new NativeShaderInjectionService(manager,programs::getDefinitions,definitions);
                var output=GpuFramebuffer.builder("automatic source service native output",2,2).color(GpuFormat.RGBA8_UNORM).depth(GpuFormat.D32_FLOAT).build(device)) {
                var events=new AtomicInteger();NativeShaderInjectionService.bindRuntime(service);
                NativePipelineBridge.bindRuntime(definitions,service,List.of((provider,registry)->registry.addPreprocessor((context,tree)->{
                    if(!context.isSourceFile()||!context.isFragment())return;
                    check(context instanceof foundry.veil.api.client.render.shader.processor.ShaderPreProcessor.MinecraftContext,"Global source callback lost native pipeline context");events.incrementAndGet();
                    if(context.name().equals(Identifier.parse("veil:core/gpu_injection")))tree.mainFunction().orElseThrow().getBody().add(io.github.ocelot.glslprocessor.api.GlslParser.parseExpression("fragColor.b *= 0.5"));
                },false)));
                try {
                    var baseState=state("veil:core/gpu_injection",false);var base=cache.get(baseState);
                    var encoder=device.createCommandEncoder();output.clear(encoder,new Vector4f(0),0);
                    try(var pass=encoder.createRenderPass(RenderPassDescriptor.builder(()->"unmodified native source draw").withColorAttachment(output.color(0).view()).withDepthAttachment(output.depth().view()).build())) {
                        pass.setPipeline(base);pass.draw(3,1,0,0);
                    }encoder.submit();assertPixels(device,output,64,128,64,255);
                    checks.add("actual native PipelineCache source hook applies real JSON root/include patches plus global native-context preprocessors automatically; ordinary pass.setPipeline produces exact modified pixels without fixture planning or binding");
                    var replacementState=state("veil:core/gpu_replaced",true);var firstReplacement=cache.get(replacementState);
                    definitions.set("gain","1.0");var changedGain=cache.get(replacementState);
                    check(changedGain!=firstReplacement&&firstReplacement.isClosed(),"Changed predefinition did not invalidate the same real native cache key");
                    var editedFile=Identifier.parse("veil:pinwheel/shader_injection/gpu_root.glsl");
                    var editedCode=new java.util.concurrent.atomic.AtomicReference<>("void tail() { fragColor.r += 0.375; }");
                    var editedResources=(ResourceManager)java.lang.reflect.Proxy.newProxyInstance(ResourceManager.class.getClassLoader(),new Class<?>[]{ResourceManager.class},(proxy,method,args)->{
                        if(method.getName().equals("getResource")&&editedFile.equals(args[0]))return Optional.of(new Resource(resources.getResourceOrThrow(editedFile).source(),
                                ()->new java.io.ByteArrayInputStream(editedCode.get().getBytes(java.nio.charset.StandardCharsets.UTF_8))));
                        if(method.isDefault())return java.lang.reflect.InvocationHandler.invokeDefault(proxy,method,args);
                        try{return method.invoke(resources,args);}catch(java.lang.reflect.InvocationTargetException failure){throw failure.getCause();}
                    });
                    reload(manager,editedResources);var reloadedBase=cache.get(baseState);
                    check(reloadedBase!=base&&base.isClosed(),"Changed immutable injection generation did not replace the same native cached source owner");
                    encoder=device.createCommandEncoder();output.clear(encoder,new Vector4f(0),0);
                    try(var pass=encoder.createRenderPass(RenderPassDescriptor.builder(()->"normal cache get after injection reload").withColorAttachment(output.color(0).view()).withDepthAttachment(output.depth().view()).build())) {
                        pass.setPipeline(reloadedBase);pass.draw(3,1,0,0);
                    }encoder.submit();assertPixels(device,output,128,128,64,255);
                    editedCode.set("void tail() { MissingNativeSymbol = 42.0; }");reload(manager,editedResources);
                    var failedRefresh=cache.get(baseState);int attemptedEvents=events.get();
                    check(failedRefresh==reloadedBase&&!reloadedBase.isClosed(),"Invalid native cache source refresh lost its previous working compiled owner");
                    check(cache.get(baseState)==reloadedBase&&events.get()==attemptedEvents,"Invalid native cache generation retries every draw instead of waiting for another source change");
                    encoder=device.createCommandEncoder();output.clear(encoder,new Vector4f(0),0);
                    try(var pass=encoder.createRenderPass(RenderPassDescriptor.builder(()->"working native owner after failed source refresh").withColorAttachment(output.color(0).view()).withDepthAttachment(output.depth().view()).build())) {
                        pass.setPipeline(failedRefresh);pass.draw(3,1,0,0);
                    }encoder.submit();assertPixels(device,output,128,128,64,255);
                    editedCode.set("void tail() { fragColor.r += 0.25; }");reload(manager,editedResources);var fixedBase=cache.get(baseState);
                    check(fixedBase!=reloadedBase&&reloadedBase.isClosed(),"Later successful native cache source change did not replace and retire the earlier good owner");
                    encoder=device.createCommandEncoder();output.clear(encoder,new Vector4f(0),0);
                    try(var pass=encoder.createRenderPass(RenderPassDescriptor.builder(()->"fixed native source generation").withColorAttachment(output.color(0).view()).withDepthAttachment(output.depth().view()).build())) {
                        pass.setPipeline(fixedBase);pass.draw(3,1,0,0);
                    }encoder.submit();assertPixels(device,output,96,128,64,255);
                    var inheritedOriginal=state("veil:core/gpu_inherited",true);
                    var inheritedSnippet=new RenderPipeline.Snippet(inheritedOriginal.getShaders(),Optional.of(inheritedOriginal.getShaderDefines()),Optional.of(inheritedOriginal.getBindGroupLayouts()),
                            inheritedOriginal.getColorTargetStates().toArray(ColorTargetState[]::new),inheritedOriginal.getColorTargetStates().size(),Optional.of(inheritedOriginal.getDepthStencilState()),
                            Optional.of(inheritedOriginal.getPolygonMode()),Optional.of(inheritedOriginal.isCull()),inheritedOriginal.getVertexFormatBindings().toArray(com.mojang.renderpearl.api.vertex.VertexFormat[]::new),
                            Optional.of(inheritedOriginal.getPrimitiveTopology()),inheritedOriginal.pushConstantSize());
                    var inheritedState=RenderPipeline.builder(inheritedSnippet).withLocation(inheritedOriginal.getLocation()).withBindGroupLayout(BindGroupLayout.builder()
                            .withUniform("NativeDraw",UniformType.UNIFORM_BUFFER).withUniform("Sampler0",UniformType.COMBINED_IMAGE_SAMPLER).build()).build();
                    var replacement=cache.get(inheritedState);
                    var tint=new GpuUniformBuffer(device,"automatic prepared replacement tint",GpuUniformBuffer.layoutBuilder().field("Tint",GpuUniformBuffer.ValueType.VEC4).build());
                    var texture=GpuFramebuffer.builder("submission-owned replacement source",2,2).color(GpuFormat.RGBA8_UNORM).build(device);
                    var sampler=device.createSampler(com.mojang.renderpearl.api.textures.AddressMode.CLAMP_TO_EDGE,com.mojang.renderpearl.api.textures.AddressMode.CLAMP_TO_EDGE,
                            com.mojang.renderpearl.api.textures.FilterMode.NEAREST,com.mojang.renderpearl.api.textures.FilterMode.NEAREST,1,OptionalDouble.empty());
                    var closed=new AtomicInteger();var retainedTint=tint.buffer();
                    try(var nativeDraw=new GpuUniformBuffer(device,"actual per-draw inherited native block",GpuUniformBuffer.layoutBuilder().field("NativeColor",GpuUniformBuffer.ValueType.VEC4).build());
                        var staleDraw=new GpuUniformBuffer(device,"stale frame snapshot must not replace native block",GpuUniformBuffer.layoutBuilder().field("NativeColor",GpuUniformBuffer.ValueType.VEC4).build());
                        var decoy=GpuFramebuffer.builder("native draw sampler must yield explicit JSON override",2,2).color(GpuFormat.RGBA8_UNORM).build(device)) {
                        tint.setVector("Tint",.5f,1,1,.5f);encoder=device.createCommandEncoder();tint.upload(encoder);texture.clear(encoder,new Vector4f(.8f,.4f,.2f,1),0);output.clear(encoder,new Vector4f(0,.25f,.5f,1),0);
                        nativeDraw.setVector("NativeColor",.5f,1,.5f,1);staleDraw.setVector("NativeColor",0,0,0,0);nativeDraw.upload(encoder);staleDraw.upload(encoder);decoy.clear(encoder,new Vector4f(0,1,0,1),0);
                        AutoCloseable owner=()->{closed.incrementAndGet();tint.close();texture.close();sampler.close();};
                        try(var frame=service.prepareFrame(request->{
                            var blocks=new HashMap<String,com.mojang.renderpearl.api.buffers.GpuBufferSlice>();blocks.put("ReplacementTint",tint.buffer().slice());
                            if(request.definition().uniformBlocks().contains("NativeDraw"))blocks.put("NativeDraw",staleDraw.buffer().slice());
                            var samples=new HashMap<String,NativePipelineBridge.Texture>();request.definition().samplers().keySet().forEach(name->samples.put(name,new NativePipelineBridge.Texture(texture.color(0).view(),sampler)));
                            return new NativeShaderInjectionService.PreparedBindings(blocks,samples,owner);
                        })) {
                            try(var pass=encoder.createRenderPass(RenderPassDescriptor.builder(()->"unmodified native replacement draw").withColorAttachment(output.color(0).view()).withDepthAttachment(output.depth().view()).build())) {
                                pass.setPipeline(replacement);pass.setUniform("NativeDraw",nativeDraw.buffer().slice());pass.setUniform("Sampler0",decoy.color(0).view(),sampler);pass.draw(3,1,0,0);
                            }
                            cache.close();source.close();service.close();
                        }
                        check(closed.get()==0&&!retainedTint.isClosed()&&!texture.isClosed()&&!sampler.isClosed(),"Closing native cache/source/frame/service released queued replacement resources before native submit");
                        try(var fence=encoder.createFence()){encoder.submit();check(fence.awaitCompletion(10_000_000_000L),"Automatic native replacement submission timed out");}
                        check(closed.get()==1&&retainedTint.isClosed()&&texture.isClosed()&&sampler.isClosed(),"Actual native encoder submission failed to retire prepared replacement resource owners once");
                        assertPixels(device,output,26,83,77,255);check(events.get()==7,"Native global preprocessing did not cover the seven actual source/cache reload recipes exactly once: "+events.get());
                        checks.add("actual native cache replaces shader stages with prepared typed JSON resources while preserving original blend/depth state; automatic encoder ledger retains real owned UBO/texture/sampler through cache/source/frame/service retirement and closes them once after native submission");
                        checks.add("normal predefinition changes and immutable injection-listener reload automatically invalidate the same actual native cache keys, retire prior compiled owners and produce changed native pixels without fixture cache clearing");
                        checks.add("replacement retains exact inherited per-draw native UBO despite stale frame value and reapplies explicit JSON Sampler0 after native draw sampler binding; no frame-head projection/transform substitution");
                        checks.add("failed actual native cache refresh keeps last working compiled owner and pixels without repeated retries; a later valid resource generation swaps successfully and retires that prior owner");
                    } finally {if(!retainedTint.isClosed())tint.close();if(!texture.isClosed())texture.close();if(!sampler.isClosed())sampler.close();}
                } finally {NativePipelineBridge.unbindRuntime();NativeShaderInjectionService.unbindRuntime(service);}
            }
        }
    }
    private static void reload(PreparableReloadListener listener,ResourceManager resources) {
        var state=new PreparableReloadListener.SharedState(resources);
        var barrier=new PreparableReloadListener.PreparationBarrier(){@Override public<T>java.util.concurrent.CompletableFuture<T>wait(T value){return java.util.concurrent.CompletableFuture.completedFuture(value);}};
        listener.reload(state,Runnable::run,barrier,Runnable::run).join();
    }
    private static void assertPixels(GpuDevice device,GpuFramebuffer output,int...expected) {
        try(var read=device.createBuffer(()->"native injection exact pixels",GpuBuffer.USAGE_COPY_DST|GpuBuffer.USAGE_MAP_READ,16)) {
            var encoder=device.createCommandEncoder();encoder.copyTextureToBuffer(output.color(0).texture(),read,0,()->{},0);
            try(var fence=encoder.createFence()){encoder.submit();check(fence.awaitCompletion(10_000_000_000L),"Native injection readback timed out");
                try(var mapped=read.map(true,false)){for(int i=0;i<16;i++){int actual=mapped.data().get(i)&255;check(Math.abs(actual-expected[i%4])<=2,"Native injection component "+i+" was "+actual+", expected "+expected[i%4]);}}
            }
        }
    }
    private static void check(boolean value,String message){if(!value)throw new AssertionError(message);}
}
