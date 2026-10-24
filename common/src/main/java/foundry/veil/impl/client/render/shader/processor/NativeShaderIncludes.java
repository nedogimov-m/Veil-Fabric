package foundry.veil.impl.client.render.shader.processor;

import foundry.veil.impl.client.render.shader.ShaderImporterImpl;
import io.github.ocelot.glslprocessor.api.GlslParser;
import io.github.ocelot.glslprocessor.api.GlslSyntaxException;
import io.github.ocelot.glslprocessor.api.node.GlslTree;
import io.github.ocelot.glslprocessor.lib.anarres.cpp.*;
import net.minecraft.resources.Identifier;

import java.io.IOException;
import java.util.*;
import java.util.regex.Pattern;

/** Uses the genuine C preprocessor's source stack so include macros affect subsequent source. */
final class NativeShaderIncludes extends Preprocessor {
    private static final Pattern DIRECTIVE = Pattern.compile("^\\h*#\\h*(\\w+(?::\\w+)?)\\h*(.*)$");
    private static final Pattern VERSION = Pattern.compile("(?m)^\\h*#\\h*version\\h+(\\d+)(?:\\h+(\\w+))?");
    private final ShaderImporterImpl importer;
    private final List<Import> imports = new ArrayList<>();
    private final List<Region> regions = new ArrayList<>();
    private final List<String> directives = new ArrayList<>();
    private final Set<Identifier> once = new HashSet<>();
    private final String marker = "__veil_" + UUID.randomUUID().toString().replace("-", "") + "_";

    NativeShaderIncludes(ShaderImporterImpl importer) { this.importer = importer; this.addFeature(Feature.KEEPCOMMENTS); }

    String preprocess(String source, Map<String, String> macros, ImportProcessor processor) throws IOException, LexerException, GlslSyntaxException {
        for (var entry : macros.entrySet()) this.addMacro(entry.getKey(), entry.getValue());
        var version = VERSION.matcher(source);
        this.addMacro("__VERSION__", version.find() ? version.group(1) : "110");
        this.addMacro("GL_core_profile", "1");
        this.addInput(new IncludeSource(this.protect(source), null, 0));
        StringBuilder output = new StringBuilder();
        for (Token token; (token = this.token()).getType() != Token.EOF;) output.append(token.getText());
        macros.clear(); macros.putAll(this.snapshot());
        return this.modifyImports(this.restore(output.toString()), processor);
    }

    private Map<String, String> snapshot() {
        Map<String, String> result = new HashMap<>();
        this.getMacros().forEach((name, macro) -> {
            if (!macro.isFunctionLike() && !Set.of("__LINE__", "__FILE__", "__COUNTER__").contains(name)) result.put(name, macro.getText());
        });
        return result;
    }

    /** Only active pragmas reach this hook, after the genuine preprocessor consumes their line. */
    @Override protected void pragma(Token token) throws LexerException {
        String name = token.getText();
        if (name.startsWith("veil_native_import_")) {
            Import request = this.imports.get(Integer.parseInt(name.substring("veil_native_import_".length())));
            if (this.once.contains(request.name)) return;
            IncludeSource parent = (IncludeSource) this.getSource();
            if (parent.depth >= 128) throw new LexerException("Native shader include depth exceeds 128 at " + request.name);
            try {
                String raw = this.importer.loadSource(request.name);
                int index = this.regions.size();
                this.regions.add(new Region(request.name));
                String included = this.begin(index) + "\n" + this.protect(raw) + "\n#pragma veil_native_end_" + index + "\n" + this.end(index) + "\n";
                this.push_source(new IncludeSource(included, request.name, parent.depth + 1), true);
            } catch (IOException failure) { throw new LexerException("Cannot include " + request.name + ": " + failure.getMessage()); }
        } else if (name.startsWith("veil_native_end_")) {
            this.regions.get(Integer.parseInt(name.substring("veil_native_end_".length()))).macros = this.snapshot();
        } else if (name.equals("veil_native_once")) {
            Identifier id = ((IncludeSource) this.getSource()).id;
            if (id != null) this.once.add(id);
        } else super.pragma(token);
    }

