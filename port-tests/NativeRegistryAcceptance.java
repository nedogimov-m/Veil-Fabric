package foundry.veil.porttest;

import com.google.gson.GsonBuilder;
import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import foundry.veil.api.resource.VeilDynamicRegistry;
import net.fabricmc.loader.api.entrypoint.PreLaunchEntrypoint;
import net.minecraft.SharedConstants;
import net.minecraft.core.Holder;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.codec.RegistryFileCodec;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.*;
import net.minecraft.server.Bootstrap;
import net.minecraft.server.packs.*;
import net.minecraft.server.packs.repository.PackSource;
import net.minecraft.server.packs.resources.*;

import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/** Actual native task/context/resource-pack integration; no rendering or fabricated target API. */
public final class NativeRegistryAcceptance implements PreLaunchEntrypoint {
    private static final ResourceKey<Registry<String>> VALUES = ResourceKey.createRegistryKey(id("values"));
    private static final ResourceKey<Registry<Holder<String>>> LINKS = ResourceKey.createRegistryKey(id("links"));
    private static final ResourceKey<Registry<String>> EMPTY = ResourceKey.createRegistryKey(id("empty"));
    private static Identifier id(String path) { return Identifier.fromNamespaceAndPath("veil_registry_test", path); }
    private static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
    private static void write(Path pack, ResourceKey<? extends Registry<?>> key, String name, String value) throws Exception {
        Identifier file = FileToIdConverter.registry(key).idToFile(id(name));
        Path path = pack.resolve("data").resolve(file.getNamespace()).resolve(file.getPath());
        Files.createDirectories(path.getParent()); Files.writeString(path, value);
    }
    private static MultiPackResourceManager resources(Path pack) {
        return new MultiPackResourceManager(PackType.SERVER_DATA, List.of(new PathPackResources(
                new PackLocationInfo("veil-registry-acceptance", Component.literal("Registry acceptance"), PackSource.DEFAULT, Optional.empty()), pack)));
    }
    @Override public void onPreLaunch() {
        Map<String, Object> result = new LinkedHashMap<>();
        try {
            SharedConstants.tryDetectVersion(); Bootstrap.bootStrap();
            Path root = Path.of(System.getProperty("veil.registry.work")); Files.createDirectories(root);
            Path pack = root.resolve("pack");
            write(pack, VALUES, "first", "\"alpha\""); write(pack, VALUES, "second", "\"beta\"");
            write(pack, LINKS, "first", "\"veil_registry_test:first\"");
            AtomicInteger decoded = new AtomicInteger();
            Codec<String> valueCodec = Codec.STRING.comapFlatMap(value -> {
                check(VeilDynamicRegistry.isLoading(), "Native async codec executes within loading scope");
                decoded.incrementAndGet(); return DataResult.success(value);
            }, value -> value);
            var values = new RegistryDataLoader.RegistryData<>(VALUES, valueCodec, RegistryValidator.none());
            var links = new RegistryDataLoader.RegistryData<>(LINKS, RegistryFileCodec.create(VALUES, valueCodec, false), RegistryValidator.none());
            var empty = new RegistryDataLoader.RegistryData<>(EMPTY, valueCodec, RegistryValidator.nonEmpty());
            try (var workers = Executors.newFixedThreadPool(3); var resources = resources(pack)) {
                var valid = VeilDynamicRegistry.loadRegistries(resources, List.of(links, values), workers).get(20, TimeUnit.SECONDS);
                check(valid.errors().isEmpty(), "Valid registries load without errors: " + valid.errors());
                var strings = valid.registryAccess().lookupOrThrow(VALUES);
                Holder<String> linked = valid.registryAccess().lookupOrThrow(LINKS).getValue(id("first"));
                check(linked != null && linked.isBound() && linked.value().equals("alpha"), "Cross-registry holder resolves to actual registered value");
                check(linked == strings.get(id("first")).orElseThrow(), "Reference holder identity belongs to returned native registry");
                check(decoded.get() == 2, "Each non-inline entry decodes once");
                check(!VeilDynamicRegistry.isLoading(), "Calling thread loading scope restored");
                for (int i = 0; i < 6; i++) check(!workers.submit(VeilDynamicRegistry::isLoading).get(), "Worker loading scope restored");
                result.put("crossRegistryAndAsyncScopes", "PASS");
            }
            write(pack, VALUES, "broken", "{invalid json");
            try (var workers = Executors.newFixedThreadPool(2); var resources = resources(pack)) {
                var partial = VeilDynamicRegistry.loadRegistries(resources, List.of(links, values, empty), workers).get(20, TimeUnit.SECONDS);
                check(partial.errors().containsKey(ResourceKey.create(VALUES, id("broken"))), "Malformed resource reports exact entry key");
                check(partial.registryAccess().lookupOrThrow(VALUES).getValue(id("second")).equals("beta"), "Independent valid registry entries survive invalid resources");
                check(partial.registryAccess().lookupOrThrow(LINKS).getValue(id("first")).value().equals("alpha"), "Valid cross-registry holder survives unrelated parse error");
                check(partial.registryAccess().lookup(EMPTY).isEmpty(), "Native nonempty validator excludes empty registry");
                String message = VeilDynamicRegistry.printErrors(partial.errors());
                check(message != null && message.contains("broken") && message.contains("empty"), "Error report includes parse and validation failures");
                boolean immutable = false; try { partial.errors().clear(); } catch (UnsupportedOperationException expected) { immutable = true; }
                check(immutable, "Published errors are immutable");
                result.put("partialErrorsFreezeValidation", "PASS");
            }
            write(pack, LINKS, "missing", "\"veil_registry_test:absent\"");
            try (var resources = resources(pack)) {
                var unresolved = VeilDynamicRegistry.loadRegistries(resources, List.of(links, values), Runnable::run).join();
                check(unresolved.registryAccess().lookup(VALUES).isEmpty(), "Target registry with an unresolved native holder fails freeze and is omitted");
                check(VeilDynamicRegistry.printErrors(unresolved.errors()).contains("absent"), "Native unresolved-holder freeze reports missing reference");
            }
            try (var resources = resources(pack)) {
                try { VeilDynamicRegistry.loadRegistries(resources, List.of(values), task -> { throw new RejectedExecutionException("intentional"); }); throw new AssertionError("Rejected executor must fail"); }
                catch (RejectedExecutionException expected) { }
                check(!VeilDynamicRegistry.isLoading(), "Synchronous executor failure restores loading scope");
            }
            try (var workers = Executors.newSingleThreadExecutor(); var failed = new MultiPackResourceManager(PackType.SERVER_DATA, List.of()) {
                @Override public Map<Identifier, Resource> listResources(String path, ResourceManager.Selector selector) { throw new IllegalStateException("intentional async resource failure"); }
            }) {
                try { VeilDynamicRegistry.loadRegistries(failed, List.of(values), workers).join(); throw new AssertionError("Resource failure must complete exceptionally"); }
                catch (CompletionException expected) { check(expected.getCause() instanceof IllegalStateException, "Async error retained"); }
                check(!workers.submit(VeilDynamicRegistry::isLoading).get(), "Asynchronous executor failure restores worker loading scope");
            }
            try (var resources = resources(pack)) {
                VeilDynamicRegistry.loadRegistries(resources, List.of(values), Runnable::run).join();
                check(!VeilDynamicRegistry.isLoading(), "Inline nested executor scopes restore caller");
            }
            result.put("failureAndInlineScopes", "PASS"); result.put("status", "passed"); result.put("checks", 3);
            Files.writeString(Path.of(System.getProperty("veil.registry.report")), new GsonBuilder().setPrettyPrinting().create().toJson(result));
            System.out.println("Native registry acceptance passed: " + result); System.exit(0);
        } catch (Throwable failure) {
            failure.printStackTrace(); result.put("status", "failed"); result.put("failure", failure.toString());
            try { Files.writeString(Path.of(System.getProperty("veil.registry.report")), new GsonBuilder().create().toJson(result)); } catch (Exception ignored) { }
            System.exit(1);
        }
    }
}
