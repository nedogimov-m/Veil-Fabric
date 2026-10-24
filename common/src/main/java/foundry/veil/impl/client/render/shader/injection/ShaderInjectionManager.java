package foundry.veil.impl.client.render.shader.injection;

import foundry.veil.impl.client.render.shader.injection.util.ShaderInjection;
import foundry.veil.impl.client.render.shader.injection.util.ShaderInjectionDefinition;
import io.github.ocelot.glslprocessor.api.node.GlslTree;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.SimplePreparableReloadListener;
import net.minecraft.util.profiling.ProfilerFiller;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.*;
import java.util.regex.Pattern;

/**
 * Manages modifications for both vanilla and Veil shader files.
 *
 * @author Ocelot, Vowxky
 */
@ApiStatus.Internal
public class ShaderInjectionManager extends SimplePreparableReloadListener<ShaderInjectionManager.Preparations> {

    private static final org.slf4j.Logger LOGGER = org.slf4j.LoggerFactory.getLogger("Veil");

    private static final Pattern SHADER_EXTENSION = Pattern.compile("\\.(fsh|vsh|tcsh|tesh|gsh|comp)$");

    private volatile Snapshot snapshot = new Snapshot(Map.of(), Map.of(), Map.of());

    /** A native compilation retains one exact resource-reload generation, including its injection order. */
    public Snapshot snapshot() { return this.snapshot; }

    /**
     * Applies all registered shader modifications to the specified GLSL tree.
     */
    public void applyModifiers(Identifier shaderId, GlslTree tree, boolean applyVersion) {
        Collection<ShaderInjection> modifiers = this.getModifiers(shaderId);
        if (modifiers.isEmpty()) return;
        try {
            for (ShaderInjection modifier : modifiers) {
                modifier.inject(tree, applyVersion);
            }
        } catch (Exception e) {
            LOGGER.error("Failed to transform shader: {}", shaderId, e);
        }
    }

    /**
     * Fetches all registered modifications for the specified shader.
     */
    public List<ShaderInjection> getModifiers(Identifier shaderId) {
        return this.snapshot.getModifiers(shaderId);
    }

    /**
     * Returns the resource ID under which the given modification was registered, or null.
     */
    @Nullable
    public Identifier getModifierId(ShaderInjection modification) {
        return this.snapshot.names.get(modification);
    }

    /**
     * Returns the replacement shader ID for the target, or null if none.
     */
    @Nullable
    public Identifier getReplacement(Identifier target) {
        return this.snapshot.getReplacement(target);
    }

    /**
     * Strips the shader extension (.fsh, .vsh, etc.) from a path, or null if none.
     */
    @Nullable
    private static Identifier stripShaderExtension(Identifier location) {
        String path = location.getPath();
        String stripped = SHADER_EXTENSION.matcher(path).replaceFirst("");
        return !stripped.equals(path) ? Identifier.fromNamespaceAndPath(location.getNamespace(), stripped) : null;
    }

    /**
     * Loads and adapts all shader injection JSON definitions from resource packs.
     */
    @Override
    protected @NotNull Preparations prepare(@NotNull ResourceManager resourceManager, @NotNull ProfilerFiller profilerFiller) {
        Map<Identifier, List<ShaderInjection>> modifiers = new HashMap<>();
        Map<ShaderInjection, Identifier> names = new HashMap<>();
        Map<Identifier, Identifier> replacements = new HashMap<>();
        Map<ShaderInjectionLoader.LoadedPatch, List<ShaderInjection>> adapted = new IdentityHashMap<>();

        ShaderInjectionLoader.LoadedPatches loaded = ShaderInjectionLoader.load(resourceManager);
        for (Map.Entry<Identifier, List<ShaderInjectionLoader.LoadedPatch>> entry : loaded.byTarget().entrySet()) {
            Identifier target = entry.getKey();
            List<ShaderInjectionLoader.LoadedPatch> patches = entry.getValue();

            List<ShaderInjection> mods = modifiers.computeIfAbsent(target, k -> new LinkedList<>());
            for (ShaderInjectionLoader.LoadedPatch patch : patches) {
                ShaderInjectionDefinition definition = patch.definition();
                if (definition.replace() != null) {
                    Identifier replaceTarget = stripShaderExtension(target);
                    if (replaceTarget == null) {
                        replaceTarget = target;
                    }
                    replacements.put(replaceTarget, definition.replace());
                    if (definition.debug()) {
                        LOGGER.info("\n═══ Replace: {} ═══\n  Target:   {}\n  Replace:  {}\n  Source:   {}",
                                replaceTarget,
                                target,
                                definition.replace(),
                                patch.resourceLocation());
                    }
                    continue;
                }

                try {
                    List<ShaderInjection> injections = adapted.get(patch);
                    if (injections == null) {
                        injections = ShaderInjectionAdapter.toModifications(definition, resourceManager);
                        adapted.put(patch, injections);
                    }
                    for (ShaderInjection m : injections) {
                        mods.add(m);
                        names.put(m, definition.id() != null ? definition.id() : patch.resourceLocation());
                    }
                } catch (Throwable t) {
                    LOGGER.error("Couldn't adapt shader injection {}", patch.resourceLocation(), t);
                }
            }
        }

        return new Preparations(modifiers, names, replacements);
    }

    /**
     * Applies the loaded preparations to the live injection and replacement maps.
     */
    @Override
    protected void apply(@NotNull Preparations preparations, @NotNull ResourceManager resourceManager, @NotNull ProfilerFiller profilerFiller) {
        this.snapshot = new Snapshot(preparations.shaders, preparations.names, preparations.replacements);
        LOGGER.info("Loaded {} shader redirects, {} replacements", this.snapshot.names.size(), this.snapshot.replacements.size());
        if (!this.snapshot.replacements.isEmpty()) {
            LOGGER.debug("Active shader replacements:");
            this.snapshot.replacements.forEach((target, replacement) -> LOGGER.debug("  {} -> {}", target, replacement));
        }
    }

    @ApiStatus.Internal
    public record Preparations(Map<Identifier, List<ShaderInjection>> shaders,
                               Map<ShaderInjection, Identifier> names,
                               Map<Identifier, Identifier> replacements) {
    }

    public record Snapshot(Map<Identifier, List<ShaderInjection>> shaders,
                           Map<ShaderInjection, Identifier> names,
                           Map<Identifier, Identifier> replacements) {
        public Snapshot {
            var copied = new LinkedHashMap<Identifier, List<ShaderInjection>>();
            shaders.forEach((id, values) -> copied.put(id, List.copyOf(values)));
            shaders = Collections.unmodifiableMap(copied); names = Map.copyOf(names); replacements = Map.copyOf(replacements);
        }
        public List<ShaderInjection> getModifiers(Identifier shaderId) { return this.shaders.getOrDefault(shaderId, List.of()); }
        public @Nullable Identifier getReplacement(Identifier target) {
            Identifier stripped = stripShaderExtension(target);
            return this.replacements.get(stripped != null ? stripped : target);
        }
    }
}
