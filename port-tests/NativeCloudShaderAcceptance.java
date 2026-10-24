import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import com.mojang.renderpearl.api.pipeline.ShaderType;
import foundry.veil.api.client.render.shader.ShaderPreDefinitions;
import foundry.veil.impl.client.render.shader.processor.NativeGlslNodeWriter;
import foundry.veil.impl.client.render.shader.processor.NativeShaderProcessor;
import io.github.ocelot.glslprocessor.api.GlslParser;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.*;
import net.minecraft.server.packs.repository.PackSource;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceProvider;
import java.nio.file.*;
import java.util.*;
import static org.lwjgl.util.shaderc.Shaderc.*;

/** Real clouds/flat/OIT sources compile with native shaderc on CPU; no game, device or GPU launch. */
public final class NativeCloudShaderAcceptance {
    public static void main(String[] args) throws Exception {
        for (String expression : List.of("vertex((direction * 4) + (isInsideFace ? 3 - quadVertex : quadVertex))",
                "(a ? b : c) * d", "(a ? b : c) ? d : e", "a == (b == c)", "(a < b) == (c < d)")) {
            var original = GlslParser.parseExpression(expression);
            String source = NativeGlslNodeWriter.expression(original);
            if (!original.equals(GlslParser.parseExpression(source))) throw new AssertionError("Expression precedence changed: " + expression + " -> " + source);
        }
        Path resourcesPath = Path.of(args[0]), output = Path.of(args[1]);
        try (var pack = new PathPackResources(new PackLocationInfo("actual-cloud-shaders", Component.literal("Actual clouds shaders"), PackSource.BUILT_IN, Optional.empty()), resourcesPath)) {
            ResourceProvider resources = id -> Optional.ofNullable(pack.getResource(PackType.CLIENT_RESOURCES, id)).map(stream -> new Resource(pack, stream));
            List<RenderPipeline> pipelines = List.of(RenderPipelines.CLOUDS, RenderPipelines.FLAT_CLOUDS,
                    RenderPipelines.OIT_CLOUDS.depthBoundsPipeline(), RenderPipelines.OIT_CLOUDS.transmittancePipeline(), RenderPipelines.OIT_CLOUDS.accumulatePipeline(),
                    RenderPipelines.OIT_FLAT_CLOUDS.depthBoundsPipeline(), RenderPipelines.OIT_FLAT_CLOUDS.transmittancePipeline(), RenderPipelines.OIT_FLAT_CLOUDS.accumulatePipeline());
            long compiler = shaderc_compiler_initialize(), options = shaderc_compile_options_initialize();
            try {
                shaderc_compile_options_set_target_env(options, shaderc_target_env_vulkan, shaderc_env_version_vulkan_1_3);
                shaderc_compile_options_set_auto_bind_uniforms(options, true);
                shaderc_compile_options_set_auto_map_locations(options, true);
                int stages = 0;
                for (var pipeline : pipelines) {
                    var macros = new HashMap<>(pipeline.getShaderDefines().values());
                    pipeline.getShaderDefines().flags().forEach(flag -> macros.put(flag, ""));
                    macros.put("RENDERPEARL_DEPTH_IS_ZERO_TO_ONE", "");
                    var processor = new NativeShaderProcessor(resources, new ShaderPreDefinitions(), null, macros, new HashSet<>(), 0, List.of()).forVariant(pipeline, 0, List.of());
                    for (var entry : pipeline.getShaders().entrySet()) {
                        ShaderType stage = entry.getKey(); Identifier id = entry.getValue();
                        String raw = Files.readString(resourcesPath.resolve("assets/" + id.getNamespace() + "/shaders/" + id.getPath() + (stage == ShaderType.VERTEX ? ".vsh" : ".fsh")));
                        String processed = processor.process(id, stage, raw);
                        Files.writeString(output.resolve(pipeline.getLocation().getPath().replace('/', '_') + (stage == ShaderType.VERTEX ? ".vsh" : ".fsh")), processed);
                        long result = shaderc_compile_into_spv(compiler, processed, stage == ShaderType.VERTEX ? shaderc_glsl_vertex_shader : shaderc_glsl_fragment_shader, id.toString(), "main", options);
                        try {
                            if (shaderc_result_get_compilation_status(result) != shaderc_compilation_status_success)
                                throw new AssertionError(pipeline.getLocation() + " " + stage + ": " + shaderc_result_get_error_message(result));
                        } finally { shaderc_result_release(result); }
                        stages++;
                    }
                }
                System.out.println("PASS five generic AST precedence round trips and " + stages + " actual native shaderc stages across all eight genuine clouds/flat/OIT pipelines");
            } finally { shaderc_compile_options_release(options); shaderc_compiler_release(compiler); }
        }
    }
}
