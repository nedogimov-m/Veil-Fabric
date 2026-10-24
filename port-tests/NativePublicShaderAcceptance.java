import com.google.gson.JsonParser;
import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.pipeline.*;
import com.mojang.renderpearl.api.vertex.VertexFormat;
import foundry.veil.api.client.render.gpu.GpuShaderSource;
import foundry.veil.api.client.render.shader.ShaderFeature;
import foundry.veil.api.client.render.shader.ShaderPreDefinitions;
import foundry.veil.api.client.render.shader.program.ProgramDefinition;
import foundry.veil.impl.client.render.shader.processor.NativeShaderProcessor;
import it.unimi.dsi.fastutil.ints.Int2ObjectArrayMap;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.*;
import net.minecraft.server.packs.repository.PackSource;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceProvider;

import java.nio.file.*;
import java.util.*;

import static org.lwjgl.util.shaderc.Shaderc.*;

/** Real public Veil programs through managed stage-pair packing and native CPU shaderc; no GPU. */
public final class NativePublicShaderAcceptance {
    private static final List<String> PROGRAMS = List.of("quasar/particle_additive", "blit_screen", "blit_depth",
            "core/composite", "core/blit_screen_effect", "debug/array", "debug/cubemap");
    private static final VertexFormat PARTICLE = VertexFormat.builder(0)
            .addAttribute("Position", GpuFormat.RGB32_FLOAT).addAttribute("UV0", GpuFormat.RG32_FLOAT)
            .addAttribute("Color", GpuFormat.RGBA8_UNORM).addAttribute("UV2", GpuFormat.RG16_SINT).build();

