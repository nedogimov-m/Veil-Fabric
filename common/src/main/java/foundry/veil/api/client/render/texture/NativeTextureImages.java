package foundry.veil.api.client.render.texture;

import com.mojang.blaze3d.platform.NativeImage;
import java.util.List;

/** Owned CPU preparation only; source images remain owned by their caller. */
public final class NativeTextureImages {
    public static NativeImage copy(NativeImage image) {
        if(image.format()!=NativeImage.Format.RGBA)throw new IllegalArgumentException("Native texture uploads require RGBA images");
        final var result=new NativeImage(image.getWidth(),image.getHeight(),false);
        try {result.copyFrom(image);return result;}catch(RuntimeException|Error failure){result.close();throw failure;}
    }
    public static NativeImage stack(List<NativeImage> images) {
        if(images.isEmpty())throw new IllegalArgumentException("No image layers");
        final int width=images.getFirst().getWidth(),height=images.getFirst().getHeight();
        for(var image:images)if(image.format()!=NativeImage.Format.RGBA||image.getWidth()!=width||image.getHeight()!=height)
            throw new IllegalArgumentException("All RGBA layers must have equal dimensions");
        final var result=new NativeImage(width,Math.multiplyExact(height,images.size()),false);
        try {for(int i=0;i<images.size();i++)images.get(i).copyRect(result,0,0,0,i*height,width,height,false,false);return result;}
        catch(RuntimeException|Error failure){result.close();throw failure;}
    }
    /** Original cross: +X(2,1),-X(0,1),+Y(1,0),-Y(1,2),+Z(1,1),-Z(3,1). */
    public static NativeImage cross(NativeImage image) {
        final int width=image.getWidth();
        if(image.format()!=NativeImage.Format.RGBA||width%4!=0||image.getHeight()!=width/4*3)
            throw new IllegalArgumentException("Cubemap requires a complete RGBA 4x3 cross");
        final int size=width/4;final var result=new NativeImage(size,size*6,false);
        try {
            final int[][] tiles={{2,1},{0,1},{1,0},{1,2},{1,1},{3,1}};
            for(int face=0;face<6;face++)image.copyRect(result,tiles[face][0]*size,tiles[face][1]*size,0,face*size,size,size,false,false);
            return result;
        } catch(RuntimeException|Error failure){result.close();throw failure;}
    }
    private NativeTextureImages() { }
}
