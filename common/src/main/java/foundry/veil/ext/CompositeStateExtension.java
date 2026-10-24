package foundry.veil.ext;

import foundry.veil.api.client.render.rendertype.VeilRenderTypeBuilder;
import java.util.Collection;
import java.util.function.UnaryOperator;

/** Typed native replacement for adding executable global-state shards. */
public interface CompositeStateExtension {
    void veil$addShards(Collection<UnaryOperator<VeilRenderTypeBuilder>> transforms);
}
