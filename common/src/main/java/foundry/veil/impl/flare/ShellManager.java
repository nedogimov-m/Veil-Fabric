package foundry.veil.impl.flare;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import com.google.gson.JsonSyntaxException;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.JsonOps;
import foundry.veil.Veil;
import foundry.veil.api.flare.data.model.FlareShell;
import foundry.veil.api.flare.model.BakedShell;
import foundry.veil.api.flare.model.ShellBakery;
import net.minecraft.resources.FileToIdConverter;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.SimplePreparableReloadListener;
import net.minecraft.util.profiling.ProfilerFiller;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNull;
import org.lwjgl.system.NativeResource;

import java.io.Reader;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

@ApiStatus.Internal
public class ShellManager extends SimplePreparableReloadListener<Map<Identifier, BakedShell>> implements NativeResource {

    private static final FileToIdConverter CONVERTER = FileToIdConverter.json("flare/shells");

    private Map<Identifier, BakedShell> shells;

    public ShellManager() {
        this.shells = Map.of();
    }

    @Override
    protected @NotNull Map<Identifier, BakedShell> prepare(@NotNull ResourceManager resourceManager, @NotNull ProfilerFiller profiler) {
        Map<Identifier, BakedShell> data = new HashMap<>();

        Map<Identifier, Resource> resources = CONVERTER.listMatchingResources(resourceManager);
        for (Map.Entry<Identifier, Resource> entry : resources.entrySet()) {
            Identifier location = entry.getKey();
            Identifier id = CONVERTER.fileToId(location);

            try (Reader reader = entry.getValue().openAsReader()) {
                JsonElement element = JsonParser.parseReader(reader);
                DataResult<FlareShell> result = FlareShell.CODEC.parse(JsonOps.INSTANCE, element);

                if (result.error().isPresent()) {
                    throw new JsonSyntaxException(result.error().get().message());
                }

                if (data.put(id, result.result().orElseThrow().bake()) != null) {
                    throw new IllegalStateException("Duplicate data file ignored with ID " + id);
                }
            } catch (Exception e) {
                Veil.LOGGER.error("Couldn't parse data file {} from {}", id, location, e);
            }
        }

        return data;
    }

    @Override
    protected void apply(@NotNull Map<Identifier, BakedShell> map, @NotNull ResourceManager resourceManager, @NotNull ProfilerFiller profiler) {
        this.free();
        this.shells = Collections.unmodifiableMap(map);
    }

    public BakedShell getBakedShell(Identifier shellLocation) {
        return this.shells.getOrDefault(shellLocation, ShellBakery.MISSING_SHELL);
    }

    @Override
    public void free() {
        for (BakedShell shell : this.shells.values()) {
            shell.free();
        }
        this.shells = Map.of();
        ShellBakery.MISSING_SHELL.free();
    }
}
