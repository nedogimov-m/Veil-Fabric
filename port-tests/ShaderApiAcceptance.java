import com.mojang.renderpearl.api.pipeline.ShaderType;
import foundry.veil.api.client.editor.InspectorState;
import foundry.veil.api.client.editor.SingleWindowInspector;
import foundry.veil.api.client.render.shader.ShaderPreDefinitions;
import foundry.veil.api.client.render.shader.processor.ShaderImporter;
import foundry.veil.api.client.render.shader.processor.ShaderPreProcessor;
import foundry.veil.api.client.render.shader.program.ProgramDefinition;
import foundry.veil.impl.client.render.shader.processor.ShaderProcessorList;
import foundry.veil.impl.client.render.shader.processor.NativeShaderProcessor;
import io.github.ocelot.glslprocessor.api.GlslParser;
import io.github.ocelot.glslprocessor.api.node.GlslTree;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.*;
import net.minecraft.server.packs.repository.PackSource;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceProvider;
import java.nio.file.Path;
import java.util.*;

/** Real GLSL AST/import semantics and actual native API signatures; never renders an ImGui window. */
public final class ShaderApiAcceptance {
    public static void main(String[] args) throws Exception {
        try (var pack = new PathPackResources(new PackLocationInfo("api-smoke", Component.literal("API test"), PackSource.BUILT_IN, Optional.empty()), Path.of(args[0]))) {
            ResourceProvider resources = id -> Optional.ofNullable(pack.getResource(PackType.CLIENT_RESOURCES, id)).map(stream -> new Resource(pack, stream));
            var processors = new ShaderProcessorList(resources);
            var trace = new ArrayList<String>();
            processors.addPreprocessor((ctx, tree) -> trace.add("last"), false);
            processors.addPreprocessorFirst((ctx, tree) -> trace.add("first"));
            var context = new Context(processors, Identifier.parse("veil:api_source"), true);
            processors.getProcessor().prepare(); processors.getProcessor().modify(context, GlslParser.parse("void main() {}"));
            check(trace.equals(List.of("first", "last")), "Default addPreprocessorFirst must preserve actual registration order");
            check(context.isVertex() && !context.isFragment() && context.stage() == ShaderType.VERTEX && context.name().equals(Identifier.parse("veil:api_source")), "Native context identity/stage must remain real 26.3 values");
            var include = Identifier.parse("veil:api_smoke");
            var source = GlslParser.parse("float apiValue() { return 0.75; } void main() {}");
            context.include(source, include, ShaderPreProcessor.IncludeOverloadStrategy.SOURCE);
            check(source.functions().filter(function -> function.getName().equals("apiValue")).count() == 1
                    && source.toSourceString().contains("0.75") && source.field("ApiValue").isPresent(), "SOURCE strategy must retain source overload and add nonconflicting imported fields");
            int size = source.getBody().size(); context.include(source, include, ShaderPreProcessor.IncludeOverloadStrategy.SOURCE);
            check(source.getBody().size() == size && processors.getShaderImporter().addedImports().contains(include), "Duplicate imports must be tracked and inserted once");
            processors.getShaderImporter().reset();
            try {
                context.include(GlslParser.parse("float apiValue() { return 0.75; }"), include, ShaderPreProcessor.IncludeOverloadStrategy.FAIL);
                throw new AssertionError("FAIL strategy accepted a conflicting real GLSL function");
            } catch (java.io.IOException expected) { }
            processors.getShaderImporter().reset();
            source = GlslParser.parse("float apiValue() { return 0.75; } void main() {}");
            context.include(source, include, ShaderPreProcessor.IncludeOverloadStrategy.INCLUDE);
            check(source.functions().filter(function -> function.getName().equals("apiValue")).count() == 1 && !source.toSourceString().contains("0.75"), "INCLUDE strategy must replace the source overload");
            processors.addPreprocessor((ctx, tree) -> {
                if (!ctx.isSourceFile() && ctx.name().equals(Identifier.parse("veil:api_loop")))
                    ctx.include(tree, ctx.name(), ShaderPreProcessor.IncludeOverloadStrategy.SOURCE);
            }, true);
            processors.getShaderImporter().reset();
            context.include(GlslParser.parse("void main() {}"), Identifier.parse("veil:api_loop"), ShaderPreProcessor.IncludeOverloadStrategy.SOURCE);
            check(processors.getShaderImporter().addedImports().size() == 1, "Recursive import must terminate through actual importer dependency tracking");
            var nativeImports = new ArrayList<Identifier>();
            var nativeProcessor = new NativeShaderProcessor(resources, new ShaderPreDefinitions(), null, Map.of(), new HashSet<>(), 0,
                    List.of((provider, registry) -> registry.addPreprocessor((ctx, tree) -> {
                        if (!ctx.isSourceFile()) nativeImports.add(ctx.name());
                    }, true)));
            String expanded = nativeProcessor.process(Identifier.parse("veil:native_include_test"), ShaderType.FRAGMENT, """
                    #version 330
                    #include <veil:native_order_first.glsl>
                    #if ORDER_FROM_INCLUDE != 7
                    #error Macro from an earlier include was lost
                    #endif
                    #if 0
                    #include <veil:missing_inactive.glsl>
                    #endif
                    #define NATIVE_REPEAT_NAME nativeRepeatA
                    #define NATIVE_REPEAT_VALUE 1.0
                    #include <veil:native_repeat.glsl>
                    #undef NATIVE_REPEAT_NAME
                    #undef NATIVE_REPEAT_VALUE
                    #define NATIVE_REPEAT_NAME nativeRepeatB
                    #define NATIVE_REPEAT_VALUE 2.0
                    #include <veil:native_repeat.glsl>
                    #include <veil:native_once.glsl>
                    #include <veil:native_once.glsl>
                    void main() { float value = NATIVE_SCALE(nativeSecond()); }
                    """);
            var expandedTree = GlslParser.parse(expanded);
            check(expanded.indexOf("float nativeFirst(") < expanded.indexOf("float nativeSecond("), "Native sibling and nested include declarations must retain lexical order");
            check(expandedTree.field("nativeRepeatA").isPresent() && expandedTree.field("nativeRepeatB").isPresent(), "Repeated native includes must use the macros active at each occurrence");
            check(expandedTree.functions().filter(function -> function.getName().equals("nativeOnce")).count() == 1, "Only explicit pragma once may suppress a repeated unguarded native include");
            check(nativeImports.contains(Identifier.parse("veil:native_order_first.glsl")) && nativeImports.contains(Identifier.parse("veil:native_order_second.glsl"))
                    && !nativeProcessor.includedResources().contains(Identifier.parse("veil:missing_inactive.glsl")), "Real import callbacks/dependencies must preserve active resource identities and skip inactive branches");
            check(!expanded.contains("NATIVE_SCALE("), "Function-like include macros must expand in the subsequent root source");
        }
        var inspector = new SingleWindowInspector() {
            @Override public Component getDisplayName() { return Component.literal("Native inspector API"); }
            @Override protected void renderComponents() { throw new AssertionError("This CPU API check must not claim UI rendering"); }
        };
        Object owner = new Object(); boolean[] visible = {true};
        check(!inspector.isOpen(), "Unregistered inspector must not be visible");
        InspectorState.attach(inspector, owner, () -> visible[0], () -> visible[0] = false);
        check(inspector.isOpen() && inspector.getDisplayName().getString().equals("Native inspector API"), "Current Component API and registered editor visibility must be live");
        visible[0] = false; check(!inspector.isOpen(), "Inspector visibility must reflect its actual manager");
        try { InspectorState.attach(inspector, new Object(), () -> true, () -> {}); throw new AssertionError("Two owners attached one inspector"); }
        catch (IllegalStateException expected) { }
        InspectorState.detach(inspector, owner); check(!inspector.isOpen(), "Removing the editor must detach visibility ownership");
        System.out.println("Shader/inspector CPU API acceptance passed: real AST include strategies, duplicate/recursive imports, processor order, native identifiers/stages and editor visibility ownership; no UI GPU claim.");
    }
    private static final class Context implements ShaderPreProcessor.VeilContext {
        private final ShaderProcessorList processors;
        private final Identifier name;
        private final boolean source;
        private final Map<String, Object> data;
        private final ShaderPreDefinitions definitions;
        Context(ShaderProcessorList processors, Identifier name, boolean source) { this(processors, name, source, new HashMap<>(), new ShaderPreDefinitions()); }
        Context(ShaderProcessorList processors, Identifier name, boolean source, Map<String, Object> data, ShaderPreDefinitions definitions) {
            this.processors = processors; this.name = name; this.source = source; this.data = data; this.definitions = definitions;
        }
        @Override public GlslTree modifyInclude(Identifier name, String source) throws java.io.IOException, io.github.ocelot.glslprocessor.api.GlslSyntaxException, io.github.ocelot.glslprocessor.lib.anarres.cpp.LexerException {
            var tree = GlslParser.preprocessParse(source, new HashMap<>());
            this.processors.getImportProcessor().modify(new Context(this.processors, name, false, this.data, this.definitions), tree); return tree;
        }
        @Override public Map<String, Object> customProgramData() { return this.data; }
        @Override public Identifier name() { return this.name; }
        @Override public boolean isSourceFile() { return this.source; }
        @Override public int activeBuffers() { return 0; }
        @Override public int type() { return org.lwjgl.opengl.GL20C.GL_VERTEX_SHADER; }
        @Override public ShaderImporter shaderImporter() { return this.processors.getShaderImporter(); }
        @Override public ShaderPreDefinitions preDefinitions() { return this.definitions; }
        @Override public Map<String, String> macros() { return Map.of(); }
        @Override public void addUniformBinding(String name, int binding) { this.data.put(name, binding); }
        @Override public void addDefinitionDependency(String name) { this.data.put("definition:" + name, true); }
        @Override public boolean isDynamic() { return false; }
        @Override public ProgramDefinition definition() { return null; }
    }
    private static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
