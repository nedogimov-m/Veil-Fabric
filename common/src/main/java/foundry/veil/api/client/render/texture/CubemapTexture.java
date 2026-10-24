package foundry.veil.api.client.render.texture;

import net.minecraft.core.Direction;
import net.minecraft.resources.Identifier;

/** Native six-layer cube-compatible texture/view with the genuine TextureManager reload lifecycle. */
public abstract class CubemapTexture extends NativeLayeredTexture {
    protected CubemapTexture(Identifier location){super(location,6,true);}
    public static int faceIndex(Direction direction) {
        return switch(direction){case EAST->0;case WEST->1;case UP->2;case DOWN->3;case SOUTH->4;case NORTH->5;};
    }
}
