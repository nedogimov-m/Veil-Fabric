package foundry.veil.api.client.render.texture;

import net.minecraft.resources.Identifier;

/** Native array texture storage; images are prepared as equal-size vertically stacked layers. */
public abstract class ArrayTexture extends NativeLayeredTexture {
    protected ArrayTexture(Identifier location,int layers){super(location,layers,false);}
}
