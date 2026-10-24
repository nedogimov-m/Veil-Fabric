package foundry.veil.api.client.render.post.stage;

import com.mojang.renderpearl.api.pipeline.CompareOp;
import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.MapCodec;
import foundry.veil.api.client.registry.PostPipelineStageRegistry;
import foundry.veil.api.client.render.post.PostPipeline;


/**
 * Sets the depth function.
 *
 * @param func The new depth function to use
 * @author Ocelot
 */
public record DepthFunctionPostStage(DepthFunc func) implements PostPipeline {

    public static final MapCodec<DepthFunctionPostStage> CODEC = DepthFunc.CODEC.fieldOf("function").xmap(DepthFunctionPostStage::new, DepthFunctionPostStage::func);

    @Override
    public void apply(Context context) {
        context.setDepthState(this.func.comparison(), context.writeDepth());
    }

    @Override
    public PostPipelineStageRegistry.PipelineType<? extends PostPipeline> getType() {
        return PostPipelineStageRegistry.DEPTH_FUNC.get();
    }

    public enum DepthFunc {
        NEVER(CompareOp.NEVER_PASS),
        LESS(CompareOp.GREATER_THAN),
        EQUAL(CompareOp.EQUAL),
        LEQUAL(CompareOp.GREATER_THAN_OR_EQUAL),
        GREATER(CompareOp.LESS_THAN),
        NOTEQUAL(CompareOp.NOT_EQUAL),
        GEQUAL(CompareOp.LESS_THAN_OR_EQUAL),
        ALWAYS(CompareOp.ALWAYS_PASS);

        public static final Codec<DepthFunc> CODEC = Codec.STRING.flatXmap(name -> {
            for (DepthFunc type : DepthFunc.values()) {
                if (type.name().equalsIgnoreCase(name)) {
                    return DataResult.success(type);
                }
            }
            return DataResult.error(() -> "Unknown depth function: " + name);
        }, type -> DataResult.success(type.name()));

        private final CompareOp comparison;

        DepthFunc(CompareOp comparison) {
            this.comparison = comparison;
        }

        /**
         * @return The immutable native depth comparison
         */
        public CompareOp comparison() {
            return this.comparison;
        }
    }
}
