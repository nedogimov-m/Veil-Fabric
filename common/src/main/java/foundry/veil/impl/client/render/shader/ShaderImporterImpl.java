package foundry.veil.impl.client.render.shader;

import foundry.veil.api.client.render.shader.processor.ShaderImporter;
import foundry.veil.api.client.render.shader.processor.ShaderPreProcessor;
import io.github.ocelot.glslprocessor.api.node.GlslTree;
import it.unimi.dsi.fastutil.objects.ObjectArraySet;
import it.unimi.dsi.fastutil.objects.ObjectSet;
import it.unimi.dsi.fastutil.objects.ObjectSets;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.FileToIdConverter;
import net.minecraft.server.packs.resources.ResourceProvider;
import org.apache.commons.io.IOUtils;

import java.io.IOException;
import java.io.Reader;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;

public class ShaderImporterImpl implements ShaderImporter {

    private static final String DEPRECATED_MARKER = "#veil:deprecated";
    private static final FileToIdConverter INCLUDES = new FileToIdConverter("pinwheel/shaders/include", ".glsl");
    private static final org.slf4j.Logger LOGGER = org.slf4j.LoggerFactory.getLogger("Veil");

    private final ResourceProvider resourceProvider;
    private final ObjectSet<Identifier> failedImports;
    private final ObjectSet<Identifier> addedImports;
    private final ObjectSet<Identifier> addedImportsView;
    private final Map<Identifier, String> imports;

    /**
     * Creates a new import processor that loads import files from the specified resource provider.
     *
     * @param resourceProvider The provider for import resources
     */
    public ShaderImporterImpl(ResourceProvider resourceProvider) {
        this.resourceProvider = resourceProvider;
        this.failedImports = new ObjectArraySet<>();
        this.addedImports = new ObjectArraySet<>();
        this.addedImportsView = ObjectSets.unmodifiable(this.addedImports);
        this.imports = new HashMap<>();
    }

    public void reset() {
        this.addedImports.clear();
    }

    /** Load an active lexical include without imposing include-once semantics on native GLSL. */
    public String loadSource(Identifier name) throws IOException {
        String cached = this.imports.get(name);
        if (cached == null) {
            String path = name.getPath();
            Identifier file = path.endsWith(".glsl")
                    ? Identifier.fromNamespaceAndPath(name.getNamespace(), "pinwheel/shaders/include/" + path) : INCLUDES.idToFile(name);
            if (this.resourceProvider.getResource(file).isEmpty()) {
                file = Identifier.fromNamespaceAndPath(name.getNamespace(), "shaders/include/" + path + (path.endsWith(".glsl") ? "" : ".glsl"));
            }
            try (Reader reader = this.resourceProvider.openAsReader(file)) {
                cached = IOUtils.toString(reader);
                this.imports.put(name, cached);
            }
        }
        this.addedImports.add(name);
        return cached;
    }

    @Override
    public GlslTree loadImport(ShaderPreProcessor.Context context, Identifier name, boolean force) throws IOException {
        if (this.failedImports.contains(name)) {
            throw new IOException("Import previously failed to load");
        }

        if (this.addedImports.contains(name) && !force) {
            return new GlslTree();
        }

        try {
            // Mark before processing so recursive/duplicate includes cannot insert the same source repeatedly.
            String source = this.loadSource(name);
            // TODO add a way to safely clone a glsl tree
            GlslTree tree = context.modifyInclude(name, source);
            Iterator<String> iterator = tree.getDirectives().iterator();
            while (iterator.hasNext()) {
                String directive = iterator.next();
                if (!directive.startsWith(DEPRECATED_MARKER)) {
                    continue;
                }

                iterator.remove();
                String message = directive.substring(DEPRECATED_MARKER.length()).trim();
                if (message.isEmpty()) {
                    LOGGER.warn("Program '{}' uses deprecated import in {} shader '{}'", context.name(), context.typeName(), name);
                } else {
                    LOGGER.warn("Program '{}' uses deprecated import in {} shader '{}': {}", context.name(), context.typeName(), name, message);
                }
            }
            return tree;
        } catch (Throwable t) {
            this.failedImports.add(name);
            throw new IOException("Failed to add import: " + name, t);
        }
    }

    @Override
    public Set<Identifier> addedImports() {
        return this.addedImportsView;
    }
}
