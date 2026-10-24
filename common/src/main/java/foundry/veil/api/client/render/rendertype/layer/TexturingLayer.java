package foundry.veil.api.client.render.rendertype.layer;

import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import foundry.veil.api.client.registry.RenderTypeLayerRegistry;
import foundry.veil.api.client.render.rendertype.VeilRenderTypeBuilder;
import net.minecraft.util.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.rendertype.TextureTransform;
import org.joml.Matrix4f;

public record TexturingLayer(float scale) implements RenderTypeLayer {

    public static final MapCodec<TexturingLayer> CODEC = Codec.FLOAT.fieldOf("scale")
            .xmap(TexturingLayer::new, TexturingLayer::scale);

    @Override
    public void addShard(VeilRenderTypeBuilder builder, Object... params) {
        if (this.scale != 1) builder.texturingState(new TextureTransform("veil:glint_texturing", () -> {
            final long time = (long) (Util.getMillis() * Minecraft.getInstance().options.glintSpeed().get() * 8);
            final float x = (float) (time % 110000L) / 110000;
            final float y = (float) (time % 30000L) / 30000;
            return new Matrix4f().translation(-x, y, 0).rotateZ((float) (Math.PI / 18)).scale(this.scale);
        }));
    }

    @Override
    public RenderTypeLayerRegistry.LayerType<?> getType() {
        return RenderTypeLayerRegistry.TEXTURING.get();
    }
}
