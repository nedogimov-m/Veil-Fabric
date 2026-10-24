import com.mojang.brigadier.StringReader;
import foundry.veil.api.client.render.shader.NativeShaderDefaults;
import foundry.veil.api.client.util.ScissorStack;
import foundry.veil.impl.ClientCoordinates;
import net.minecraft.commands.arguments.coordinates.Vec3Argument;
import net.minecraft.core.Direction;
import net.minecraft.world.level.CardinalLighting;
import net.minecraft.world.phys.Vec2;
import net.minecraft.world.phys.Vec3;

/** Actual native command parsing, cardinal-light record and pure explicit scissor geometry. */
public final class NativeClientHelpersAcceptance {
    public static void main(String[] args) throws Exception {
        Vec3 origin = new Vec3(10, 20, 30);
        position("~1 ~-2 ~3", origin, new Vec2(0, 0), new Vec3(11, 18, 33));
        position("1 2 3", origin, new Vec2(0, 0), new Vec3(1.5, 2, 3.5));
        position("^1 ^2 ^3", origin, new Vec2(0, 0), new Vec3(11, 22, 33));
        position("^1 ^2 ^3", origin, new Vec2(0, 90), new Vec3(7, 22, 31));
        for (CardinalLighting lighting : new CardinalLighting[]{CardinalLighting.DEFAULT, CardinalLighting.NETHER}) {
            var values = NativeShaderDefaults.Values.capture(3_600_250, lighting::byFace);
            check(values.renderTime() == .25f, "Native default clock wraps one hour");
            float[] faces = values.faceBrightness();
            for (Direction direction : Direction.values()) check(faces[direction.get3DDataValue()] == lighting.byFace(direction), "Actual native cardinal lighting follows Direction shader indexing");
            faces[0] = -1;
            check(values.faceBrightness()[0] == lighting.byFace(Direction.DOWN), "Captured native face data is immutable");
        }
        try (var scissors = new ScissorStack(2, 8, 8)) {
            scissors.push(.25f, .5f, 2, 2);
            check(scissors.getTop().left() == 0 && scissors.getTop().top() == 1 && scissors.getTop().width() == 5 && scissors.getTop().height() == 4, "Root native scissor scales exactly once with outward rounding");
            scissors.push(1, 1, 3, 3);
            check(scissors.getTop().left() == 2 && scissors.getTop().top() == 2 && scissors.getTop().width() == 3 && scissors.getTop().height() == 3, "Nested native scissor intersects parent framebuffer pixels");
            check(scissors.containsPoint(1.25f, 1.25f) && !scissors.containsPoint(.75f, .75f), "GUI hit coordinates use the same single scale");
            scissors.push(3, 3, 1, 1);
            check(scissors.getTop().width() == 0 && scissors.getTop().height() == 0, "Disjoint child has genuinely empty bounds");
            scissors.pop(); scissors.pop();
            check(scissors.size() == 1 && scissors.getTop().top() == 1, "Pop restores exact parent bounds");
        }
        System.out.println("PASS native client coordinates, actual cardinal-light captures and explicit scissor geometry");
    }
    private static void position(String source, Vec3 origin, Vec2 rotation, Vec3 expected) throws Exception {
        var coordinates = Vec3Argument.vec3().parse(new StringReader(source));
        var result = ClientCoordinates.resolve(coordinates, origin, rotation);
        check(result.distanceToSqr(expected) < 1.0e-10, "Native coordinate parsing/resolution for " + source + ": " + result + " expected " + expected);
    }
    private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
}
