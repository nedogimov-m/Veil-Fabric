package foundry.veil.mixin.pipeline.client;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.mojang.blaze3d.systems.RenderSystem;
import foundry.veil.api.client.render.texture.*;
import foundry.veil.ext.TextureManagerExtension;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.*;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.ResourceManager;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.*;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.*;

/** Native register-and-load creates the real texture; native reload discovers both custom reloadable types. */
@Mixin(TextureManager.class)
public abstract class PipelineTextureManagerMixin implements TextureManagerExtension {
    @Shadow @Final private Map<Identifier,AbstractTexture> byPath;
    @Shadow @Final private ResourceManager resourceManager;
    @Shadow public abstract void registerAndLoad(Identifier path,ReloadableTexture texture);
    @Unique private final Map<Identifier,CompletableFuture<?>> veil$preloads=new ConcurrentHashMap<>();

    @WrapMethod(method="getTexture")
    private AbstractTexture veil$nativeType(Identifier path,Operation<AbstractTexture> original) {
        final var existing=byPath.get(path);if(existing!=null)return existing;
        final var resource=resourceManager.getResource(path);if(resource.isEmpty())return original.call(path);
        try {
            final var section=resource.get().metadata().getSection(TextureTypeMetadataSection.TYPE);
            if(section.isEmpty()||section.get().type()==TextureTypeMetadataSection.TextureType.TEXTURE_2D)return original.call(path);
            final ReloadableTexture texture=switch(section.get().type()){
                case TEXTURE_2D_ARRAY->new SimpleArrayTexture(path);case TEXTURE_CUBE_MAP->new SimpleCubemapTexture(path);
                case TEXTURE_2D->throw new IllegalStateException("Ordinary texture belongs to vanilla");
            };
            registerAndLoad(path,texture);return texture;
        } catch(IOException failure){throw new java.io.UncheckedIOException("Cannot read texture type: "+path,failure);}
    }
    @Override public <T extends ReloadableTexture & VeilPreloadedTexture> CompletableFuture<?> veil$registerPreloadedTexture(Identifier path,T texture,Executor workers) {
        RenderSystem.assertOnRenderThread();
        if(byPath.containsKey(path))return CompletableFuture.completedFuture(null);
        final var pending=veil$preloads.get(path);if(pending!=null)return pending;
        final var future=texture.preload(resourceManager,workers).thenRunAsync(()->registerAndLoad(path,texture),Minecraft.getInstance());
        veil$preloads.put(path,future);future.whenComplete((unused,failure)->veil$preloads.remove(path,future));return future;
    }
}
