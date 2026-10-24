package foundry.veil.api.client.necromancer.render;

import foundry.veil.impl.client.necromancer.render.NecromancerRenderDispatcher;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import java.util.List;

/** Detached skeletal layers; native submission never retains the live entity or mutable skeleton. */
public final class NecromancerEntityRenderState extends EntityRenderState {
    public List<NecromancerRenderDispatcher.Draw> skeletalLayers = List.of();
}
