package foundry.veil.api.client.render;

import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.renderer.rendertype.RenderType;

/** Geometry capture; the owner submits its immutable snapshots to a native feature collector. */
@FunctionalInterface
public interface VertexSource {
    VertexConsumer getBuffer(RenderType type);
}
