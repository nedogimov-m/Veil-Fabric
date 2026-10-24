package foundry.veil.fabric.mixin.compat.sodium;

import foundry.veil.ext.NativePerspectiveLevelExtension;
import net.minecraft.client.renderer.LevelRenderer;
import org.spongepowered.asm.mixin.Mixin;

/** Sodium owns an asynchronous resident terrain graph; the vanilla ViewArea borrower cannot represent it. */
@Mixin(LevelRenderer.class)
public abstract class NativeSodiumPerspectiveGateMixin implements NativePerspectiveLevelExtension {
    @Override public String veil$unsupportedPerspectiveBackend() {
        return "Independent level perspectives are unavailable with Sodium 0.9.2: its native terrain graph has no independent view adapter";
    }
}
