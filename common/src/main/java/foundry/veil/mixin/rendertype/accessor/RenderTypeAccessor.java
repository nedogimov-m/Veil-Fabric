package foundry.veil.mixin.rendertype.accessor;

import net.minecraft.client.renderer.rendertype.RenderSetup;
import net.minecraft.client.renderer.rendertype.RenderType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(RenderType.class)
public interface RenderTypeAccessor {
    @Accessor("state") RenderSetup veil$getState();
    @Accessor("name") String veil$getName();
    @Mutable @Accessor("state") void veil$setState(RenderSetup setup);
    @Mutable @Accessor("hasBlending") void veil$setHasBlending(boolean blending);
    @Mutable @Accessor("outline") void veil$setOutline(java.util.Optional<RenderType> outline);
    @Invoker("writeDynamicTransforms")
    com.mojang.renderpearl.api.buffers.GpuBufferSlice veil$writeDynamicTransforms(org.joml.Matrix4f modelView);
    @Invoker("create")
    static RenderType veil$create(String name, RenderSetup setup) { throw new AssertionError("Mixin invoker was not transformed"); }
}
