import com.google.gson.GsonBuilder;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.textures.GpuTexture;
import foundry.veil.api.client.render.gpu.GpuFramebuffer;
import foundry.veil.api.client.render.texture.*;
import foundry.veil.api.client.util.*;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.renderer.texture.TextureContents;
import net.minecraft.core.Direction;
import net.minecraft.resources.Identifier;
import org.joml.Vector4f;
import org.lwjgl.system.MemoryStack;
import javax.imageio.ImageIO;
import java.nio.file.*;
import java.util.*;

/** New native inspector sampling/inventory/PNG path only; no old layered/framebuffer groups. */
public final class NativeEditorTextureAcceptance implements ClientModInitializer {
    private int ticks;private boolean ran;
    @Override public void onInitializeClient(){ClientTickEvents.END_CLIENT_TICK.register(client->{
        if(ran||++ticks<5||!client.isGameLoadFinished())return;ran=true;
        final var report=verifyNative(client,Path.of(System.getProperty("veil.editor.texture.output")));
        try{Files.writeString(Path.of(System.getProperty("veil.editor.texture.report")),new GsonBuilder().setPrettyPrinting().create().toJson(report));}catch(Exception failure){throw new RuntimeException(failure);}client.stop();
    });}
    /** Existing preview/export GPU assertions, callable with the installed Veil runtime. */
    public static Map<String,Object> verifyNative(net.minecraft.client.Minecraft client,Path folder){
        final var report=new LinkedHashMap<String,Object>();final var device=RenderSystem.getDevice();
        report.put("backend",device.getDeviceInfo().backendName());report.put("device",device.getDeviceInfo().name());report.put("deviceClass",device.getClass().getName());report.put("deviceHooks",java.util.Arrays.stream(device.getClass().getDeclaredMethods()).map(java.lang.reflect.Method::getName).filter(name->name.contains("trackNative")).toList());
        try(var preview=new NativeTexturePreview(device);
            var hdr=GpuFramebuffer.builder("new inspector HDR",2,2).color(GpuFormat.RGBA16_FLOAT).build(device);
            var depth=GpuFramebuffer.builder("new inspector depth",2,2).depth(GpuFormat.D32_FLOAT).build(device);
            var cube=new DynamicCubemapTexture();
            var array=new SimpleArrayTexture(Identifier.parse("veil:editor/a"),Identifier.parse("veil:editor/b"));
            var single=new SimpleArrayTexture(Identifier.parse("veil:editor/single"))) {
            final var texture=device.createTexture("binding-only PNG source",GpuTexture.USAGE_COPY_DST|GpuTexture.USAGE_TEXTURE_BINDING,GpuFormat.RGBA8_UNORM,2,2,1,2);
            final var integers=device.createTexture("native integer preview",GpuTexture.USAGE_COPY_DST|GpuTexture.USAGE_TEXTURE_BINDING,GpuFormat.RGBA8_UINT,1,1,1,1);
            try(var view=device.createTextureView(texture);var integerView=device.createTextureView(integers);var stack=MemoryStack.stackPush()){
                final var commands=device.createCommandEncoder();
                final var pixels=stack.malloc(16);pixels.put(new byte[]{(byte)255,0,0,(byte)255,(byte)255,0,0,(byte)128,0,0,(byte)255,(byte)255,0,0,(byte)255,0}).flip();
                commands.writeToTexture(texture,pixels,0,0,0,0,2,2);
                final var mip=stack.malloc(4);mip.put(new byte[]{0,(byte)255,0,(byte)255}).flip();commands.writeToTexture(texture,mip,1,0,0,0,1,1);
                final var data=stack.malloc(4);data.put(new byte[]{(byte)128,64,(byte)255,(byte)255}).flip();commands.writeToTexture(integers,data,0,0,0,0,1,1);
                hdr.clear(commands,new Vector4f(2,.5f,.25f,.75f),0);depth.clear(commands,new Vector4f(0),.75);commands.submit();
                report.put("inventorySize",NativeTextureRegistry.snapshot().size());check(NativeTextureRegistry.snapshot().stream().anyMatch(entry->entry.texture()==texture),"Actual frontend allocation enters typed inventory");
                final var raw=TextureDownloader.readRgba(preview.render(view,NativeTexturePreview.Projection.TEXTURE_2D,0,0));
                check((raw[0]&255)==255&&(raw[2]&255)==0&&(raw[8]&255)==0&&(raw[10]&255)==255,"Native preview preserves source row order and alpha");
                check((raw[7]&255)==128&&(raw[15]&255)==0,"Native preview preserves source alpha");
                rgba(preview.render(hdr.color(0).view(),NativeTexturePreview.Projection.TEXTURE_2D,0,0),255,128,64,191);
                rgba(preview.render(depth.depth().view(),NativeTexturePreview.Projection.TEXTURE_2D,0,0),191,191,191,255);
                rgba(preview.render(integerView,NativeTexturePreview.Projection.TEXTURE_2D,0,0),128,64,255,255);
                final var normal=TextureDownloader.save("binding",folder,view,false);final var flipped=TextureDownloader.save("flipped",folder,view,true);
                texture.close();normal.join();flipped.join();
                final var normalPng=ImageIO.read(folder.resolve("binding-0.png").toFile());final var flipPng=ImageIO.read(folder.resolve("flipped-0.png").toFile());
                check(normalPng.getRGB(0,0)==0xffff0000&&normalPng.getRGB(0,1)==0xff0000ff&&normalPng.getRGB(1,1)==0x000000ff,"PNG exact RGBA survives immediate source retirement");
                check(flipPng.getRGB(0,0)==normalPng.getRGB(0,1)&&flipPng.getRGB(0,1)==normalPng.getRGB(0,0),"Per-export CPU flip has no global writer state");
                final var mipPng=ImageIO.read(folder.resolve("binding-1.png").toFile());check(mipPng.getWidth()==1&&mipPng.getHeight()==1&&mipPng.getRGB(0,0)==0xff00ff00,"Actual mip-level export uses native sampling LOD");
            }finally{texture.close();integers.close();}
            cube.init(2,2);for(var direction:Direction.values()){final int face=CubemapTexture.faceIndex(direction);try(var image=image(2,2,color(face))){cube.upload(direction,image);}}
            for(int face=0;face<6;face++){final var image=preview.render(cube.getTextureView(),NativeTexturePreview.Projection.CUBEMAP_FACE,face,0);final int expected=color(face);rgba(image,(expected>>>16)&255,(expected>>>8)&255,expected&255,255);}
            final var panorama=preview.render(cube.getTextureView(),NativeTexturePreview.Projection.CUBEMAP,0,0);check(panorama.getWidth(0)==4&&panorama.getHeight(0)==2,"Actual cubemap panorama uses original equirectangular projection");
            TextureDownloader.save("cube",folder,cube,false).join();for(int face=0;face<6;face++)check(ImageIO.read(folder.resolve("cube-layer"+face+".png").toFile()).getRGB(0,0)==color(face),"Every real cube face exports its own PNG");
            final var stacked=image(2,4,0xff00ff00);for(int y=2;y<4;y++)for(int x=0;x<2;x++)stacked.setPixel(x,y,0xffff00ff);array.apply(new TextureContents(stacked,null));
            single.apply(new TextureContents(image(2,2,0xff00ffff),null));
            rgba(preview.render(array.getTextureView(),NativeTexturePreview.Projection.ARRAY,0,0),0,255,0,255);
            rgba(preview.render(array.getTextureView(),NativeTexturePreview.Projection.ARRAY,1,0),255,0,255,255);
            rgba(preview.render(single.getTextureView(),NativeTexturePreview.Projection.ARRAY,0,0),0,255,255,255);
            check(NativeTextureRegistry.snapshot().stream().anyMatch(entry->entry.texture()==single.getTexture()&&entry.kind()==NativeTextureRegistry.Kind.ARRAY),"Native single-layer allocation retains explicit array identity in inventory");
            TextureDownloader.save("array",folder,array,false).join();check(ImageIO.read(folder.resolve("array-layer0.png").toFile()).getRGB(0,0)==0xff00ff00&&ImageIO.read(folder.resolve("array-layer1.png").toFile()).getRGB(0,0)==0xffff00ff,"Actual native array layers export independently");
            check(!cube.getTexture().isClosed()&&!array.getTexture().isClosed()&&!hdr.color(0).texture().isClosed()&&!depth.depth().texture().isClosed(),"Preview/export never dispose caller textures or views");
            report.put("status","passed");report.put("checks",List.of("actual typed frontend inventory plus explicit native single-layer array identity","binding-only 2D source preview, exact rows/alpha and asynchronous PNG after source retirement","actual native mip export and independent per-file vertical flip","sampled HDR clamp, depth grayscale and unsigned integer normalized preview","actual cubemap face/panorama and array-layer preview/PNG export","caller ownership preserved"));
        }catch(Throwable failure){failure.printStackTrace();report.put("status","failed");report.put("error",failure.toString());}
        return report;
    }
    private static NativeImage image(int width,int height,int color){final var image=new NativeImage(width,height,false);for(int y=0;y<height;y++)for(int x=0;x<width;x++)image.setPixel(x,y,color);return image;}
    private static int color(int face){return 0xff000000|((face*36+20)&255)<<16|((face*24+30)&255)<<8|((face*16+40)&255);}
    private static void rgba(com.mojang.renderpearl.api.textures.GpuTextureView image,int red,int green,int blue,int alpha){final var pixels=TextureDownloader.readRgba(image);final int[] expected={red,green,blue,alpha};for(int index=0;index<pixels.length;index++)check(Math.abs((pixels[index]&255)-expected[index%4])<=1,"Wrong native inspector pixel at "+index+": "+(pixels[index]&255)+" expected "+expected[index%4]);}
    private static void check(boolean value,String message){if(!value)throw new AssertionError(message);}
}
