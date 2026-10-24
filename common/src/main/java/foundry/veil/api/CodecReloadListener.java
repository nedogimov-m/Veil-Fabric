package foundry.veil.api;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import com.google.gson.JsonSyntaxException;
import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.DynamicOps;
import com.mojang.serialization.JsonOps;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import net.minecraft.core.HolderLookup;
import net.minecraft.resources.FileToIdConverter;
import net.minecraft.resources.RegistryOps;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.SimpleJsonResourceReloadListener;
import net.minecraft.server.packs.resources.SimplePreparableReloadListener;
import net.minecraft.util.profiling.ProfilerFiller;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.Reader;
import java.util.HashMap;
import java.util.Map;

/**
 * Loads files similarly to {@link SimpleJsonResourceReloadListener},
 * but also decodes them using the provided codec.
 *
 * @param <T> The type of mapped data to return
 * @see Codec
 * @since 1.0.0
 */
public abstract class CodecReloadListener<T> extends SimplePreparableReloadListener<Map<Identifier, T>> {

    private static final Logger LOGGER = LoggerFactory.getLogger("Veil");

    protected final Codec<T> codec;
    protected final FileToIdConverter converter;
    private final HolderLookup.Provider registries;

    /**
     * Creates a new codec reload listener.
     *
     * @param codec     The codec to use when deserializing files
     * @param converter The converter to use for listing files
     */
    public CodecReloadListener(Codec<T> codec, FileToIdConverter converter) {
        this(codec, converter, null);
    }

    /**
     * Creates a new codec reload listener.
     *
     * @param codec     The codec to use when deserializing files
     * @param converter The converter to use for listing files
     */
    public CodecReloadListener(Codec<T> codec, FileToIdConverter converter, @Nullable HolderLookup.Provider registries) {
        this.codec = codec;
        this.converter = converter;
        this.registries = registries;
    }

    @Override
    protected @NotNull Map<Identifier, T> prepare(@NotNull ResourceManager resourceManager, @NotNull ProfilerFiller profilerFiller) {
        Map<Identifier, T> data = new HashMap<>();

        DynamicOps<JsonElement> ops = this.registries != null ? RegistryOps.create(JsonOps.INSTANCE, this.registries) : JsonOps.INSTANCE;
        Map<Identifier, Resource> resources = this.converter.listMatchingResources(resourceManager);
        for (Map.Entry<Identifier, Resource> entry : resources.entrySet()) {
            Identifier location = entry.getKey();
            Identifier id = this.converter.fileToId(location);

            try (Reader reader = entry.getValue().openAsReader()) {
                JsonElement element = JsonParser.parseReader(reader);
                DataResult<T> result = this.codec.parse(ops, element);

                if (result.error().isPresent()) {
                    throw new JsonSyntaxException(result.error().get().message());
                }
                if (data.put(id, result.result().orElseThrow()) != null) {
                    throw new IllegalStateException("Duplicate data file ignored with ID " + id);
                }
            } catch (Exception e) {
                LOGGER.error("Couldn't parse data file {} from {}", id, location, e);
            }
        }

        return data;
    }
}
