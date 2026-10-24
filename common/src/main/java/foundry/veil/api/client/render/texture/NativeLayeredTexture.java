package foundry.veil.api.client.render.texture;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.textures.GpuTexture;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import net.minecraft.client.renderer.texture.ReloadableTexture;
import net.minecraft.client.renderer.texture.TextureContents;
import net.minecraft.resources.Identifier;

/** Real native layered storage; a failed replacement never destroys the previous texture/view. */
public abstract class NativeLayeredTexture extends ReloadableTexture {
    private final int layers;
    private final boolean cube;
    protected NativeLayeredTexture(Identifier location,int layers,boolean cube) {
        super(location);if(layers<1)throw new IllegalArgumentException("Texture needs at least one layer");
        this.layers=layers;this.cube=cube;
    }
    @Override public void apply(TextureContents contents) {
        try(contents){doLoad(contents.image());setSampler(contents);}
    }
    @Override protected void doLoad(NativeImage image) {
        RenderSystem.assertOnRenderThread();
        if(image.format()!=NativeImage.Format.RGBA||image.getHeight()%layers!=0)
            throw new IllegalArgumentException("Layer upload requires a vertically stacked RGBA image");
        final int width=image.getWidth(),height=image.getHeight()/layers;
        if(cube&&width!=height)throw new IllegalArgumentException("Cubemap faces must be square");
        final var device=RenderSystem.getDevice();
        if(!cube)NativeArrayTextureViews.requireBackend(device);
        final int usage=GpuTexture.USAGE_COPY_SRC|GpuTexture.USAGE_COPY_DST|GpuTexture.USAGE_TEXTURE_BINDING
                |(cube?GpuTexture.USAGE_CUBEMAP_COMPATIBLE:0);
        final var replacement=cube?device.createTexture(resourceId()::toString,usage,GpuFormat.RGBA8_UNORM,width,height,layers,1)
                :NativeArrayTextureViews.allocate(device,resourceId().toString(),width,height,layers);
        GpuTextureView view=null;
        try {
            view=cube?device.createTextureView(replacement):NativeArrayTextureViews.create(device,replacement);
            final var encoder=device.createCommandEncoder();
            final var staging=encoder.transientMemory().uploadStaging(image.getPixelBytes(),1,16);
            for(int layer=0;layer<layers;layer++)encoder.copyBufferToTexture(staging,0,layer*height,width,image.getHeight(),
                    replacement,0,0,width,height,0,layer);
            encoder.submit();
        } catch(RuntimeException|Error failure){if(view!=null)view.close();replacement.close();throw failure;}
        final var previousView=textureView;final var previous=texture;texture=replacement;textureView=view;
        if(previousView!=null)previousView.close();if(previous!=null)previous.close();
    }
}
