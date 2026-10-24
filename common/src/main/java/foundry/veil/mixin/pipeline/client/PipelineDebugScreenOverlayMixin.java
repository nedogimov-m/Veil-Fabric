package foundry.veil.mixin.pipeline.client;

import foundry.veil.api.client.render.VeilRenderSystem;
import net.minecraft.client.gui.components.DebugScreenOverlay;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import java.util.ArrayList;
import java.util.List;

@Mixin(DebugScreenOverlay.class)
public class PipelineDebugScreenOverlayMixin {
    @ModifyArg(method = "extractRenderState", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/gui/components/DebugScreenOverlay;extractLines(Lnet/minecraft/client/gui/GuiGraphicsExtractor;Ljava/util/List;ZI)V", ordinal = 1), index = 1)
    private List<String> veil$nativeDebugInformation(List<String> original) {
        List<String> lines = new ArrayList<>(original);
        VeilRenderSystem.renderer().addDebugInfo(lines::add);
        return lines;
    }
}
