package foundry.veil.api.client.render.rendertype;

import com.mojang.renderpearl.api.commands.CommandEncoder;
import foundry.veil.api.client.render.gpu.GpuFramebuffer;
import org.jetbrains.annotations.Nullable;
import java.util.Objects;
import java.util.function.Consumer;

/** Capture allocates/borrows resources; preparation records commands only after vanilla passes close. */
@FunctionalInterface
public interface NativeRenderTypeOutput {
    /** A null target deliberately suppresses this output layer, e.g. disabled bloom under Iris. */
    @Nullable Target acquire();
    record Target(GpuFramebuffer framebuffer,Consumer<CommandEncoder> prepare,Runnable submitted,AutoCloseable lease) implements AutoCloseable {
        public Target {Objects.requireNonNull(framebuffer);Objects.requireNonNull(prepare);Objects.requireNonNull(submitted);Objects.requireNonNull(lease);}
        @Override public void close() {
            try {lease.close();}catch(RuntimeException|Error failure){throw failure;}catch(Exception failure){throw new IllegalStateException("Cannot release native output lease",failure);}
        }
    }
}
