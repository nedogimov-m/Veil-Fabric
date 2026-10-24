package foundry.veil.mixin.scene.client;

import net.minecraft.client.renderer.SubmitNodeStorage;
import net.minecraft.client.renderer.feature.FeatureFrameContext;
import net.minecraft.client.renderer.feature.FeatureRenderDispatcher;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;
import org.spongepowered.asm.mixin.gen.Accessor;

/** The native private prepare method accepts an actual explicit resource/options context. */
@Mixin(FeatureRenderDispatcher.class)
public interface NativeFeatureDispatcherInvoker {
    @Invoker("prepareFrameWithContext")
    FeatureRenderDispatcher.PreparedFrame veil$prepareFrame(FeatureFrameContext context, SubmitNodeStorage storage);

    @Accessor("preparedFrame")
    FeatureRenderDispatcher.PreparedFrame veil$preparedFrame();
}
