package foundry.veil.api.resource;

import com.mojang.serialization.Lifecycle;
import foundry.veil.mixin.registry.accessor.RegistryDataAccessor;
import net.minecraft.util.Util;
import net.minecraft.core.Registry;
import net.minecraft.core.RegistryAccess;
import net.minecraft.resources.RegistryDataLoader;
import net.minecraft.resources.RegistryLoadTask;
import net.minecraft.resources.ResourceManagerRegistryLoadTask;
import net.minecraft.resources.RegistryOps;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.ResourceManager;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.Nullable;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.stream.Collectors;

/**
 * Allows the creation of normal vanilla dynamic registries. They can be created client or server side depending on the use case.
 *
 * @author Ocelot
 * @since 1.0.0
 */
public class VeilDynamicRegistry {

    private static final ThreadLocal<Boolean> LOADING = ThreadLocal.withInitial(() -> false);

    /**
     * Loads the specified registries from the normal registry folder.
     *
     * @param resourceManager The manager for all resources
     * @param registries      The registries to load
     * @param executor        The executor to load all registries on
     * @return All loaded registries and their errors
     */
    @SuppressWarnings("RedundantOperationOnEmptyContainer")
    public static CompletableFuture<Data> loadRegistries(ResourceManager resourceManager, Collection<RegistryDataLoader.RegistryData<?>> registries, Executor executor) {
        Map<ResourceKey<?>, Exception> errors = new ConcurrentHashMap<>();
        List<RegistryLoadTask<?>> loaders = registries.stream()
                .<RegistryLoadTask<?>>map(data -> new ResourceManagerRegistryLoadTask<>(data, Lifecycle.stable(), errors, resourceManager))
                .toList();
        RegistryOps.RegistryInfoLookup ctx = RegistryDataAccessor.veil$createContext(List.of(), loaders);
        Executor scoped = task -> executor.execute(() -> {
            boolean previous = LOADING.get(); LOADING.set(true);
            try { task.run(); } finally { LOADING.set(previous); }
        });
        List<CompletableFuture<?>> pending = loaders.stream().<CompletableFuture<?>>map(loader -> {
            boolean previous = LOADING.get(); LOADING.set(true);
            try { return loader.load(ctx, scoped); } finally { LOADING.set(previous); }
        }).toList();
        return CompletableFuture.allOf(pending.toArray(CompletableFuture[]::new)).thenApply(ignored -> {
            List<Registry<?>> loaded = new java.util.ArrayList<>();
            // Native validators may inspect another registry: freeze every registry before validating any.
            List<RegistryLoadTask<?>> frozen = loaders.stream().filter(loader -> loader.freezeRegistry(errors)).toList();
            for (RegistryLoadTask<?> loader : frozen) loader.validateRegistry(errors).ifPresent(loaded::add);
            return new Data(new RegistryAccess.ImmutableRegistryAccess(loaded).freeze(), Map.copyOf(errors));
        });
    }

    /**
     * Prints all errors from loading registries into a string.
     *
     * @param errors The errors to print
     * @return The errors or <code>null</code> if there were no errors
     */
    public static @Nullable String printErrors(Map<ResourceKey<?>, Exception> errors) {
        if (errors.isEmpty()) {
            return null;
        }

        StringWriter stringWriter = new StringWriter();
        PrintWriter printWriter = new PrintWriter(stringWriter);
        Map<Identifier, Map<Identifier, Exception>> sortedErrors = errors.entrySet().stream().collect(Collectors.groupingBy(entry -> entry.getKey().registry(), Collectors.toMap(entry -> entry.getKey().identifier(), Map.Entry::getValue)));
        sortedErrors.entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(registryError -> {
            printWriter.printf("%n> %d Errors in registry %s:", registryError.getValue().size(), registryError.getKey());
            registryError.getValue().entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(elementError -> {
                Throwable error = elementError.getValue();
                while (error.getCause() != null) {
                    error = error.getCause();
                }
                printWriter.printf("%n>> Error in element %s: %s", elementError.getKey(), error.getMessage());
            });
        });
        printWriter.flush();
        return stringWriter.toString();
    }

    @ApiStatus.Internal
    public static boolean isLoading() {
        return LOADING.get();
    }

    /**
     * Stored data about loaded dynamic registries.
     *
     * @param registryAccess The created registry access
     * @param errors         Any errors thrown while loading registries
     * @since 1.0.0
     */
    public record Data(RegistryAccess registryAccess, Map<ResourceKey<?>, Exception> errors) {
    }
}
