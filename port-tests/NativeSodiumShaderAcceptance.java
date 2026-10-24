import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.pipeline.*;
import com.mojang.renderpearl.api.vertex.VertexFormat;
import foundry.veil.api.client.render.dynamicbuffer.DynamicBufferType;
import foundry.veil.api.client.render.shader.ShaderPreDefinitions;
import foundry.veil.impl.client.render.dynamicbuffer.DynamicBufferProcessor;
import foundry.veil.impl.client.render.shader.processor.NativeShaderProcessor;
import io.github.ocelot.glslprocessor.api.GlslParser;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.*;
import net.minecraft.server.packs.repository.PackSource;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceProvider;

import java.nio.file.*;
import java.util.*;

/** Exact genuine Sodium source/include pair through the production native processor and shared MRT AST. */
public final class NativeSodiumShaderAcceptance {
    private static final int NORMAL = DynamicBufferType.NORMAL.getMask();
    private static final Identifier VERTEX = Identifier.parse("sodium:blocks/block_layer_opaque");
    public static void main(String[] args) throws Exception {
        Path assets = Path.of(args[0]);
        try (var pack = new PathPackResources(new PackLocationInfo("native-sodium-source", Component.literal("Actual Sodium source"), PackSource.BUILT_IN, Optional.empty()), assets)) {
            ResourceProvider resources = id -> Optional.ofNullable(pack.getResource(PackType.CLIENT_RESOURCES, id)).map(stream -> new Resource(pack, stream));
            String rawVertex = Files.readString(assets.resolve("assets/sodium/shaders/blocks/block_layer_opaque.vsh"));
            String rawFragment = Files.readString(assets.resolve("assets/sodium/shaders/blocks/block_layer_opaque.fsh"));
            var nativeFormat = format(GpuFormat.RGBA8_SNORM);
            var dynamic = new DynamicBufferProcessor();
            var processor = processor(resources, nativeFormat, NORMAL, dynamic);
            String vertex = processor.process(VERTEX, ShaderType.VERTEX, rawVertex);
            String fragment = processor.process(VERTEX, ShaderType.FRAGMENT, rawFragment);
            Files.writeString(Path.of(args[1]).resolve("processed.vsh"), vertex);
            Files.writeString(Path.of(args[1]).resolve("processed.fsh"), fragment);
            var tree = GlslParser.parse(vertex);
            check(tree.field("a_VeilNormal").isPresent() && vertex.contains("layout(location = 4) in vec4 a_VeilNormal"), "Native normalized vec4 input does not match actual layout location");
            check(vertex.contains("transpose(inverse(mat3(u_ModelViewMatrix))) * a_VeilNormal.xyz"), "Actual native UBO inverse-transpose normal expression missing");
            check(vertex.contains("PassVeilDynamicNormal") && fragment.contains("PassVeilDynamicNormal") && fragment.contains("VeilDynamicNormal"), "Native marker did not precede real DynamicBufferProcessor stage passthrough");
            check((dynamic.producedBuffers() & NORMAL) != 0 && !vertex.contains("VeilNormalMatrix"), "Native normals did not produce genuine shared output or retained loose GL normal uniform");

            String noMask = processor(resources, nativeFormat, 0, new DynamicBufferProcessor()).process(VERTEX, ShaderType.VERTEX, rawVertex);
            check(!noMask.contains("a_VeilNormal") && !noMask.contains("veilNativeSodiumNormal"), "Inactive native normal mask injected inputs");
            var compact = VertexFormat.builder(0).addAttribute("a_Position", GpuFormat.RG32_UINT).addAttribute("a_Color", GpuFormat.RGBA8_UNORM)
                    .addAttribute("a_TexCoord", GpuFormat.RG16_UINT).addAttribute("a_LightAndData", GpuFormat.RGBA8_UINT).build();
            String noAttribute = processor(resources, compact, NORMAL, new DynamicBufferProcessor()).process(VERTEX, ShaderType.VERTEX, rawVertex);
            check(!noAttribute.contains("veilNativeSodiumNormal"), "Native processor fabricated an absent normal attribute");
            String other = processor(resources, nativeFormat, NORMAL, new DynamicBufferProcessor()).process(Identifier.parse("other:blocks/block_layer_opaque"), ShaderType.VERTEX, rawVertex);
            check(!other.contains("veilNativeSodiumNormal"), "Native Sodium processor modified another shader namespace");
            String custom = processor(resources, nativeFormat, NORMAL, new DynamicBufferProcessor()).process(VERTEX, ShaderType.VERTEX,
                    rawVertex.replace("void main() {", "void main() {\n// #veil:normal\nvec3 customSodiumNormal = vec3(0.0, 0.0, 1.0);"));
            check(!custom.contains("veilNativeSodiumNormal") && custom.contains("customSodiumNormal") && custom.contains("PassVeilDynamicNormal"),
                    "Explicit source normal marker was replaced or failed to reach the actual shared processor");
            rejected(() -> processor(resources, format(GpuFormat.RGBA8_UINT), NORMAL, new DynamicBufferProcessor()).process(VERTEX, ShaderType.VERTEX, rawVertex), "RGBA8_SNORM");
            rejected(() -> processor(resources, nativeFormat, NORMAL, new DynamicBufferProcessor()).process(VERTEX, ShaderType.VERTEX, rawVertex.replace("#include <sodium:globals.glsl>", "")), "u_Globals");
            System.out.println("PASS actual Sodium26.3 source pair: typed native inverse-transpose normals reach shared dynamic outputs; inactive/absent/foreign inputs stay untouched and invalid contracts fail explicitly");
        }
    }
    private static VertexFormat format(GpuFormat normal) {
        return VertexFormat.builder(0).addAttribute("a_Position", GpuFormat.RG32_UINT).addAttribute("a_Color", GpuFormat.RGBA8_UNORM)
                .addAttribute("a_TexCoord", GpuFormat.RG16_UINT).addAttribute("a_LightAndData", GpuFormat.RGBA8_UINT).addAttribute("a_VeilNormal", normal).build();
    }
    private static NativeShaderProcessor processor(ResourceProvider resources, VertexFormat format, int mask, DynamicBufferProcessor dynamic) {
        var pipeline = RenderPipeline.builder().withLocation(Identifier.parse("veil_test:actual_native_sodium_contract"))
                .withVertexShader(VERTEX).withFragmentShader(VERTEX).withVertexBinding(0, format)
                .withPrimitiveTopology(PrimitiveTopology.TRIANGLES)
                .withColorTargetState(new ColorTargetState(Optional.empty(), GpuFormat.RGBA8_UNORM, ColorTargetState.WRITE_ALL)).build();
        return new NativeShaderProcessor(resources, new ShaderPreDefinitions(), null,
                Map.of("VULKAN", "1", "USE_VERTEX_COMPRESSION", "1"), new HashSet<>(), mask, List.of())
                .forVariant(pipeline, mask, List.of((provider, registry) -> registry.addPreprocessor(dynamic, false)));
    }
    private interface Operation { void run(); }
    private static void rejected(Operation operation, String message) {
        try { operation.run(); } catch (IllegalArgumentException expected) {
            check(expected.getCause() != null && expected.getCause().getMessage().contains(message), "Wrong invalid native normal contract: " + expected); return;
        }
        throw new AssertionError("Accepted invalid native normal contract: " + message);
    }
    private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
}
