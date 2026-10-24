package foundry.veil.impl.client.render.shader.injection;

import com.google.gson.Gson;
import foundry.veil.impl.client.render.shader.injection.util.ShaderInjectionDefinition;
import foundry.veil.impl.client.render.shader.injection.util.ValidationResult;
import net.minecraft.resources.FileToIdConverter;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.Unmodifiable;

import java.io.Reader;
import java.util.*;

/**
 * Loads and indexes shader injection definitions
 * from {@code pinwheel/shader_injection/*.json} resources.
 *
 * @author Vowxky
 */
@ApiStatus.Internal
public final class ShaderInjectionLoader {
    private static final org.slf4j.Logger LOGGER = org.slf4j.LoggerFactory.getLogger("Veil");

    public static final FileToIdConverter INJECTION_LISTER = new FileToIdConverter("pinwheel/shader_injection", ".json");
    private static final Gson GSON = ShaderInjectionDefinition.createGson();

    public static LoadedPatches load(ResourceManager resourceManager) {
        List<LoadedPatch> patches = new LinkedList<>();
        Map<Identifier, List<LoadedPatch>> byTarget = new LinkedHashMap<>();

        for (Map.Entry<Identifier, Resource> entry : INJECTION_LISTER.listMatchingResources(resourceManager).entrySet()) {
            Identifier file = entry.getKey();
            Identifier defaultId = INJECTION_LISTER.fileToId(file);

            try (Reader reader = entry.getValue().openAsReader()) {
                ShaderInjectionDefinition definition = GSON.fromJson(reader, ShaderInjectionDefinition.class).withDefaultId(defaultId);
                ValidationResult validation = ShaderInjectionValidator.validate(definition, file.toString());
                if (!validation.isValid()) {
                    LOGGER.warn("Skipping invalid shader injection {} from {}: {}", defaultId, file, validation.diagnostics());
                    continue;
                }
                patches.add(new LoadedPatch(file, definition));
            } catch (Exception e) {
                LOGGER.error("Couldn't load shader injection {} from {}", defaultId, file, e);
            }
        }

        for (LoadedPatch patch : patches) {
            for (Identifier target : patch.definition().targets()) {
                byTarget.computeIfAbsent(target, k -> new ArrayList<>()).add(patch);
            }
        }
        byTarget.values().forEach(g -> g.sort(Comparator.comparingInt(p -> p.definition().priority())));
        byTarget.replaceAll((t, g) -> List.copyOf(g));
        return new LoadedPatches(byTarget);
    }

    public record LoadedPatches(@Unmodifiable Map<Identifier, @Unmodifiable List<LoadedPatch>> byTarget) {

        public LoadedPatches {
            byTarget = Map.copyOf(byTarget);
        }
    }

    public record LoadedPatch(Identifier resourceLocation, ShaderInjectionDefinition definition) {
    }
}
