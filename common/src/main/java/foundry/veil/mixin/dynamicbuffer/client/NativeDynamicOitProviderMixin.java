package foundry.veil.mixin.dynamicbuffer.client;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.mojang.renderpearl.api.commands.RenderPass;
import foundry.veil.impl.client.render.dynamicbuffer.NativeDynamicBuffers;
import net.minecraft.client.renderer.oit.OitRenderPassProvider;
import net.minecraft.client.renderer.oit.OitStage;
import org.spongepowered.asm.mixin.Mixin;

import java.util.function.Supplier;

@Mixin(OitRenderPassProvider.class)
public class NativeDynamicOitProviderMixin {
    @WrapMethod(method = "createRenderPass")
    private static RenderPass veil$stage(OitStage stage, Supplier<String> label, OitRenderPassProvider.Parameters parameters,
                                         Operation<RenderPass> original) {
        try (var ignored = NativeDynamicBuffers.beginOit(stage)) { return original.call(stage, label, parameters); }
    }
}
