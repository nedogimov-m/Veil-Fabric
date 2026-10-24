package foundry.veil.impl.client.render.shader.injection.util;

import com.google.gson.*;
import net.minecraft.resources.Identifier;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.Nullable;

import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.List;

/**
 * JSON-deserialized model for shader injection definitions.
 *
 * @author Vowxky
 */
@ApiStatus.Internal
public record ShaderInjectionDefinition(
        @Nullable Identifier id,
        List<Identifier> targets,
        List<Identifier> redirects,
        int priority,
        @Nullable Identifier replace,
        boolean debug) {

    public static final int DEFAULT_PRIORITY = 1000;

    public ShaderInjectionDefinition {
        if (priority == 0) {
            priority = DEFAULT_PRIORITY;
        }
        targets = List.copyOf(targets);
        redirects = List.copyOf(redirects);
    }

    public static Gson createGson() {
        return new GsonBuilder()
                .registerTypeAdapter(Identifier.class, (JsonDeserializer<Identifier>) (json, type, context) -> Identifier.parse(json.getAsString()))
                .registerTypeAdapter(Identifier.class, (JsonSerializer<Identifier>) (location, type, context) -> context.serialize(location.toString()))
                .registerTypeAdapter(ShaderInjectionDefinition.class, new Deserializer())
                .create();
    }

    public ShaderInjectionDefinition withDefaultId(Identifier defaultId) {
        return new ShaderInjectionDefinition(
                this.id != null ? this.id : defaultId,
                this.targets,
                this.redirects,
                this.priority,
                this.replace,
                this.debug
        );
    }

    public @Nullable Identifier target() {
        return this.targets.isEmpty() ? null : this.targets.getFirst();
    }

    private static class Deserializer implements JsonDeserializer<ShaderInjectionDefinition> {

        @Override
        public ShaderInjectionDefinition deserialize(JsonElement json, Type typeOfT, JsonDeserializationContext context) throws JsonParseException {
            JsonObject obj = json.getAsJsonObject();

            List<Identifier> targets = parseIdentifiers(obj, "target");
            List<Identifier> redirects = parseIdentifiers(obj, "redirect");
            int priority = obj.has("priority") ? obj.get("priority").getAsInt() : DEFAULT_PRIORITY;
            Identifier replace = obj.has("replace") ? context.deserialize(obj.get("replace"), Identifier.class) : null;
            boolean debug = obj.has("debug") && obj.get("debug").getAsBoolean();

            return new ShaderInjectionDefinition(null, targets, redirects, priority, replace, debug);
        }
    }

    private static List<Identifier> parseIdentifiers(JsonObject obj, String field) {
        if (!obj.has(field) || obj.get(field) instanceof JsonNull) {
            return List.of();
        }
        JsonElement element = obj.get(field);
        if (element instanceof JsonArray array) {
            List<Identifier> result = new ArrayList<>();
            for (JsonElement el : array) {
                result.add(Identifier.parse(el.getAsString()));
            }
            return result;
        }
        return List.of(Identifier.parse(element.getAsString()));
    }
}