    /** Protect GLSL directives from the C lexer while retaining their conditional location. */
    private String protect(String source) {
        StringBuilder result = new StringBuilder();
        boolean blockComment = false;
        for (String line : source.split("\\R", -1)) {
            var directive = DIRECTIVE.matcher(line);
            if (!blockComment && directive.matches()) {
                String key = directive.group(1), value = directive.group(2).trim();
                if (key.equals("include") || key.equals("moj_import")) {
                    String id;
                    if (value.startsWith("<") || value.startsWith("\"") || value.startsWith("'")) {
                        char end = value.charAt(0) == '<' ? '>' : value.charAt(0);
                        int close = value.indexOf(end, 1);
                        if (close < 0) throw new IllegalArgumentException("Unterminated native shader include: " + value);
                        id = value.substring(1, close);
                    } else id = value.split("\\h+", 2)[0];
                    int index = this.imports.size();
                    this.imports.add(new Import(Identifier.parse(id)));
                    result.append("#pragma veil_native_import_").append(index).append('\n');
                    continue;
                }
                if (key.equals("pragma") && value.equals("once")) {
                    result.append("#pragma veil_native_once\n"); continue;
                }
                if (Set.of("version", "extension", "line", "pragma").contains(key) || key.startsWith("veil:")) {
                    int index = this.directives.size(); this.directives.add(line);
                    result.append("/*").append(this.marker).append("directive_").append(index).append("__*/\n"); continue;
                }
            }
            result.append(line).append('\n');
            // A directive-looking line inside a comment is ordinary source, never an import.
            boolean quoted = false;
            for (int i = 0; i + 1 < line.length(); i++) {
                char c = line.charAt(i), next = line.charAt(i + 1);
                if (blockComment) { if (c == '*' && next == '/') { blockComment = false; i++; } }
                else if (c == '"' && (i == 0 || line.charAt(i - 1) != '\\')) quoted = !quoted;
                else if (!quoted && c == '/' && next == '/') break;
                else if (!quoted && c == '/' && next == '*') { blockComment = true; i++; }
            }
        }
        return result.toString();
    }

    private String restore(String source) {
        for (int index = 0; index < this.directives.size(); index++) source = source.replace("/*" + this.marker + "directive_" + index + "__*/", this.directives.get(index));
        return source;
    }
    private String begin(int index) { return "/*" + this.marker + "begin_" + index + "__*/"; }
    private String end(int index) { return "/*" + this.marker + "end_" + index + "__*/"; }
    private String modifyImports(String source, ImportProcessor processor) throws IOException, LexerException, GlslSyntaxException {
        String prefix = "/*" + this.marker + "begin_";
        StringBuilder result = new StringBuilder();
        int position = 0, start;
        while ((start = source.indexOf(prefix, position)) >= 0) {
            int markerEnd = source.indexOf("__*/", start) + 4;
            int index = Integer.parseInt(source.substring(start + prefix.length(), markerEnd - 4));
            int end = source.indexOf(this.end(index), markerEnd);
            if (end < 0) throw new IOException("Unbalanced native shader include markers");
            Region region = this.regions.get(index);
            String expanded = this.modifyImports(source.substring(markerEnd, end), processor);
            GlslTree tree = GlslParser.parse(expanded);
            processor.modify(region.name, region.macros, tree);
            result.append(source, position, start).append(NativeGlslTreeWriter.source(tree).replaceFirst("(?m)^#version[^\\r\\n]*(?:\\R|$)", ""));
            position = end + this.end(index).length();
        }
        return result.append(source, position, source.length()).toString();
    }
    @FunctionalInterface interface ImportProcessor {
        void modify(Identifier id, Map<String, String> macros, GlslTree tree) throws IOException, LexerException, GlslSyntaxException;
    }
    private record Import(Identifier name) { }
    private static final class Region {
        final Identifier name; Map<String, String> macros = Map.of();
        Region(Identifier name) { this.name = name; }
    }
    private static final class IncludeSource extends StringLexerSource {
        final Identifier id; final int depth;
        IncludeSource(String source, Identifier id, int depth) { super(source, true); this.id = id; this.depth = depth; }
        @Override public String getName() { return this.id == null ? "native shader" : this.id.toString(); }
    }
}
