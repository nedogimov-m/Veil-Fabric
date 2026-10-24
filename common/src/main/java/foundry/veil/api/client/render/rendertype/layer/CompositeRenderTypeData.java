package foundry.veil.api.client.render.rendertype.layer;

import com.mojang.renderpearl.api.vertex.VertexFormat;
import com.mojang.renderpearl.api.pipeline.PrimitiveTopology;
import com.mojang.serialization.Codec;
import com.mojang.datafixers.util.Either;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import foundry.veil.api.client.render.rendertype.VeilRenderType;
import foundry.veil.api.client.render.rendertype.VeilRenderTypeBuilder;
import foundry.veil.api.client.util.VertexFormatCodec;
import net.minecraft.client.renderer.rendertype.RenderType;
import org.jetbrains.annotations.ApiStatus;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

public record CompositeRenderTypeData(VertexFormat format, PrimitiveTopology mode, int bufferSize,
                                      boolean affectsCrumbling, boolean sort, boolean outline, boolean standardUniforms,
                                      List<RenderTypeLayer[]> layers) {

    public CompositeRenderTypeData(VertexFormat format, PrimitiveTopology mode, int bufferSize, boolean affectsCrumbling,
                                   boolean sort, boolean outline, List<RenderTypeLayer[]> layers) {
        this(format, mode, bufferSize, affectsCrumbling, sort, outline, true, layers);
    }
    public CompositeRenderTypeData {
        if (bufferSize < 0) throw new IllegalArgumentException("Render buffer size cannot be negative");
        layers = layers.stream().map(RenderTypeLayer[]::clone).toList();
        if (layers.isEmpty()) throw new IllegalArgumentException("A render type needs at least one layer");
    }
    @Override public List<RenderTypeLayer[]> layers() { return this.layers.stream().map(RenderTypeLayer[]::clone).toList(); }

    private static final Codec<List<List<RenderTypeLayer>>> LAYER_CODEC = Codec.either(
            RenderTypeLayer.CODEC.listOf(),
            RenderTypeLayer.CODEC.listOf().listOf().validate(list -> list.size() < 2
                    ? DataResult.error(() -> "Expected at least 2 layers") : DataResult.success(list)))
            .xmap(value -> value.map(Collections::singletonList, layers -> layers),
                    layers -> layers.size() == 1 ? Either.left(layers.getFirst()) : Either.right(layers))
            .validate(layers -> layers.isEmpty() ? DataResult.error(() -> "Expected at least one render layer") : DataResult.success(layers));

    public static final Codec<CompositeRenderTypeData> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            VertexFormatCodec.CODEC.fieldOf("format").forGetter(CompositeRenderTypeData::format),
            VertexFormatCodec.MODE_CODEC.fieldOf("mode").forGetter(CompositeRenderTypeData::mode),
            VertexFormatCodec.BUFFER_SIZE_CODEC.fieldOf("bufferSize").forGetter(CompositeRenderTypeData::bufferSize),
            Codec.BOOL.optionalFieldOf("affectsCrumbling", false).forGetter(CompositeRenderTypeData::affectsCrumbling),
            Codec.BOOL.optionalFieldOf("sort", false).forGetter(CompositeRenderTypeData::sort),
            Codec.BOOL.optionalFieldOf("outline", false).forGetter(CompositeRenderTypeData::outline),
            Codec.BOOL.optionalFieldOf("standardUniforms", true).forGetter(CompositeRenderTypeData::standardUniforms),
            LAYER_CODEC.fieldOf("layers").forGetter(state -> state.layers().stream().map(Arrays::asList).toList())
    ).apply(instance, (format, mode, bufferSize, affectsCrumbling, sort, outline, standardUniforms, layersList) -> {
        List<RenderTypeLayer[]> layers = new ArrayList<>(layersList.size());
        for (List<RenderTypeLayer> shards : layersList) {
            layers.add(shards.toArray(RenderTypeLayer[]::new));
        }
        return new CompositeRenderTypeData(format, mode, bufferSize, affectsCrumbling, sort, outline, standardUniforms, Collections.unmodifiableList(layers));
    }));

    @ApiStatus.Internal
    public RenderType createRenderType(String name, Object... params) {
        RenderType[] renderTypes = new RenderType[this.layers.size()];
        for (int i = 0; i < this.layers.size(); i++) {
            RenderTypeLayer[] shards = this.layers.get(i);

            VeilRenderTypeBuilder builder = new VeilRenderTypeBuilder().standardUniforms(this.standardUniforms);
            for (RenderTypeLayer shard : shards) {
                shard.addShard(builder, params);
            }

            renderTypes[i] = VeilRenderType.create(name + "/" + i, this.format, this.mode, this.bufferSize,
                    this.affectsCrumbling, this.sort, this.outline, builder.create());
        }
        return VeilRenderType.layered(renderTypes);
    }
}
