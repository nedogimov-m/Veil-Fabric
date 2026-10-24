package foundry.veil.api.client.render.texture;

import net.minecraft.client.renderer.texture.TextureContents;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.ResourceManager;
import java.io.IOException;
import java.util.concurrent.*;

/** Resource 4x3 cross, prepared off-thread and uploaded by native ReloadableTexture.apply. */
public class SimpleCubemapTexture extends CubemapTexture implements VeilPreloadedTexture {
    private CompletableFuture<TextureContents> prepared;
    public SimpleCubemapTexture(Identifier location){super(location);}
    private TextureContents prepare(ResourceManager resources)throws IOException {
        try(var original=TextureContents.load(resources,resourceId())) {
            try {return new TextureContents(NativeTextureImages.cross(original.image()),original.metadata());}
            catch(IllegalArgumentException failure){throw new IOException("Invalid cubemap cross: "+resourceId(),failure);}
        }
    }
    @Override public synchronized CompletableFuture<?> preload(ResourceManager resources,Executor workers) {
        if(prepared==null)prepared=CompletableFuture.supplyAsync(()->{try{return prepare(resources);}catch(IOException failure){throw new CompletionException(failure);}},workers);
        return prepared;
    }
    @Override public TextureContents loadContents(ResourceManager resources)throws IOException {
        final CompletableFuture<TextureContents> pending;
        synchronized(this){pending=prepared;prepared=null;}
        if(pending==null)return prepare(resources);
        try {return pending.join();}catch(CompletionException failure){if(failure.getCause() instanceof IOException io)throw io;throw failure;}
    }
    @Override public void close() {
        final CompletableFuture<TextureContents> pending;
        synchronized(this){pending=prepared;prepared=null;}
        if(pending!=null)pending.thenAccept(TextureContents::close);
        super.close();
    }
}
