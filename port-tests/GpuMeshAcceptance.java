import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.device.GpuDevice;
import com.mojang.renderpearl.api.pipeline.*;
import com.mojang.renderpearl.api.vertex.VertexFormat;
import foundry.veil.api.client.render.gpu.GpuFramebuffer;
import foundry.veil.api.client.render.gpu.GpuShaderProgram;
import foundry.veil.api.client.render.gpu.GpuShaderSource;
import foundry.veil.api.client.render.vertex.VertexArray;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.Identifier;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.List;
import java.util.Optional;

/** Test-only real mesh/instance/indirect acceptance with native color readback. */
public final class GpuMeshAcceptance {
    private static final VertexFormat INSTANCES = VertexFormat.builder(1)
            .addAttribute("Offset", GpuFormat.RG32_FLOAT).addAttribute("InstanceColor", GpuFormat.RGBA8_UNORM).build();
    public static void run(Minecraft client, GpuDevice device, List<String> checks) {
        var pipeline = RenderPipeline.builder().withLocation(Identifier.parse("veil:gpu_mesh_acceptance"))
                .withVertexShader(Identifier.parse("veil:core/gpu_mesh")).withFragmentShader(Identifier.parse("veil:core/gpu_mesh"))
                .withVertexBinding(0, DefaultVertexFormat.POSITION).withVertexBinding(1, INSTANCES)
                .withPrimitiveTopology(PrimitiveTopology.QUADS).withCull(false).withDepthStencilState(Optional.empty())
                .withColorTargetState(ColorTargetState.DEFAULT).build();
        try (var source = new GpuShaderSource(client.getResourceManager());
             var program = new GpuShaderProgram(device, pipeline, source, Runnable::run);
             var mesh = VertexArray.create(device, "retained instanced quad");
             var vertices = new ByteBufferBuilder(256);
             var outputA = GpuFramebuffer.builder("mesh retained A", 4, 2).color(GpuFormat.RGBA8_UNORM).build(device);
             var outputB = GpuFramebuffer.builder("mesh retained B", 4, 2).color(GpuFormat.RGBA8_UNORM).build(device);
             var indirectOutput = GpuFramebuffer.builder("mesh indirect", 4, 2).color(GpuFormat.RGBA8_UNORM).build(device);
             var indirect = device.createBuffer(() -> "mesh indexed indirect commands", GpuBuffer.USAGE_INDIRECT_PARAMETERS | GpuBuffer.USAGE_COPY_DST, 20)) {
            var builder = new BufferBuilder(vertices, PrimitiveTopology.QUADS, DefaultVertexFormat.POSITION);
            builder.addVertex(-.5f, -1, .5f); builder.addVertex(.5f, -1, .5f); builder.addVertex(.5f, 1, .5f); builder.addVertex(-.5f, 1, .5f);
            var encoder = device.createCommandEncoder();
            mesh.upload(encoder, builder.buildOrThrow(), VertexArray.DrawUsage.STATIC);
            mesh.uploadVertexBuffer(encoder, 1, instances(255, 0, 0, 0, 0, 255), INSTANCES, VertexArray.DrawUsage.DYNAMIC);
            check(mesh.getIndexCount() == 6 && mesh.getVertexCount() == 4, "Minecraft sequential quad topology must become six real indices");
            var oldVertices = mesh.vertexBuffer(0); var oldInstances = mesh.vertexBuffer(1); var oldIndices = mesh.indexBuffer().buffer();
            try { mesh.uploadIndexBuffer(encoder, ByteBuffer.allocateDirect(3), IndexType.SHORT); throw new AssertionError("Partial index upload was accepted"); }
            catch (IllegalArgumentException expected) { check(mesh.indexBuffer().buffer() == oldIndices && !oldIndices.isClosed(), "Rejected index upload destroyed live mesh storage"); }
            try (var first = mesh.retain()) {
                first.validatePipeline(pipeline);
                mesh.uploadVertexBuffer(encoder, 1, instances(0, 255, 0, 255, 255, 0), INSTANCES, VertexArray.DrawUsage.STREAM);
                try (var second = mesh.retain()) {
                    mesh.close();
                    check(!oldVertices.isClosed() && !oldInstances.isClosed() && !oldIndices.isClosed(), "Retained mesh storage closed before submission");
                    var commands = ByteBuffer.allocateDirect(20).order(ByteOrder.nativeOrder()).putInt(6).putInt(2).putInt(0).putInt(0).putInt(0); commands.flip();
                    encoder.writeToBuffer(indirect.slice(), commands);
                    try (var pass = outputA.beginPass(encoder)) { program.bind(pass); first.drawInstanced(pass, 2); }
                    try (var pass = outputB.beginPass(encoder)) { program.bind(pass); second.drawInstanced(pass, 2); }
                    try (var pass = indirectOutput.beginPass(encoder)) { program.bind(pass); first.drawIndirect(pass, indirect.slice(), 1); }
                    encoder.submit();
                    assertHalves(device, outputA, new int[]{255, 0, 0, 255}, new int[]{0, 0, 255, 255});
                    assertHalves(device, outputB, new int[]{0, 255, 0, 255}, new int[]{255, 255, 0, 255});
                    assertHalves(device, indirectOutput, new int[]{255, 0, 0, 255}, new int[]{0, 0, 255, 255});
                }
                check(!mesh.isClosed(), "A remaining draw lease must retain the mesh owner");
            }
            check(mesh.isClosed() && oldVertices.isClosed() && oldInstances.isClosed() && oldIndices.isClosed(), "Final mesh lease must dispose retired owned buffers");
            checks.add("native Minecraft quad mesh, normalized instanced attributes, indexed indirect draw and replacement/disposal leases drive exact Vulkan pixels");
        }

        var triangles = RenderPipeline.builder().withLocation(Identifier.parse("veil:gpu_mesh_borrowed_acceptance"))
                .withVertexShader(Identifier.parse("veil:core/gpu_mesh")).withFragmentShader(Identifier.parse("veil:core/gpu_mesh"))
                .withVertexBinding(0, DefaultVertexFormat.POSITION).withVertexBinding(1, INSTANCES)
                .withPrimitiveTopology(PrimitiveTopology.TRIANGLES).withCull(false).withDepthStencilState(Optional.empty())
                .withColorTargetState(ColorTargetState.DEFAULT).build();
        try (var source = new GpuShaderSource(client.getResourceManager());
             var program = new GpuShaderProgram(device, triangles, source, Runnable::run);
             var mesh = VertexArray.create(device, "borrowed nonindexed mesh");
             var vertices = device.createBuffer(() -> "borrowed triangle vertices", GpuBuffer.USAGE_VERTEX | GpuBuffer.USAGE_COPY_DST, 36);
             var instance = device.createBuffer(() -> "borrowed instance", GpuBuffer.USAGE_VERTEX | GpuBuffer.USAGE_COPY_DST, 12);
             var indirect = device.createBuffer(() -> "nonindexed indirect", GpuBuffer.USAGE_INDIRECT_PARAMETERS | GpuBuffer.USAGE_COPY_DST, 16);
             var output = GpuFramebuffer.builder("borrowed mesh", 2, 2).color(GpuFormat.RGBA8_UNORM).build(device)) {
            var encoder = device.createCommandEncoder();
            var positions = ByteBuffer.allocateDirect(36).order(ByteOrder.nativeOrder());
            for (float value : new float[]{-1, -1, .5f, 3, -1, .5f, -1, 3, .5f}) positions.putFloat(value); positions.flip();
            var green = ByteBuffer.allocateDirect(12).order(ByteOrder.nativeOrder()).putFloat(0).putFloat(0).putInt(0xFF00FF00); green.flip();
            var command = ByteBuffer.allocateDirect(16).order(ByteOrder.nativeOrder()).putInt(3).putInt(1).putInt(0).putInt(0); command.flip();
            encoder.writeToBuffer(vertices.slice(), positions); encoder.writeToBuffer(instance.slice(), green); encoder.writeToBuffer(indirect.slice(), command);
            mesh.editFormat().defineVertexBuffer(0, vertices.slice(), DefaultVertexFormat.POSITION).defineVertexBuffer(1, instance.slice(), INSTANCES);
            mesh.setVertexCount(3);
            try (var draw = mesh.retain()) {
                draw.validatePipeline(triangles); mesh.close();
                try (var pass = output.beginPass(encoder)) { program.bind(pass); draw.drawIndirect(pass, indirect.slice(), 1); }
                encoder.submit(); assertHalves(device, output, new int[]{0, 255, 0, 255}, new int[]{0, 255, 0, 255});
            }
            check(mesh.isClosed() && !vertices.isClosed() && !instance.isClosed(), "Borrowed mesh disposal must preserve buffer owners");
            checks.add("native borrowed ranges, nonindexed indirect draw and external buffer ownership drive exact Vulkan pixels");
        }
    }
    private static ByteBuffer instances(int r1, int g1, int b1, int r2, int g2, int b2) {
        return ByteBuffer.allocateDirect(24).order(ByteOrder.nativeOrder())
                .putFloat(-.5f).putFloat(0).put((byte) r1).put((byte) g1).put((byte) b1).put((byte) 255)
                .putFloat(.5f).putFloat(0).put((byte) r2).put((byte) g2).put((byte) b2).put((byte) 255).flip();
    }
    private static void assertHalves(GpuDevice device, GpuFramebuffer target, int[] left, int[] right) {
        int size = target.width() * target.height() * 4;
        try (var readback = device.createBuffer(() -> "mesh readback", GpuBuffer.USAGE_COPY_DST | GpuBuffer.USAGE_MAP_READ, size)) {
            var encoder = device.createCommandEncoder(); encoder.copyTextureToBuffer(target.color(0).texture(), readback, 0, () -> {}, 0);
            try (var fence = encoder.createFence()) {
                encoder.submit(); check(fence.awaitCompletion(10_000_000_000L), "Mesh GPU fence timed out");
                try (var mapped = readback.map(true, false)) {
                    for (int y = 0; y < target.height(); y++) for (int x = 0; x < target.width(); x++) for (int component = 0; component < 4; component++) {
                        int actual = mapped.data().get((y * target.width() + x) * 4 + component) & 255;
                        int expected = (x < target.width() / 2 ? left : right)[component];
                        check(Math.abs(actual - expected) <= 1, "Native mesh pixel (" + x + "," + y + ") component " + component + " was " + actual + ", expected " + expected);
                    }
                }
            }
        }
    }
    private static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
