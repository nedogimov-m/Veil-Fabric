package foundry.veil.impl.client.render.rendertype;

import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;
import foundry.veil.api.client.render.rendertype.VeilRenderType;
import foundry.veil.api.client.render.rendertype.layer.CompositeRenderTypeData;
import net.minecraft.resources.FileToIdConverter;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.SimplePreparableReloadListener;
import net.minecraft.util.profiling.ProfilerFiller;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.ExecutionException;

/** Atomic resource reloads and parameter caches keyed by actual values, with native variant retirement. */
public final class DynamicRenderTypeManager extends SimplePreparableReloadListener<Map<Identifier, byte[]>> implements AutoCloseable {
    private static final Logger LOGGER = LoggerFactory.getLogger("Veil/RenderTypes");
    private static final FileToIdConverter CONVERTER = FileToIdConverter.json("pinwheel/rendertypes");
    private volatile Map<Identifier, RenderTypeCache> renderTypes = Map.of();
    public DynamicRenderTypeManager() { VeilRenderType.setDynamicManager(this); }
    public @Nullable net.minecraft.client.renderer.rendertype.RenderType get(Identifier id, Object... params) {
        final var cache = this.renderTypes.get(id);
        return cache == null ? null : cache.get(params);
    }
    @Override protected Map<Identifier, byte[]> prepare(ResourceManager resources, ProfilerFiller profiler) {
        final var data = new LinkedHashMap<Identifier, byte[]>();
        for (var entry : CONVERTER.listMatchingResources(resources).entrySet()) {
            final var id = CONVERTER.fileToId(entry.getKey());
            try (var stream = entry.getValue().open()) { data.put(id, stream.readAllBytes()); }
            catch (Exception exception) { LOGGER.error("Couldn't read render type {}", id, exception); }
        }
        return Map.copyOf(data);
    }
    @Override protected void apply(Map<Identifier, byte[]> files, ResourceManager resources, ProfilerFiller profiler) {
        final var replacement = new LinkedHashMap<Identifier, RenderTypeCache>();
        for (var entry : files.entrySet()) {
            try (var reader = new InputStreamReader(new java.io.ByteArrayInputStream(entry.getValue()), StandardCharsets.UTF_8)) {
                final var data = CompositeRenderTypeData.CODEC.parse(JsonOps.INSTANCE, JsonParser.parseReader(reader)).getOrThrow();
                replacement.put(entry.getKey(), new RenderTypeCache(entry.getKey().toString(), data));
            } catch (Exception exception) { LOGGER.error("Couldn't parse render type {}", entry.getKey(), exception); }
        }
        final var previous = this.renderTypes;
        this.renderTypes = Map.copyOf(replacement);
        previous.values().forEach(RenderTypeCache::close);
        LOGGER.info("Loaded {} native render types", replacement.size());
    }
    @Override public void close() {
        final var previous = this.renderTypes;
        this.renderTypes = Map.of();
        previous.values().forEach(RenderTypeCache::close);
    }
    private static final class RenderTypeCache implements AutoCloseable {
        private final String name;
        private final CompositeRenderTypeData data;
        private final Cache<Params, net.minecraft.client.renderer.rendertype.RenderType> variants;
        private net.minecraft.client.renderer.rendertype.RenderType defaultType;
        private boolean defaultFailed;
        private RenderTypeCache(String name, CompositeRenderTypeData data) {
            this.name = name; this.data = data;
            this.variants = CacheBuilder.newBuilder().initialCapacity(4).maximumSize(64).expireAfterAccess(Duration.ofSeconds(1000))
                    .<Params, net.minecraft.client.renderer.rendertype.RenderType>removalListener(removal -> {
                        if (removal.getValue() != null) VeilRenderType.release(removal.getValue());
                    }).build();
        }
        private synchronized @Nullable net.minecraft.client.renderer.rendertype.RenderType get(Object[] params) {
            if (params.length == 0) {
                if (this.defaultFailed) return null;
                if (this.defaultType == null) {
                    try { this.defaultType = this.data.createRenderType(this.name); }
                    catch (Exception exception) {
                        LOGGER.error("Failed to create render type {} without parameters", this.name, exception);
                        this.defaultFailed = true;
                    }
                }
                return this.defaultType;
            }
            final var key = new Params(params);
            try { return this.variants.get(key, () -> this.data.createRenderType(this.name, key.values)); }
            catch (ExecutionException exception) {
                LOGGER.error("Failed to create render type {} with {}", this.name, Arrays.deepToString(key.values), exception.getCause());
                return null;
            }
        }
        @Override public synchronized void close() {
            if (this.defaultType != null) VeilRenderType.release(this.defaultType);
            this.defaultType = null;
            this.variants.invalidateAll(); this.variants.cleanUp();
        }
    }
    /** Array values are copied recursively so a caller cannot invalidate the cache's key after insertion. */
    static final class Params {
        private final Object[] values;
        private final int hash;
        Params(Object[] values) { this.values = (Object[]) copy(values); this.hash = Arrays.deepHashCode(this.values); }
        private static Object copy(Object value) {
            if (value == null || !value.getClass().isArray()) return value;
            final int size = java.lang.reflect.Array.getLength(value);
            final var result = java.lang.reflect.Array.newInstance(value.getClass().getComponentType(), size);
            for (int index = 0; index < size; index++) java.lang.reflect.Array.set(result, index, copy(java.lang.reflect.Array.get(value, index)));
            return result;
        }
        @Override public int hashCode() { return this.hash; }
        @Override public boolean equals(Object other) { return other instanceof Params params && Arrays.deepEquals(this.values, params.values); }
    }
}
