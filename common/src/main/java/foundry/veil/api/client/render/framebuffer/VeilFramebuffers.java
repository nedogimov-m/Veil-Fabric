package foundry.veil.api.client.render.framebuffer;

import net.minecraft.resources.Identifier;

/**
 * Default framebuffer names for use with {@link FramebufferManager#getFramebuffer(Identifier)}.
 *
 * @author Ocelot
 */
public final class VeilFramebuffers {

    private VeilFramebuffers() {
    }

    public static final Identifier MAIN = Identifier.withDefaultNamespace("main");
    public static final Identifier FIRST_PERSON = buffer("first_person");
    public static final Identifier BLOOM = buffer("bloom");
    public static final Identifier LIGHT = buffer("light");
    public static final Identifier POST = buffer("post");

    public static final Identifier TRANSLUCENT_TARGET = transparency("translucent");
    public static final Identifier ITEM_ENTITY_TARGET = transparency("item_entity");
    public static final Identifier PARTICLES_TARGET = transparency("particles");
    public static final Identifier WEATHER_TARGET = transparency("weather");
    public static final Identifier CLOUDS_TARGET = transparency("clouds");

    private static Identifier transparency(String name) {
        return Identifier.withDefaultNamespace(name);
    }

    private static Identifier buffer(String name) {
        return Identifier.fromNamespaceAndPath("veil", name);
    }
}
