package foundry.veil.mixin.perspective.client;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.*;
import com.mojang.blaze3d.resource.GraphicsResourceAllocator;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import foundry.veil.ext.NativePerspectiveLevelExtension;
import foundry.veil.mixin.scene.client.NativeFeatureDispatcherInvoker;
import net.minecraft.client.renderer.*;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher;
import net.minecraft.client.renderer.feature.FeatureRenderDispatcher;
import net.minecraft.client.renderer.state.GameRenderState;
import net.minecraft.client.renderer.state.OptionsRenderState;
import net.minecraft.client.renderer.state.level.*;
import org.joml.Matrix4fStack;
import org.joml.Vector4f;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(LevelRenderer.class)
public abstract class NativePerspectiveLevelMixin implements NativePerspectiveLevelExtension {
    @Shadow @Final @Mutable private LevelRenderState levelRenderState;
    @Shadow @Final @Mutable private OptionsRenderState optionsRenderState;
    @Shadow @Final @Mutable private RenderBuffers renderBuffers;
    @Shadow @Final @Mutable private FeatureRenderDispatcher featureRenderDispatcher;
    @Shadow private ViewArea viewArea;
    @Shadow private SectionRenderDispatcher sectionRenderDispatcher;
    @Shadow @Final private LevelTargetBundle targets;
    @Unique private boolean veil$perspectiveOwner;
    @Unique private boolean veil$viewPushed;

    @Override public void veil$configurePerspective(GameRenderState state, RenderBuffers buffers, FeatureRenderDispatcher features) {
        this.veil$perspectiveOwner = true; this.levelRenderState = state.levelRenderState;
        this.optionsRenderState = state.optionsRenderState; this.renderBuffers = buffers; this.featureRenderDispatcher = features;
    }
    @Override public void veil$borrowPerspectiveTerrain(LevelRenderer source) {
        this.viewArea = source.viewArea(); this.sectionRenderDispatcher = source.sectionRenderDispatcher();
    }
    @Override public void veil$detachPerspectiveTerrain() { this.viewArea = null; this.sectionRenderDispatcher = null; }
    @Inject(method = {"repositionCamera", "compileSections"}, at = @At("HEAD"), cancellable = true)
    private void veil$preserveBorrowedTerrain(CameraRenderState camera, CallbackInfo ci) { if (this.veil$perspectiveOwner) ci.cancel(); }
    @WrapOperation(method = "render", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/SectionOcclusionGraph;update(Lnet/minecraft/client/renderer/state/level/CameraRenderState;ILnet/minecraft/client/renderer/state/level/ChunkLoadingRenderState;)V"))
    private void veil$preserveTerrainGraph(SectionOcclusionGraph graph, CameraRenderState camera, int fov, ChunkLoadingRenderState chunks, Operation<Void> original) {
        if (!this.veil$perspectiveOwner) original.call(graph, camera, fov, chunks);
    }
    @WrapOperation(method = "render", at = @At(value = "INVOKE", target = "Lorg/joml/Matrix4fStack;pushMatrix()Lorg/joml/Matrix4fStack;"))
    private Matrix4fStack veil$push(Matrix4fStack stack, Operation<Matrix4fStack> original) {
        var result = original.call(stack); if (this.veil$perspectiveOwner) this.veil$viewPushed = true; return result;
    }
    @WrapOperation(method = "render", at = @At(value = "INVOKE", target = "Lorg/joml/Matrix4fStack;popMatrix()Lorg/joml/Matrix4fStack;"))
    private Matrix4fStack veil$pop(Matrix4fStack stack, Operation<Matrix4fStack> original) {
        var result = original.call(stack); if (this.veil$perspectiveOwner) this.veil$viewPushed = false; return result;
    }
    @WrapMethod(method = "render")
    private void veil$independentFrame(GraphicsResourceAllocator allocator, boolean outlines, CameraRenderState camera, GpuBufferSlice fog,
            Vector4f fogColor, boolean sky, boolean clouds, Operation<Void> original) {
        if (!this.veil$perspectiveOwner) { original.call(allocator, outlines, camera, fog, fogColor, sky, clouds); return; }
        boolean renderingLevel = RenderSystem.isRenderingLevel;
        try { original.call(allocator, outlines, camera, fog, fogColor, sky, clouds); }
        catch (RuntimeException | Error failure) {
            try { ((NativeFeatureDispatcherInvoker) this.featureRenderDispatcher).veil$preparedFrame().close(); }
            catch (RuntimeException | Error cleanup) { failure.addSuppressed(cleanup); }
            throw failure;
        } finally {
            this.targets.clear();
            if (this.veil$viewPushed) { RenderSystem.getModelViewStack().popMatrix(); this.veil$viewPushed = false; }
            RenderSystem.isRenderingLevel = renderingLevel;
        }
    }
}
