package foundry.veil.api.client.render.texture;

import net.minecraft.client.renderer.texture.TextureContents;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.ResourceManager;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.*;

/** Resource layers prepare off-thread; native reload applies their real array texture/view. */
public class SimpleArrayTexture extends ArrayTexture implements VeilPreloadedTexture {
    private final Identifier[] locations;
    private CompletableFuture<TextureContents> prepared;
    public SimpleArrayTexture(Identifier... locations){super(first(locations),locations.length);this.locations=locations.clone();}
    private static Identifier first(Identifier[] locations){if(locations.length==0)throw new IllegalArgumentException("Array needs at least one location");return Objects.requireNonNull(locations[0]);}
    private TextureContents prepare(ResourceManager resources)throws IOException {
        final var contents=new ArrayList<TextureContents>();
        try {
            for(var location:locations)contents.add(TextureContents.load(resources,location));
            try {return new TextureContents(NativeTextureImages.stack(contents.stream().map(TextureContents::image).toList()),contents.getFirst().metadata());}
            catch(IllegalArgumentException failure){throw new IOException("Invalid array texture layers",failure);}
        } finally {contents.forEach(TextureContents::close);}
    }
    @Override public synchronized CompletableFuture<?> preload(ResourceManager resources,Executor workers) {
        if(prepared==null)prepared=CompletableFuture.supplyAsync(()->{try{return prepare(resources);}catch(IOException failure){throw new CompletionException(failure);}},workers);
        return prepared;
    }
    @Override public TextureContents loadContents(ResourceManager resources)throws IOException {
        final CompletableFuture<TextureContents> pending;synchronized(this){pending=prepared;prepared=null;}
        if(pending==null)return prepare(resources);
        try{return pending.join();}catch(CompletionException failure){if(failure.getCause() instanceof IOException io)throw io;throw failure;}
    }
    @Override public void close() {
        final CompletableFuture<TextureContents> pending;synchronized(this){pending=prepared;prepared=null;}
        if(pending!=null)pending.thenAccept(TextureContents::close);super.close();
    }
}
