package foundry.veil.api.client.render.post.stage;

import foundry.veil.api.client.render.post.PostPipeline;
import net.minecraft.resources.Identifier;
import org.jetbrains.annotations.Nullable;

/** Shared resource target selection; every operation receives an explicit encoder. */
public abstract class FramebufferPostStage implements PostPipeline {
    @Nullable private final Identifier in;
    private final Identifier out;
    private final boolean clear;
    protected FramebufferPostStage(@Nullable Identifier in, Identifier out, boolean clear) {
        this.in = in; this.out = java.util.Objects.requireNonNull(out); this.clear = clear;
    }
    public @Nullable Identifier getIn() { return this.in; }
    public Identifier getOut() { return this.out; }
    public boolean clearOut() { return this.clear; }
}
