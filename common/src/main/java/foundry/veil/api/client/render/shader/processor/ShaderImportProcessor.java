package foundry.veil.api.client.render.shader.processor;

import io.github.ocelot.glslprocessor.api.GlslSyntaxException;
import io.github.ocelot.glslprocessor.api.node.GlslTree;
import io.github.ocelot.glslprocessor.lib.anarres.cpp.LexerException;
import net.minecraft.IdentifierException;
import net.minecraft.resources.Identifier;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Processes a shader to add imports.
 *
 * @author Ocelot
 */
public class ShaderImportProcessor implements ShaderPreProcessor {

    private static final String INCLUDE_KEY = "#include ";
    private static final String NATIVE_INCLUDE_KEY = "#moj_import ";

    public static String sanitizeLocation(String location) {
        if ((location.startsWith("\"") && location.endsWith("\""))
                || (location.startsWith("'") && location.endsWith("'"))
                || (location.startsWith("<") && location.endsWith(">"))) {
            return location.substring(1, location.length() - 1);
        }
        return location;
    }

    @Override
    public void modify(Context ctx, GlslTree tree) throws IOException, GlslSyntaxException, LexerException {
        List<String> imports = new ArrayList<>();
        List<String> directives = tree.getDirectives();
        for (String directive : directives) {
            if (directive.startsWith(INCLUDE_KEY) || directive.startsWith(NATIVE_INCLUDE_KEY)) {
                imports.add(directive);
            }
        }
        for (String directive : imports) {
            String importId = sanitizeLocation(directive.substring(directive.startsWith(INCLUDE_KEY) ? INCLUDE_KEY.length() : NATIVE_INCLUDE_KEY.length()).trim());

            try {
                ctx.include(tree, Identifier.parse(importId), IncludeOverloadStrategy.SOURCE);
            } catch (IdentifierException e) {
                throw new IOException("Invalid import: " + importId, e);
            }
        }
        directives.removeAll(imports);
    }
}
