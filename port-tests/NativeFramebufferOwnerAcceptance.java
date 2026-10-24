import com.google.gson.*;
import com.mojang.blaze3d.pipeline.*;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.device.GpuDevice;
import com.mojang.renderpearl.api.textures.GpuTexture;
import com.mojang.serialization.JsonOps;
import foundry.veil.api.client.render.framebuffer.*;
import foundry.veil.api.client.render.gpu.GpuFramebuffer;
import foundry.veil.ext.RenderTargetExtension;
import foundry.veil.impl.client.render.wrapper.VanillaAdvancedFboWrapper;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.resources.Identifier;
import org.joml.Vector4f;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;

/** New real typed wrapper/Mixin ownership and transparent-alpha delta only; no prior GPU groups. */
public final class NativeFramebufferOwnerAcceptance implements ClientModInitializer {
    private boolean ran;private int ticks;
    @Override public void onInitializeClient() {
        ClientTickEvents.END_CLIENT_TICK.register(client->{
            if(ran||++ticks<5||!client.isGameLoadFinished())return;ran=true;
            final var report=new LinkedHashMap<String,Object>();final var device=RenderSystem.getDevice();
            report.put("backend",device.getDeviceInfo().backendName());report.put("device",device.getDeviceInfo().name());
            try {
                check(device.getDeviceInfo().backendName().toLowerCase(Locale.ROOT).contains("vulkan"),"Actual Vulkan required");
                wrapperAndMixin(device);transparentManagerDelta(device);
                report.put("status","passed");report.put("checks",List.of("actual typed target borrow/cache/resize/supplier replacement","real native Mixin switches and restores typed attachments","native owner resize and destroy/create retain separately owned targets","wrapper close never disposes Minecraft textures/views","new framebuffer allocation and autoClear preserve transparent alpha zero"));
            } catch(Throwable failure){failure.printStackTrace();report.put("status","failed");report.put("error",failure.toString());}
            finally {try {Files.writeString(Path.of(System.getProperty("veil.framebuffer.owner.report")),new GsonBuilder().setPrettyPrinting().create().toJson(report));}catch(Exception failure){throw new RuntimeException(failure);}client.stop();}
        });
    }
    private static void wrapperAndMixin(GpuDevice device) {
        final var nativeTarget=new TextureTarget("new native owner",4,3,GpuFormat.RGBA8_UNORM,GpuFormat.D32_FLOAT);
        final var otherTarget=new TextureTarget("supplier replacement",6,2,GpuFormat.RGBA8_UNORM,null);
        final var current=new AtomicReference<RenderTarget>(nativeTarget);
        final var extension=(RenderTargetExtension)nativeTarget;
        try(var wrapper=new VanillaAdvancedFboWrapper(device,current::get);
            var first=GpuFramebuffer.builder("temporary first",2,2).color(GpuFormat.RGBA8_UNORM).depth(GpuFormat.D32_FLOAT).build(device);
            var second=GpuFramebuffer.builder("temporary second",3,3).color(GpuFormat.RGBA8_UNORM).build(device)) {
            final var borrowed=wrapper.framebuffer();
            check(wrapper.framebuffer()==borrowed&&wrapper.colorView(0)==nativeTarget.getColorTextureView(),"Unchanged actual native views reuse one typed borrow");
            nativeTarget.resize(5,2);final var resized=wrapper.framebuffer();
            check(resized!=borrowed&&borrowed.isClosed()&&resized.width()==5&&resized.height()==2,"Native resize invalidates the old cached borrow");
            final var retainedOwner=nativeTarget.getColorTexture();current.set(otherTarget);final var replaced=wrapper.framebuffer();
            check(resized.isClosed()&&replaced.width()==6&&!retainedOwner.isClosed()&&wrapper.depthView()==null,"Supplier replacement refreshes the typed layout without disposing the prior native owner");
            current.set(nativeTarget);wrapper.framebuffer();
            extension.veil$setWrapper(first);
            check(extension.veil$getWrapper()==first&&extension.veil$getTexture(0)==first.color(0).view()&&nativeTarget.getColorTexture()==first.color(0).texture()&&nativeTarget.getDepthTexture()==first.depth().texture(),"Actual native Mixin installs genuine GPU textures and views");
            final var borrowedFirst=wrapper.framebuffer();extension.veil$setWrapper(second);final var borrowedSecond=wrapper.framebuffer();
            check(borrowedFirst.isClosed()&&!first.color(0).texture().isClosed()&&borrowedSecond.color(0).view()==second.color(0).view()&&!nativeTarget.hasDepth(),"Nested replacement retains native storage and closes only expired borrows");
            nativeTarget.resize(7,4);
            check(nativeTarget.width==3&&nativeTarget.height==3&&nativeTarget.getColorTextureView()==second.color(0).view()&&!second.color(0).view().isClosed(),"Native resize allocates its own storage while retaining the active external target");
            extension.veil$setWrapper(null);
            check(nativeTarget.width==7&&nativeTarget.height==4&&nativeTarget.getColorTexture()!=retainedOwner&&retainedOwner.isClosed()&&nativeTarget.hasDepth(),"Removal restores the newest actual native allocation and dimensions");
            extension.veil$setWrapper(first);nativeTarget.destroyBuffers();
            check(!first.color(0).texture().isClosed()&&!first.depth().view().isClosed(),"Actual native destroy does not close separately owned framebuffer attachments");
            nativeTarget.createBuffers(9,5);extension.veil$setWrapper(null);
            check(nativeTarget.width==9&&nativeTarget.height==5&&!nativeTarget.getColorTextureView().isClosed(),"Actual native recreate installs/restores fresh owner storage");
            final var last=wrapper.framebuffer();final var nativeColor=nativeTarget.getColorTexture();wrapper.close();
            check(last.isClosed()&&!nativeColor.isClosed()&&!nativeTarget.getColorTextureView().isClosed(),"Wrapper close expires only its borrow, never Minecraft storage");
        } finally {
            extension.veil$setWrapper(null);nativeTarget.destroyBuffers();otherTarget.destroyBuffers();
        }
    }
    private static void transparentManagerDelta(GpuDevice device) {
        final var manager=new FramebufferManager(device,2,2);
        try {
            final var id=Identifier.fromNamespaceAndPath("veil","new_clear_alpha_delta");
            final var definition=FramebufferDefinition.CODEC.parse(JsonOps.INSTANCE,JsonParser.parseString("{\"width\":2,\"height\":2,\"depth\":false}")).getOrThrow();
            manager.setDefinition(id,definition);final var output=Objects.requireNonNull(manager.getFramebuffer(id));
            assertTransparent(device,output.color(0).texture());
            final var encoder=device.createCommandEncoder();output.clear(encoder,new Vector4f(1,0,0,1),0);encoder.submit();
            manager.clear();assertTransparent(device,output.color(0).texture());
        } finally {manager.free();}
    }
    private static void assertTransparent(GpuDevice device,GpuTexture texture) {
        final int size=texture.getWidth(0)*texture.getHeight(0)*4;
        try(var buffer=device.createBuffer(()->"new alpha-delta readback",GpuBuffer.USAGE_COPY_DST|GpuBuffer.USAGE_MAP_READ,size)) {
            final var encoder=device.createCommandEncoder();encoder.copyTextureToBuffer(texture,buffer,0,()->{},0);
            try(var fence=encoder.createFence()) {
                encoder.submit();check(fence.awaitCompletion(10_000_000_000L),"GPU readback completed");
                try(var mapped=buffer.map(true,false)){final var bytes=mapped.data();while(bytes.hasRemaining())check(bytes.get()==0,"Real new/default-cleared RGBA channels, including alpha, are zero");}
            }
        }
    }
    private static void check(boolean condition,String message){if(!condition)throw new AssertionError(message);}
}
