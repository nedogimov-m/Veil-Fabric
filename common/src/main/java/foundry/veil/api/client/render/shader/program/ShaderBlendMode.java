package foundry.veil.api.client.render.shader.program;

import com.mojang.renderpearl.api.pipeline.BlendFactor;
import com.mojang.renderpearl.api.pipeline.BlendFunction;
import com.mojang.renderpearl.api.pipeline.BlendOp;
import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import foundry.veil.api.util.EnumCodec;

/** Serialized Veil blend state, applied when constructing a native render pipeline. */
public record ShaderBlendMode(BlendOp colorEquation, BlendOp alphaEquation,
                              BlendFactor srcColorFactor, BlendFactor dstColorFactor,
                              BlendFactor srcAlphaFactor, BlendFactor dstAlphaFactor) {
    private static final Codec<BlendFactor> FACTOR_CODEC = EnumCodec.<BlendFactor>builder("Blend Factor")
            .values(BlendFactor.class).build();
    private static final Codec<BlendOp> OP_CODEC = EnumCodec.<BlendOp>builder("Blend Equation")
            .values(BlendOp.class).build();
    public static final Codec<BlendFactor> SOURCE_FACTOR_CODEC = FACTOR_CODEC;
    public static final Codec<BlendFactor> DESTINATION_FACTOR_CODEC = FACTOR_CODEC;

    public static final Codec<ShaderBlendMode> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            OP_CODEC.optionalFieldOf("func", BlendOp.ADD).forGetter(ShaderBlendMode::colorEquation),
            OP_CODEC.optionalFieldOf("alphafunc", BlendOp.ADD).forGetter(ShaderBlendMode::alphaEquation),
            FACTOR_CODEC.optionalFieldOf("srcrgb", BlendFactor.ONE).forGetter(ShaderBlendMode::srcColorFactor),
            FACTOR_CODEC.optionalFieldOf("dstrgb", BlendFactor.ZERO).forGetter(ShaderBlendMode::dstColorFactor),
            FACTOR_CODEC.optionalFieldOf("srcalpha", BlendFactor.ONE).forGetter(ShaderBlendMode::srcAlphaFactor),
            FACTOR_CODEC.optionalFieldOf("dstalpha", BlendFactor.ZERO).forGetter(ShaderBlendMode::dstAlphaFactor)
    ).apply(instance, ShaderBlendMode::new));

    public BlendFunction toBlendFunction() {
        return new BlendFunction(this.srcColorFactor, this.dstColorFactor, this.colorEquation,
                this.srcAlphaFactor, this.dstAlphaFactor, this.alphaEquation);
    }

    public boolean hasEquation() { return this.colorEquation != BlendOp.ADD || this.alphaEquation != BlendOp.ADD; }
}
