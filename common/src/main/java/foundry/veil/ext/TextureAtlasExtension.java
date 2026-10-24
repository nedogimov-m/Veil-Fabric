package foundry.veil.ext;

import net.minecraft.resources.Identifier;
import org.jetbrains.annotations.ApiStatus;

@ApiStatus.Internal
public interface TextureAtlasExtension {

    boolean veil$hasTexture(Identifier location);

    /** Immutable lookup snapshot of the actual resident sprite objects, for editor model baking. */
    java.util.Map<Identifier,net.minecraft.client.renderer.texture.TextureAtlasSprite> veil$sprites();
}
