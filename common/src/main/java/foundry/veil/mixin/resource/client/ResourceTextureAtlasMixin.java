package foundry.veil.mixin.resource.client;

import foundry.veil.ext.TextureAtlasExtension;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.resources.Identifier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

import java.util.Map;

@Mixin(TextureAtlas.class)
public class ResourceTextureAtlasMixin implements TextureAtlasExtension {

    @Shadow
    private Map<Identifier, TextureAtlasSprite> texturesByName;

    @Override
    public boolean veil$hasTexture(Identifier location) {
        return this.texturesByName.containsKey(location);
    }

    @Override public Map<Identifier,TextureAtlasSprite> veil$sprites(){return Map.copyOf(this.texturesByName);}
}
