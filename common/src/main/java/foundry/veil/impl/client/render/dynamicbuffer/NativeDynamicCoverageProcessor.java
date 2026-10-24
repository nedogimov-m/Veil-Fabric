package foundry.veil.impl.client.render.dynamicbuffer;

import foundry.veil.api.client.render.dynamicbuffer.DynamicBufferType;
import foundry.veil.api.client.render.shader.processor.ShaderPreProcessor;
import io.github.ocelot.glslprocessor.api.GlslParser;
import io.github.ocelot.glslprocessor.api.node.GlslNode;
import io.github.ocelot.glslprocessor.api.node.GlslNodeList;
import io.github.ocelot.glslprocessor.api.node.GlslTree;
import io.github.ocelot.glslprocessor.api.node.branch.GlslReturnNode;
import io.github.ocelot.glslprocessor.api.node.variable.GlslNewFieldNode;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/** Preserve generic property W in packed RGB channels while native source alpha remains geometry coverage. */
public final class NativeDynamicCoverageProcessor implements ShaderPreProcessor {
    public static final String PACKED_PREFIX = "VeilDynamicPropertyW";
    @Override public void modify(Context context, GlslTree tree) throws IOException {
        if (!context.isSourceFile() || !context.isFragment() || context.activeBuffers() == 0) return;
        if (!(context instanceof MinecraftContext nativeContext)) throw new IOException("Native property coverage requires the actual pipeline context");
        try {
            var types = DynamicBufferType.decode(context.activeBuffers());
            int base = nativeContext.pipeline().getColorTargetStates().size();
            GlslNewFieldNode primary = tree.fields().filter(field -> !field.getName().startsWith("VeilDynamic"))
                    .filter(field -> field.toSourceString().matches("(?s).*\\blayout\\s*\\([^)]*\\blocation\\s*=\\s*0\\b[^)]*\\).*\\bout\\s+vec4\\b.*"))
                    .findFirst().orElseThrow(() -> new IOException("Native property coverage requires a vec4 color output at location zero"));
            var main = tree.mainFunction().orElseThrow();
            for (int group = 0; group < NativeDynamicCoverage.packedTargetCount(context.activeBuffers()); group++) {
                String name = PACKED_PREFIX + group;
                if (tree.field(name).isPresent()) throw new IOException("Generated native property W output collision: " + name);
                tree.getBody().add(0, GlslParser.parseExpression("layout(location=" + (base + types.length + group) + ") out vec4 " + name));
                main.getBody().add(0, GlslParser.parseExpression(name + " = vec4(0.0)"));
            }
            List<Integer> produced = new ArrayList<>();
            for (int index = 0; index < types.length; index++) {
                if (tree.field(types[index].getSourceName()).isPresent()) produced.add(index);
                else {
                    String name = types[index].getSourceName();
                    tree.getBody().add(0, GlslParser.parseExpression("layout(location=" + (base + index) + ") out vec4 " + name));
                    main.getBody().add(0, GlslParser.parseExpression(name + " = vec4(0.0)"));
                }
            }
            for (GlslNode node : main.stream().filter(GlslReturnNode.class::isInstance).toList()) {
                var block = tree.containingBlock(node).orElseThrow();
                write(block.body(), block.index(), types, produced, primary.getName());
            }
            write(main.getBody(), main.getBody().size(), types, produced, primary.getName());
            tree.markOutputs();
        } catch (IOException failure) { throw failure; }
        catch (Exception failure) { throw new IOException("Cannot preserve native property W in " + context.name(), failure); }
    }
    private static void write(GlslNodeList body, int index, DynamicBufferType[] types, List<Integer> produced, String primary) throws Exception {
        for (int property : produced) {
            body.add(index++, GlslParser.parseExpression(PACKED_PREFIX + (property / 3) + "." + "xyz".charAt(property % 3) + " = " + types[property].getSourceName() + ".w"));
        }
        for (var type : types) body.add(index++, GlslParser.parseExpression(type.getSourceName() + ".w = " + primary + ".a"));
        for (int group = 0; group < (types.length + 2) / 3; group++)
            body.add(index++, GlslParser.parseExpression(PACKED_PREFIX + group + ".a = " + primary + ".a"));
    }
}
