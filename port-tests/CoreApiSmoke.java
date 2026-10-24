import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.serialization.Codec;
import com.mojang.serialization.JsonOps;
import com.mojang.serialization.Lifecycle;
import com.google.gson.JsonPrimitive;
import foundry.veil.api.CodecReloadListener;
import foundry.veil.api.client.color.Color;
import foundry.veil.api.network.VeilPacketManager;
import foundry.veil.fabric.platform.FabricRegistrationFactory;
import foundry.veil.mixin.pipeline.client.PipelinePoseStackMixin;
import net.minecraft.core.MappedRegistry;
import net.minecraft.resources.FileToIdConverter;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.network.protocol.game.ClientboundBundlePacket;
import net.minecraft.server.packs.PackResources;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.util.profiling.InactiveProfiler;
import net.minecraft.util.profiling.ProfilerFiller;
import org.spongepowered.asm.mixin.Shadow;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;

/** Runs only the explicitly documented migrated core component checks. */
public final class CoreApiSmoke {
    public static void main(String[] args) throws Exception {
        var selected = args.length == 0 ? Set.of("packets", "registry", "resource", "colors", "pose") : Set.of(args);
        if (selected.contains("packets")) packets();
        if (selected.contains("registry")) registry();
        if (selected.contains("resource")) resourceReload();
        if (selected.contains("colors")) colors();
        if (selected.contains("pose")) poseShadowContract();
        System.out.println("PASS: " + selected);
    }

    private static void packets() {
        var sent = new ArrayList<Packet<?>>();
        VeilPacketManager.PacketSink sink = sent::add;
        var first = new TestPayload("first");
        var second = new TestPayload("second");
        sink.sendPacket(new CustomPacketPayload[0]);
        check(sent.isEmpty(), "Empty payload lists must not emit packets");
        sink.sendPacket(first);
        check(sent.size() == 1 && sent.getFirst() instanceof ClientboundCustomPayloadPacket single && single.payload() == first,
                "A single payload must keep its identity and avoid a bundle");
        sent.clear();
        sink.sendPacket(first, second);
        check(sent.size() == 1 && sent.getFirst() instanceof ClientboundBundlePacket, "Multiple payloads must share a bundle");
        var children = new ArrayList<Packet<?>>();
        ((ClientboundBundlePacket) sent.getFirst()).subPackets().forEach(children::add);
        check(children.size() == 2 && ((ClientboundCustomPayloadPacket) children.get(0)).payload() == first
                && ((ClientboundCustomPayloadPacket) children.get(1)).payload() == second, "Bundling must preserve ordering");
    }

    private static void registry() {
        var key = ResourceKey.<String>createRegistryKey(Identifier.fromNamespaceAndPath("veil_test", "values"));
        var registry = new MappedRegistry<String>(key, Lifecycle.stable());
        var provider = new FabricRegistrationFactory().create(registry, "veil_test");
        var entry = provider.register("value", () -> "kept");
        registry.freeze();
        check(entry.getId().equals(Identifier.fromNamespaceAndPath("veil_test", "value")), "Registry ID must retain namespace");
        check(entry.isPresent() && entry.get().equals("kept") && registry.getValue(entry.getId()) == entry.get(), "Registered value identity must survive wrappers");
        check(entry.asHolder().value() == entry.get(), "Registry holder must reference the registered value");
        check(provider.getEntries().contains(entry), "Provider must expose registered entries");
    }

    private static void resourceReload() {
        var files = new LinkedHashMap<Identifier, Resource>();
        files.put(Identifier.parse("veil_test:core/valid.json"), resource("42"));
        files.put(Identifier.parse("veil_test:core/invalid.json"), resource("\"wrong type\""));
        files.put(Identifier.parse("veil_test:other/ignored.json"), resource("7"));
        var loaded = new IntReload().read(new MemoryResources(files));
        check(loaded.size() == 1 && loaded.get(Identifier.parse("veil_test:valid")) == 42,
                "Reload must decode valid JSON, isolate invalid files and use converted resource IDs");
    }

    private static Resource resource(String json) {
        return new Resource(null, () -> new ByteArrayInputStream(json.getBytes(StandardCharsets.UTF_8)));
    }

    private static void colors() {
        var original = new Color(Color.RED);
        var halfway = original.lerp(Color.WHITE, 0.5F, new Color());
        check(halfway.red() == 1F && halfway.green() == 0.5F && halfway.blue() == 0.5F && halfway.alpha() == 1F,
                "Color interpolation must preserve all channels");
        check(original.argb() == 0xFFFF0000, "Interpolation into another color must not mutate its source");
        var parsed = Color.ARGB_INT_CODEC.parse(JsonOps.INSTANCE, new JsonPrimitive("#80ff00ff")).getOrThrow();
        check(parsed == 0x80FF00FF, "Color codec must preserve alpha and RGB components");
    }

    private static void poseShadowContract() throws Exception {
        int checked = 0;
        for (var method : PipelinePoseStackMixin.class.getDeclaredMethods()) {
            if (method.getAnnotation(Shadow.class) != null) {
                checked++;
                var target = PoseStack.class.getDeclaredMethod(method.getName().replaceFirst("^shadow\\$", ""), method.getParameterTypes());
                check(target.getReturnType() == method.getReturnType(), "PoseStack shadow method return type must match");
            }
        }
        for (var field : PipelinePoseStackMixin.class.getDeclaredFields()) {
            if (field.getAnnotation(Shadow.class) != null) {
                checked++;
                check(PoseStack.class.getDeclaredField(field.getName()).getType() == field.getType(), "PoseStack shadow field type must match");
            }
        }
        check(checked == 8, "Every expected PoseStack shadow must be inspected");
    }

    private static void check(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }

    private record TestPayload(String value) implements CustomPacketPayload {
        @Override public Type<TestPayload> type() { return new Type<>(Identifier.fromNamespaceAndPath("veil_test", value)); }
    }

    private static final class IntReload extends CodecReloadListener<Integer> {
        IntReload() { super(Codec.INT, FileToIdConverter.json("core")); }
        Map<Identifier, Integer> read(ResourceManager resources) { return prepare(resources, InactiveProfiler.INSTANCE); }
        @Override protected void apply(Map<Identifier, Integer> values, ResourceManager resources, ProfilerFiller profiler) { }
    }

    private record MemoryResources(Map<Identifier, Resource> files) implements ResourceManager {
        @Override public Set<String> getNamespaces() { return Set.of("veil_test"); }
        @Override public Optional<Resource> getResource(Identifier id) { return Optional.ofNullable(files.get(id)); }
        @Override public List<Resource> getResourceStack(Identifier id) { return getResource(id).stream().toList(); }
        @Override public Map<Identifier, Resource> listResources(String prefix, Selector selector) {
            var result = new LinkedHashMap<Identifier, Resource>();
            files.forEach((id, resource) -> { if (id.getPath().startsWith(prefix + "/") && selector.isIncluded(id)) result.put(id, resource); });
            return result;
        }
        @Override public Map<Identifier, List<Resource>> listResourceStacks(String prefix, Selector selector) {
            var result = new LinkedHashMap<Identifier, List<Resource>>();
            listResources(prefix, selector).forEach((id, value) -> result.put(id, List.of(value)));
            return result;
        }
        @Override public Stream<PackResources> listPacks() { return Stream.empty(); }
    }
}
