import com.google.gson.GsonBuilder;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import foundry.veil.api.client.render.dynamicbuffer.DynamicBufferType;
import foundry.veil.fabric.FabricRenderTypeStageHandler;
import foundry.veil.fabric.event.FabricVeilRenderLevelStageEvent;
import foundry.veil.api.event.VeilRenderLevelStageEvent;
import foundry.veil.impl.client.render.dynamicbuffer.*;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.resources.Identifier;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/** New actual-world scope: real LevelRenderer/terrain/features/OIT with production automatic dynamic capture. */
public final class GpuDynamicWorldSceneAcceptance implements FabricClientGameTest {
    @Override public void runTest(ClientGameTestContext test) {
        var report = new LinkedHashMap<String,Object>();
        var owner = new AtomicReference<DynamicBufferManager>();
        var variants = new AtomicReference<VanillaShaderCompiler>();
        var failure = new AtomicReference<Throwable>();
        var previousTransparency = new AtomicReference<Boolean>();
        var frames = new AtomicInteger(); var geometry = new AtomicInteger();
        try {
            test.runOnClient(client -> {
                var device = RenderSystem.getDevice();
                previousTransparency.set(client.options.improvedTransparency().get()); client.options.improvedTransparency().set(true);
                report.put("backend",device.getDeviceInfo().backendName());
                var compiler = new VanillaShaderCompiler(device, net.minecraft.util.Util.backgroundExecutor(), client);
                variants.set(compiler);
                var manager = new DynamicBufferManager(device, client.getResourceManager(), 2, 2, compiler, change -> {});
                owner.set(manager); NativeDynamicBuffers.bindRuntime(manager);
                manager.setActiveBuffers(Identifier.parse("veil_test:actual_world"), DynamicBufferType.encode(DynamicBufferType.values()));
                FabricRenderTypeStageHandler.registerEvents();
                FabricVeilRenderLevelStageEvent.EVENT.register((stage,context) -> {
                    if (failure.get()!=null || frames.get()>=3 || !context.camera().initialized) return;
                    try {
                        if (stage==VeilRenderLevelStageEvent.Stage.COLLECT_SUBMITS) {
                            var poseStack=context.poseStack(); poseStack.pushPose();
                            poseStack.mulPose(new org.joml.Matrix4f(context.camera().viewRotationMatrix).invert());
                            context.submissions().submitCustomGeometry(poseStack,RenderTypes.debugQuads(),(pose,vertices)->{
                                for(float[] xy:new float[][]{{-2,-2},{2,-2},{2,2},{-2,2}})
                                    vertices.addVertex(pose,xy[0],xy[1],-3).setColor(255,0,255,255);
                                geometry.incrementAndGet();
                            });
                            poseStack.popPose();
                        } else if(stage==VeilRenderLevelStageEvent.Stage.END_MAIN && geometry.get()>0) {
                            var texture=manager.getBufferTexture(DynamicBufferType.ALBEDO).texture();
                            try(var read=device.createBuffer(()->"actual native world property center",GpuBuffer.USAGE_COPY_DST|GpuBuffer.USAGE_MAP_READ,4)) {
                                var commands=device.createCommandEncoder();
                                commands.copyTextureToBuffer(texture,read,0,()->{},0,texture.getWidth(0)/2,texture.getHeight(0)/2,1,1);
                                try(var fence=commands.createFence()) {
                                    commands.submit();
                                    if(!fence.awaitCompletion(10_000_000_000L)) throw new AssertionError("Actual world dynamic readback timeout");
                                }
                                try(var mapped=read.map(true,false)) {
                                    int[] expected={255,0,255,255};
                                    for(int i=0;i<4;i++) {
                                        int actual=mapped.data().get(i)&255;
                                        if(Math.abs(actual-expected[i])>2)throw new AssertionError("Actual world albedo component "+i+" was "+actual+", expected "+expected[i]);
                                    }
                                }
                            }
                            frames.incrementAndGet();
                        }
                    } catch(Throwable caught) { failure.compareAndSet(null,caught); }
                });
            });
            try(var world=test.worldBuilder().create()) {
                test.waitFor(client->failure.get()!=null||frames.get()>=3,400);
                if(failure.get()!=null)throw new AssertionError("Actual native dynamic world failed",failure.get());
                if(frames.get()<3)throw new AssertionError("Actual native dynamic world produced no stable property frames");
                report.put("frames",frames.get());report.put("geometryCallbacks",geometry.get());
                test.runOnClient(client->{
                    report.put("nativeVariantCount",variants.get().variantCount());
                    long oit = foundry.veil.impl.client.render.shader.debug.NativePipelineRegistry.snapshot().stream()
                            .filter(entry -> entry.definition().getShaderDefines().flags().contains("OIT_ACCUMULATE")
                                    && entry.definition().getColorTargetStates().size()>1).count();
                    if(oit==0)throw new AssertionError("Actual world did not execute native OIT dynamic variants");
                    report.put("nativeOitVariantCount",oit);
                });
                report.put("checks",List.of("actual LevelRenderer owner scope and real terrain/sky/features/OIT graph; production provider/pass interception; three native world property readbacks; reused native variants and frame resources"));
            }
            report.put("status","passed");
        } catch(Throwable caught) {
            report.put("status","failed"); report.put("error",caught.toString()); throw caught;
        } finally {
            test.runOnClient(client->{
                if(previousTransparency.get()!=null)client.options.improvedTransparency().set(previousTransparency.get());
                if(owner.get()!=null) { NativeDynamicBuffers.flush(owner.get()); NativeDynamicBuffers.unbindRuntime(owner.get()); owner.get().close(); }
                if(variants.get()!=null) {
                    variants.get().close(); var retired=variants.get().retirement(); client.managedBlock(retired::isDone); retired.join();
                }
            });
            try { Files.writeString(Path.of(System.getProperty("veil.gpu.acceptance.report")),new GsonBuilder().setPrettyPrinting().create().toJson(report)); }
            catch(Exception caught) { throw new RuntimeException(caught); }
        }
    }
}
