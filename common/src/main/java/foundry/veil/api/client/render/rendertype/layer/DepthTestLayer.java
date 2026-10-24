package foundry.veil.api.client.render.rendertype.layer;

import com.mojang.serialization.MapCodec;
import foundry.veil.api.client.registry.RenderTypeLayerRegistry;
import foundry.veil.api.client.render.rendertype.VeilRenderTypeBuilder;

public record DepthTestLayer(LayerTemplateValue<DepthState> mode) implements RenderTypeLayer {

    public static final MapCodec<DepthTestLayer> CODEC = LayerTemplateValue.enumCodec(DepthState.class)
            .fieldOf("mode")
            .xmap(DepthTestLayer::new, DepthTestLayer::mode);

    @Override
    public void addShard(VeilRenderTypeBuilder builder, Object... params) {
        builder.depthTestState(this.mode.parse(params).nativeState);
    }

    @Override
    public RenderTypeLayerRegistry.LayerType<?> getType() {
        return RenderTypeLayerRegistry.DEPTH_TEST.get();
    }

    public enum DepthState {
        NEVER(com.mojang.renderpearl.api.pipeline.CompareOp.NEVER_PASS),
        LESS(com.mojang.renderpearl.api.pipeline.CompareOp.GREATER_THAN),
        EQUAL(com.mojang.renderpearl.api.pipeline.CompareOp.EQUAL),
        LEQUAL(com.mojang.renderpearl.api.pipeline.CompareOp.GREATER_THAN_OR_EQUAL),
        GREATER(com.mojang.renderpearl.api.pipeline.CompareOp.LESS_THAN),
        NOTEQUAL(com.mojang.renderpearl.api.pipeline.CompareOp.NOT_EQUAL),
        GEQUAL(com.mojang.renderpearl.api.pipeline.CompareOp.LESS_THAN_OR_EQUAL),
        ALWAYS(com.mojang.renderpearl.api.pipeline.CompareOp.ALWAYS_PASS);

        private final com.mojang.renderpearl.api.pipeline.CompareOp nativeState;
        DepthState(com.mojang.renderpearl.api.pipeline.CompareOp nativeState) { this.nativeState = nativeState; }
        public com.mojang.renderpearl.api.pipeline.CompareOp getState() { return this.nativeState; }
    }
}
