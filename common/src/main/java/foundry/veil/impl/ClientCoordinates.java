package foundry.veil.impl;

import net.minecraft.commands.arguments.coordinates.Coordinates;
import net.minecraft.commands.arguments.coordinates.LocalCoordinates;
import net.minecraft.commands.arguments.coordinates.WorldCoordinates;
import net.minecraft.world.phys.Vec2;
import net.minecraft.world.phys.Vec3;

/** Resolve actual parsed coordinates against a client source without manufacturing a server command stack. */
public final class ClientCoordinates {
    public static Vec3 resolve(Coordinates coordinates, Vec3 position, Vec2 rotation) {
        if (coordinates instanceof WorldCoordinates world)
            return new Vec3(world.x().get(position.x), world.y().get(position.y), world.z().get(position.z));
        if (coordinates instanceof LocalCoordinates local) return local.apply(position, rotation);
        throw new IllegalArgumentException("Unsupported native client coordinate owner: " + coordinates.getClass().getName());
    }
    private ClientCoordinates() { }
}
