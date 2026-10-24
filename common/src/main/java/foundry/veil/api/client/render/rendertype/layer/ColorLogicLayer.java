package foundry.veil.api.client.render.rendertype.layer;

import com.mojang.serialization.MapCodec;
import foundry.veil.api.client.registry.RenderTypeLayerRegistry;
import foundry.veil.api.client.render.rendertype.VeilRenderTypeBuilder;

public record ColorLogicLayer(LayerTemplateValue<Operation> operation) implements RenderTypeLayer {

    public static final MapCodec<ColorLogicLayer> CODEC = LayerTemplateValue.enumCodec(Operation.class)
            .fieldOf("operation")
            .xmap(ColorLogicLayer::new, ColorLogicLayer::operation);

    @Override
    public void addShard(VeilRenderTypeBuilder builder, Object... params) {
        builder.colorLogicState(this.operation.parse(params));
    }

    @Override
    public RenderTypeLayerRegistry.LayerType<?> getType() {
        return RenderTypeLayerRegistry.COLOR_LOGIC.get();
    }
    public enum Operation { CLEAR, AND, AND_REVERSE, COPY, AND_INVERTED, NOOP, XOR, OR, NOR, EQUIV, INVERT, OR_REVERSE, COPY_INVERTED, OR_INVERTED, NAND, SET }
}