    public static void main(String[] args) throws Exception {
        Path veil = Path.of(args[0]), minecraft = Path.of(args[1]), output = Path.of(args[2]);
        Set<Path> inputs = new TreeSet<>();
        try (var veilPack = pack("actual-veil-shaders", veil); var minecraftPack = pack("actual-minecraft-shaders", minecraft)) {
            ResourceProvider resources = id -> {
                for (var entry : List.of(Map.entry(veilPack, veil), Map.entry(minecraftPack, minecraft))) {
                    var stream = entry.getKey().getResource(PackType.CLIENT_RESOURCES, id);
                    if (stream != null) {
                        inputs.add(entry.getValue().resolve("assets/" + id.getNamespace() + "/" + id.getPath()));
                        return Optional.of(new Resource(entry.getKey(), stream));
                    }
                }
                return Optional.empty();
            };
            long compiler = shaderc_compiler_initialize(), options = shaderc_compile_options_initialize();
            try {
                shaderc_compile_options_set_target_env(options, shaderc_target_env_vulkan, shaderc_env_version_vulkan_1_3);
                shaderc_compile_options_set_auto_bind_uniforms(options, true);
                shaderc_compile_options_set_auto_map_locations(options, true);
                int stages = 0;
                for (String name : PROGRAMS) {
                    Path jsonPath = programPath(veil, name, ".json"); inputs.add(jsonPath);
                    var json = JsonParser.parseString(Files.readString(jsonPath)).getAsJsonObject();
                    var vertex = Identifier.parse(json.get("vertex").getAsString());
                    var fragment = Identifier.parse(json.get("fragment").getAsString());
                    var definition = new ProgramDefinition(vertex, null, null, null, fragment, null,
                            new String[0], Map.of(), Map.of(), Set.of(), new Int2ObjectArrayMap<>(), new ShaderFeature[0], null);
                    var builder = RenderPipeline.builder().withLocation(Identifier.parse("veil_test:public/" + name))
                            .withVertexShader(vertex).withFragmentShader(fragment)
                            .withPrimitiveTopology(PrimitiveTopology.TRIANGLES).withCull(false);
                    if (name.equals("quasar/particle_additive")) {
                        var bindings = BindGroupLayout.builder();
                        for (String block : List.of("DynamicTransforms", "Projection", "Fog")) bindings.withUniform(block, UniformType.UNIFORM_BUFFER);
                        for (String sampler : List.of("Sampler0", "Sampler2")) bindings.withUniform(sampler, UniformType.COMBINED_IMAGE_SAMPLER);
                        builder.withVertexBinding(0, PARTICLE).withBindGroupLayout(bindings.build());
                    }
                    builder.withColorTargetState(new ColorTargetState(name.equals("quasar/particle_additive")
                            ? Optional.of(new BlendFunction(BlendFactor.ONE, BlendFactor.ONE)) : Optional.empty(),
                            GpuFormat.RGBA16_FLOAT, ColorTargetState.WRITE_ALL));
                    var pipeline = builder.build();
                    var processor = new NativeShaderProcessor(resources, new ShaderPreDefinitions(), definition,
                            Map.of("RENDERPEARL_DEPTH_IS_ZERO_TO_ONE", ""), new HashSet<>(), 0, List.of()).forVariant(pipeline, 0, List.of());
                    Map<ShaderType, String> raw = new EnumMap<>(ShaderType.class);
                    for (var stage : pipeline.getShaders().entrySet()) {
                        Path path = programPath(veil, stage.getValue().getPath(), suffix(stage.getKey())); inputs.add(path);
                        raw.put(stage.getKey(), Files.readString(path));
                    }
                    var processed = processor.processProgram(pipeline.getShaders(), raw::get);
                    Set<String> expectedUniforms = switch (name) {
                        case "core/composite" -> Set.of("ColorModulator");
                        case "core/blit_screen_effect" -> Set.of("TexOffset", "ColorModulator");
                        case "debug/array" -> Set.of("Index");
                        default -> Set.of();
                    };
                    var layouts = processor.automaticUniformLayouts();
                    check(expectedUniforms.isEmpty() ? layouts.isEmpty()
                            : layouts.size() == 1 && layouts.get(GpuShaderSource.MANAGED_UNIFORMS).fields().keySet().equals(expectedUniforms),
                            name + " wrong shared managed numeric uniform schema: " + layouts);
                    Set<String> expectedSamplers = switch (name) {
                        case "quasar/particle_additive" -> Set.of("Sampler0", "Sampler2");
                        case "blit_depth" -> Set.of("DiffuseDepthSampler");
                        case "core/composite" -> Set.of("DiffuseSampler0", "DiffuseDepthSampler", "LightSampler");
                        case "debug/array", "debug/cubemap" -> Set.of("DiffuseSampler0");
                        default -> Set.of("DiffuseSampler");
                    };
                    check(processor.automaticSamplerNames().equals(expectedSamplers), name + " wrong actual sampler contract");
                    for (var stage : processed.entrySet()) {
                        String label = name.replace('/', '_') + suffix(stage.getKey());
                        Files.writeString(output.resolve(label), stage.getValue());
                        long result = shaderc_compile_into_spv(compiler, stage.getValue(), stage.getKey() == ShaderType.VERTEX
                                ? shaderc_glsl_vertex_shader : shaderc_glsl_fragment_shader, label, "main", options);
                        try {
                            check(shaderc_result_get_compilation_status(result) == shaderc_compilation_status_success,
                                    label + ": " + shaderc_result_get_error_message(result));
                            var bytes = shaderc_result_get_bytes(result); byte[] code = new byte[bytes.remaining()]; bytes.get(code);
                            Files.write(output.resolve(label + ".spv"), code);
                        } finally { shaderc_result_release(result); }
                        stages++;
                    }
                }
                Files.write(output.resolve("resource-inputs.txt"), inputs.stream().map(Path::toString).toList());
                System.out.println("PASS " + stages + " CPU shaderc stages across all seven actual public managed shader pairs; native typed particle ABI and real shared numeric/sampler schemas");
            } finally { shaderc_compile_options_release(options); shaderc_compiler_release(compiler); }
        }
    }
    private static PathPackResources pack(String id, Path root) {
        return new PathPackResources(new PackLocationInfo(id, Component.literal(id), PackSource.BUILT_IN, Optional.empty()), root);
    }
    private static Path programPath(Path root, String name, String suffix) {
        return root.resolve("assets/veil/pinwheel/shaders/program/" + name + suffix);
    }
    private static String suffix(ShaderType stage) { return stage == ShaderType.VERTEX ? ".vsh" : ".fsh"; }
    private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
}
