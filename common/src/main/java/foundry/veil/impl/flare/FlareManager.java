package foundry.veil.impl.flare;

import foundry.veil.api.quasar.data.*;
import foundry.veil.api.resource.VeilDynamicRegistry;
import net.minecraft.core.Registry;
import net.minecraft.core.RegistryAccess;
import net.minecraft.resources.RegistryDataLoader;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.PreparableReloadListener;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.util.profiling.ProfilerFiller;
import foundry.veil.Veil;
import foundry.veil.api.flare.data.effect.FlareEffectTemplate;
import foundry.veil.api.flare.data.effect.FlareModule;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

import static foundry.veil.Veil.LOGGER;

@ApiStatus.Internal
public class FlareManager {

    public static final ResourceKey<Registry<FlareEffectTemplate>> EFFECT_TEMPLATES = createRegistryKey("flare/templates");
    public static final ResourceKey<Registry<FlareModule>> EFFECT_MODULES = createRegistryKey("flare/modules");

    private static final List<RegistryDataLoader.RegistryData<?>> REGISTRIES = List.of(
            new RegistryDataLoader.RegistryData<>(EFFECT_TEMPLATES, FlareEffectTemplate.CODEC, net.minecraft.resources.RegistryValidator.none()),
            new RegistryDataLoader.RegistryData<>(EFFECT_MODULES, FlareModule.CODEC, net.minecraft.resources.RegistryValidator.none())
    );
    private static RegistryAccess registryAccess = RegistryAccess.EMPTY;

    private FlareManager() {
    }

    public static void bootstrap() {
    }

    private static <T> ResourceKey<Registry<T>> createRegistryKey(String name) {
        return ResourceKey.createRegistryKey(Veil.veilPath(name));
    }

    public static RegistryAccess registryAccess() {
        return registryAccess;
    }

    public static class Reloader implements PreparableReloadListener {

        public static final Reloader INSTANCE = new Reloader();

        private Reloader() {
        }

        @Override
        public @NotNull CompletableFuture<Void> reload(SharedState shared, @NotNull Executor backgroundExecutor, PreparationBarrier preparationBarrier, @NotNull Executor gameExecutor) {
            ResourceManager resourceManager = shared.resourceManager();
            return VeilDynamicRegistry.loadRegistries(resourceManager, REGISTRIES, backgroundExecutor)
                    .thenCompose(preparationBarrier::wait)
                    .thenAcceptAsync(data -> {
                        registryAccess = data.registryAccess();
                        data.errors().values().forEach(Exception::printStackTrace);
                        String msg = VeilDynamicRegistry.printErrors(data.errors());
                        if (msg != null) {
                            LOGGER.error("Flare registry loading errors:{}", msg);
                        }
                        registryAccess.lookup(EFFECT_TEMPLATES).ifPresent(registry -> LOGGER.info("Loaded {} templates", registry.size()));
                        registryAccess.lookup(EFFECT_MODULES).ifPresent(registry -> LOGGER.info("Loaded {} modules", registry.size()));
                    }, gameExecutor);
        }

        @Override
        public @NotNull String getName() {
            return QuasarParticles.class.getSimpleName();
        }
    }
}
