package foundry.veil.api.client.render.rendertype.layer;

import com.mojang.serialization.MapCodec;
import foundry.veil.api.client.registry.RenderTypeLayerRegistry;
import foundry.veil.api.client.render.rendertype.VeilRenderTypeBuilder;

public record TransparencyLayer(LayerTemplateValue<BlendState> mode) implements RenderTypeLayer {

    public static final MapCodec<TransparencyLayer> CODEC = LayerTemplateValue.enumCodec(BlendState.class)
            .fieldOf("mode")
            .xmap(TransparencyLayer::new, TransparencyLayer::mode);

    @Override
    public void addShard(VeilRenderTypeBuilder builder, Object... params) {
        builder.transparencyState(this.mode.parse(params).nativeState);
    }

    @Override
    public RenderTypeLayerRegistry.LayerType<?> getType() {
        return RenderTypeLayerRegistry.TRANSPARENCY.get();
    }

    public enum BlendState {
        NONE(java.util.Optional.empty()),
        ADDITIVE(java.util.Optional.of(new com.mojang.renderpearl.api.pipeline.BlendFunction(com.mojang.renderpearl.api.pipeline.BlendFactor.ONE, com.mojang.renderpearl.api.pipeline.BlendFactor.ONE))),
        LIGHTNING(java.util.Optional.of(com.mojang.renderpearl.api.pipeline.BlendFunction.LIGHTNING)),
        GLINT(java.util.Optional.of(com.mojang.renderpearl.api.pipeline.BlendFunction.GLINT)),
        CRUMBLING(java.util.Optional.of(new com.mojang.renderpearl.api.pipeline.BlendFunction(com.mojang.renderpearl.api.pipeline.BlendFactor.DST_COLOR, com.mojang.renderpearl.api.pipeline.BlendFactor.SRC_COLOR, com.mojang.renderpearl.api.pipeline.BlendFactor.ONE, com.mojang.renderpearl.api.pipeline.BlendFactor.ZERO))),
        TRANSLUCENT(java.util.Optional.of(com.mojang.renderpearl.api.pipeline.BlendFunction.TRANSLUCENT));

        private final java.util.Optional<com.mojang.renderpearl.api.pipeline.BlendFunction> nativeState;
        BlendState(java.util.Optional<com.mojang.renderpearl.api.pipeline.BlendFunction> nativeState) { this.nativeState = nativeState; }
        public java.util.Optional<com.mojang.renderpearl.api.pipeline.BlendFunction> getState() { return this.nativeState; }
    }
}
