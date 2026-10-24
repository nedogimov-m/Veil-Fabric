package foundry.veil.api.client.render.texture;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.renderer.texture.TextureContents;
import net.minecraft.core.Direction;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.ResourceManager;
import java.io.IOException;
import java.util.*;

/** Dynamic real cube storage; owned CPU faces retain its current pixels across native resource reload. */
public class DynamicCubemapTexture extends CubemapTexture {
    private List<NativeImage> faces=List.of();
    public DynamicCubemapTexture(){super(Identifier.fromNamespaceAndPath("veil","dynamic_cubemap"));}
    private static void validate(int width,int height){if(width<=0||width!=height)throw new IllegalArgumentException("Cubemap faces must be positive squares");}
    public synchronized void init(int width,int height) {
        RenderSystem.assertOnRenderThread();validate(width,height);final var replacements=new ArrayList<NativeImage>();
        try {
            for(int face=0;face<6;face++) {final var image=new NativeImage(width,height,false);replacements.add(image);for(int y=0;y<height;y++)for(int x=0;x<width;x++)image.setPixel(x,y,0xffffffff);}
            replace(replacements);
        } catch(RuntimeException|Error failure){replacements.forEach(NativeImage::close);throw failure;}
    }
    private void replace(List<NativeImage> replacements) {
        apply(new TextureContents(NativeTextureImages.stack(replacements),null));
        final var previous=faces;faces=List.copyOf(replacements);previous.forEach(NativeImage::close);
    }
    public synchronized void upload(NativeImage image) {
        RenderSystem.assertOnRenderThread();validate(image.getWidth(),image.getHeight());final var replacements=new ArrayList<NativeImage>();
        try {for(int face=0;face<6;face++)replacements.add(NativeTextureImages.copy(image));replace(replacements);}
        catch(RuntimeException|Error failure){replacements.forEach(NativeImage::close);throw failure;}
    }
    public void upload(Direction face,NativeImage image){upload(faceIndex(face),image);}
    /** Face index is native +X,-X,+Y,-Y,+Z,-Z; there are no backend integer enums. */
    public synchronized void upload(int face,NativeImage image) {
        RenderSystem.assertOnRenderThread();if(face<0||face>=6)throw new IllegalArgumentException("Cubemap face index must be 0..5");
        validate(image.getWidth(),image.getHeight());
        if(faces.isEmpty())init(image.getWidth(),image.getHeight());
        if(image.getWidth()!=faces.getFirst().getWidth())throw new IllegalArgumentException("Call init to resize all cubemap faces together");
        final var replacement=NativeTextureImages.copy(image);
        try {
            final var encoder=RenderSystem.getDevice().createCommandEncoder();
            final var staging=encoder.transientMemory().uploadStaging(replacement.getPixelBytes(),1,16);
            encoder.copyBufferToTexture(staging,0,0,image.getWidth(),image.getHeight(),getTexture(),0,0,image.getWidth(),image.getHeight(),0,face);encoder.submit();
        } catch(RuntimeException|Error failure){replacement.close();throw failure;}
        final var updated=new ArrayList<>(faces);final var previous=updated.set(face,replacement);faces=List.copyOf(updated);previous.close();
    }
    @Override public synchronized TextureContents loadContents(ResourceManager resources)throws IOException {
        if(faces.isEmpty())throw new IOException("Dynamic cubemap must be initialized before reload");
        return new TextureContents(NativeTextureImages.stack(faces),null);
    }
    @Override public synchronized void close(){faces.forEach(NativeImage::close);faces=List.of();super.close();}
}
