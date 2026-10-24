package foundry.veil.mixin.rendertype.accessor;

import com.mojang.renderpearl.api.textures.GpuSampler;
import net.minecraft.resources.Identifier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import java.util.function.Supplier;

@Mixin(targets = "net.minecraft.client.renderer.rendertype.RenderSetup$TextureBinding")
public interface TextureBindingAccessor {
    @Accessor("location") Identifier veil$getLocation();
    @Accessor("sampler") Supplier<GpuSampler> veil$getSampler();
}
