package foundry.veil.api.client.render.gpu;

import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import com.mojang.renderpearl.api.commands.CommandEncoder;
import com.mojang.renderpearl.api.pipeline.*;
import com.mojang.renderpearl.api.vertex.VertexFormat;
import foundry.veil.api.client.render.vertex.VertexArray;
import net.minecraft.resources.Identifier;
import org.jetbrains.annotations.Nullable;

import java.util.Map;
import java.util.Optional;

/** The real native additive light pass; the caller owns submission and its leases. */
public final class NativeLightPass {
    public static RenderPipeline pipeline(Identifier id, Map<Integer, VertexFormat> formats,
            PrimitiveTopology topology, GpuFramebuffer target, boolean volume) {
        var bindings = BindGroupLayout.builder()
                .withUniform("AlbedoSampler", UniformType.COMBINED_IMAGE_SAMPLER)
                .withUniform("NormalSampler", UniformType.COMBINED_IMAGE_SAMPLER);
        if (volume) bindings.withUniform("DepthSampler", UniformType.COMBINED_IMAGE_SAMPLER)
                .withUniform("BlockGrid", UniformType.COMBINED_IMAGE_SAMPLER);
        var builder = RenderPipeline.builder().withLocation(id).withVertexShader(id).withFragmentShader(id)
                .withBindGroupLayout(bindings.build()).withCull(volume).withPrimitiveTopology(topology)
                .withColorTargetState(new ColorTargetState(Optional.of(BlendFunction.ADDITIVE),
                        target.color(0).texture().getFormat(), ColorTargetState.WRITE_ALL));
        formats.forEach(builder::withVertexBinding);
        // The inward volume's far wall must be behind the surface; native depth is reversed.
        if (volume) builder.withDepthStencilState(new DepthStencilState(CompareOp.LESS_THAN, false));
        return builder.build();
    }

    /** Retain the program and optional mesh until this encoder has been submitted. */
    public static void record(CommandEncoder encoder, GpuFramebuffer target, GpuShaderProgram program,
            @Nullable VertexArray.Draw mesh, int instances, Map<String, GpuBufferSlice> blocks) {
        if (instances <= 0) throw new IllegalArgumentException("Light instance count must be positive");
        if (mesh != null) mesh.validatePipeline(program.definition());
        program.prepare(encoder);
        try (var pass = target.beginPass(encoder)) {
            program.bind(pass, blocks);
            if (mesh == null) pass.draw(3, instances, 0, 0);
            else mesh.drawInstanced(pass, instances);
        }
    }
    private NativeLightPass() { }
}
