package foundry.veil.api.client.util;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import foundry.veil.api.client.render.texture.ArrayTexture;
import foundry.veil.api.client.render.texture.CubemapTexture;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.AbstractTexture;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Util;
import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;

/** Native sampled snapshots followed by asynchronous PNG encoding; source resources remain caller-owned. */
public final class TextureDownloader {
    private TextureDownloader(){}
    public static CompletableFuture<?> save(String name,Path outputFolder,AbstractTexture texture,boolean flip){
        return save(name,outputFolder,texture.getTextureView(),texture instanceof CubemapTexture?NativeTexturePreview.Projection.CUBEMAP_FACE:
                texture instanceof ArrayTexture?NativeTexturePreview.Projection.ARRAY:NativeTexturePreview.Projection.TEXTURE_2D,flip);
    }
    public static CompletableFuture<?> save(String name,Path outputFolder,Identifier texture,boolean flip){
        return save(name,outputFolder,Minecraft.getInstance().getTextureManager().getTexture(texture),flip);
    }
    public static CompletableFuture<?> save(String name,Path outputFolder,GpuTextureView texture,boolean flip){
        final var projection=(texture.texture().usage()&com.mojang.renderpearl.api.textures.GpuTexture.USAGE_CUBEMAP_COMPATIBLE)!=0?NativeTexturePreview.Projection.CUBEMAP_FACE:
                texture.texture().getDepthOrLayers()>1?NativeTexturePreview.Projection.ARRAY:NativeTexturePreview.Projection.TEXTURE_2D;
        return save(name,outputFolder,texture,projection,flip);
    }
    /** Explicit projection keeps single-layer native array views distinct from ordinary 2D views. */
    public static CompletableFuture<?> save(String name,Path outputFolder,GpuTextureView texture,NativeTexturePreview.Projection projection,boolean flip){
        RenderSystem.assertOnRenderThread();final var device=RenderSystem.getDevice();
        final int layers=switch(projection){case CUBEMAP,CUBEMAP_FACE->6;case ARRAY->texture.texture().getDepthOrLayers();default->1;};
        final var actual=projection==NativeTexturePreview.Projection.CUBEMAP?NativeTexturePreview.Projection.CUBEMAP_FACE:projection;
        final var pending=new ArrayList<CompletableFuture<?>>();final String safeName=safeName(name);
        try(var preview=new NativeTexturePreview(device)){
            for(int layer=0;layer<layers;layer++)for(int mip=0;mip<texture.mipLevels();mip++){
                final var image=preview.render(texture,actual,layer,mip);final int width=image.getWidth(0),height=image.getHeight(0);
                final byte[] rgba=readRgba(image);final String suffix=(layers>1?"-layer"+layer:"")+(texture.mipLevels()>1?"-"+mip:"");
                final Path path=outputFolder.resolve(safeName+suffix+".png");
                pending.add(CompletableFuture.runAsync(()->write(path,width,height,rgba,flip),Util.ioPool()));
            }
        }
        return CompletableFuture.allOf(pending.toArray(CompletableFuture[]::new));
    }
    /** Capture before return; callers may retire/resize textures while PNG encoding runs. */
    public static byte[] readRgba(GpuTextureView image){
        final var texture=image.texture();
        if(texture.getFormat()!=com.mojang.renderpearl.api.GpuFormat.RGBA8_UNORM||image.mipLevels()!=1||texture.getDepthOrLayers()!=1)
            throw new IllegalArgumentException("PNG capture requires one native RGBA8 preview image");
        final int size=Math.multiplyExact(Math.multiplyExact(image.getWidth(0),image.getHeight(0)),4);final var device=RenderSystem.getDevice();
        try(var buffer=device.createBuffer(()->"Veil native PNG snapshot",GpuBuffer.USAGE_COPY_DST|GpuBuffer.USAGE_MAP_READ,size)){
            final var encoder=device.createCommandEncoder();encoder.copyTextureToBuffer(texture,buffer,0,()->{},image.baseMipLevel());
            try(var fence=encoder.createFence()){
                encoder.submit();if(!fence.awaitCompletion(10_000_000_000L))throw new IllegalStateException("Native PNG readback timed out");
                try(var mapped=buffer.map(true,false)){final byte[] rgba=new byte[size];mapped.data().get(rgba);return rgba;}
            }
        }
    }
    private static String safeName(String name){final String safe=name.replaceAll("[^A-Za-z0-9._-]","_");return safe.isBlank()||safe.equals(".")||safe.equals("..")?"texture":safe;}
    private static void write(Path path,int width,int height,byte[] rgba,boolean flip){
        try{
            Files.createDirectories(path.getParent());final var image=new BufferedImage(width,height,BufferedImage.TYPE_INT_ARGB);
            for(int y=0;y<height;y++)for(int x=0;x<width;x++){
                final int at=((flip?height-y-1:y)*width+x)*4;
                image.setRGB(x,y,(rgba[at+3]&255)<<24|(rgba[at]&255)<<16|(rgba[at+1]&255)<<8|(rgba[at+2]&255));
            }
            if(!ImageIO.write(image,"png",path.toFile()))throw new IOException("No PNG writer is available");
        }catch(IOException failure){throw new CompletionException("Failed to write native texture "+path,failure);}
    }
}
