package foundry.veil.impl.client.render.dynamicbuffer;

import foundry.veil.api.client.render.dynamicbuffer.DynamicBufferType;
import foundry.veil.api.client.render.shader.processor.ShaderPreProcessor;
import io.github.ocelot.glslprocessor.api.GlslParser;
import io.github.ocelot.glslprocessor.api.node.GlslNode;
import io.github.ocelot.glslprocessor.api.node.GlslNodeList;
import io.github.ocelot.glslprocessor.api.node.GlslTree;
import io.github.ocelot.glslprocessor.api.node.branch.GlslReturnNode;
import io.github.ocelot.glslprocessor.api.node.function.GlslFunctionNode;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/** Runs after DynamicBufferProcessor, using the actual native OIT helper's fragment weights. */
public final class NativeDynamicOitProcessor implements ShaderPreProcessor {
    public static final String WEIGHT_PREFIX = "VeilDynamicOitWeights";
    private static final String PAYLOAD_WEIGHT = "VeilDynamicOitPayloadWeight";
    private static final String COVERAGE_WEIGHT = "VeilDynamicOitCoverageWeight";

    @Override public void modify(Context context, GlslTree tree) throws IOException {
        if (!context.isSourceFile() || !context.isFragment() || context.activeBuffers() == 0
                || !context.macros().containsKey("OIT_ACCUMULATE")) return;
        if (!(context instanceof MinecraftContext minecraft)) throw new IOException("Native OIT requires the actual Minecraft pipeline context");
        try {
            DynamicBufferType[] types = DynamicBufferType.decode(context.activeBuffers());
            GlslFunctionNode main = tree.mainFunction().orElseThrow();
            int base = minecraft.pipeline().getColorTargetStates().size();
            for (int group = 0; group < NativeDynamicOit.weightTargetCount(context.activeBuffers()); group++) {
                String name = WEIGHT_PREFIX + group;
                if (tree.field(name).isPresent()) throw new IOException("OIT generated output collision: " + name);
                tree.getBody().add(0, GlslParser.parseExpression("layout(location=" + (base + types.length + group) + ") out vec4 " + name));
                main.getBody().add(0, GlslParser.parseExpression(name + " = vec4(0.0)"));
            }
            List<Integer> produced = new ArrayList<>();
            for (int index = 0; index < types.length; index++) if (tree.field(types[index].getSourceName()).isPresent()) produced.add(index);
            if (produced.isEmpty()) { tree.markOutputs(); return; }
            if (tree.field(PAYLOAD_WEIGHT).isPresent() || tree.field(COVERAGE_WEIGHT).isPresent()) throw new IOException("OIT generated weight variable collision");
            tree.getBody().add(0, GlslParser.parseExpression("float " + PAYLOAD_WEIGHT + " = 0.0"));
            tree.getBody().add(0, GlslParser.parseExpression("float " + COVERAGE_WEIGHT + " = 0.0"));
            GlslFunctionNode sample = tree.functions().filter(function -> function.getName().equals("sampleColorForAccumulation"))
                    .findFirst().orElseThrow(() -> new IOException("Produced native OIT properties require sampleColorForAccumulation"));
            List<GlslNode> sampleReturns = sample.stream().filter(GlslReturnNode.class::isInstance).toList();
            if (sampleReturns.isEmpty()) throw new IOException("Native OIT helper has no accumulation return");
            for (GlslNode node : sampleReturns) {
                var block = tree.containingBlock(node).orElseThrow();
                block.body().add(block.index(), GlslParser.parseExpression(PAYLOAD_WEIGHT + " = color.a * sampledTransmittance"));
                block.body().add(block.index() + 1, GlslParser.parseExpression(COVERAGE_WEIGHT + " = accumAlpha * sampledTransmittance"));
            }
            // Cover early returns as well as the ordinary final main statement; discard never produces attachments.
            List<GlslNode> mainReturns = main.stream().filter(GlslReturnNode.class::isInstance).toList();
            for (GlslNode node : mainReturns) {
                var block = tree.containingBlock(node).orElseThrow();
                writeOutputs(block.body(), block.index(), types, produced);
            }
            writeOutputs(main.getBody(), main.getBody().size(), types, produced);
            tree.markOutputs();
        } catch (IOException failure) { throw failure; }
        catch (Exception failure) { throw new IOException("Cannot adapt actual native OIT property weights in " + context.name(), failure); }
    }
    private static void writeOutputs(GlslNodeList body, int insertion, DynamicBufferType[] types, List<Integer> produced) throws Exception {
        int index = insertion;
        for (int property : produced) {
            body.add(index++, GlslParser.parseExpression(types[property].getSourceName() + " *= " + PAYLOAD_WEIGHT));
            body.add(index++, GlslParser.parseExpression(WEIGHT_PREFIX + (property / 4) + "." + "xyzw".charAt(property % 4) + " = " + COVERAGE_WEIGHT));
        }
    }
}
