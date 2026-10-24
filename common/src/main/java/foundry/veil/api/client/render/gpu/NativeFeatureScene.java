package foundry.veil.api.client.render.gpu;

import com.mojang.blaze3d.pipeline.PipelineCache;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.systems.ScissorState;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import foundry.veil.mixin.scene.client.NativeFeatureDispatcherInvoker;
import foundry.veil.impl.client.render.shader.injection.NativeShaderViews;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.ProjectionMatrixBuffer;
import net.minecraft.client.renderer.RenderBuffers;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.SubmitNodeStorage;
import net.minecraft.client.renderer.feature.FeatureFrameContext;
import net.minecraft.client.renderer.feature.FeatureRenderDispatcher;
import net.minecraft.client.renderer.state.GameRenderState;
import net.minecraft.client.renderer.state.OptionsRenderState;
import net.minecraft.server.packs.resources.ResourceManager;
import org.jetbrains.annotations.Nullable;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;

import java.util.Objects;
import java.util.function.Consumer;

/**
 * Executes an extracted feature scene into a caller-owned native target. Terrain meshes may be submitted
 * as custom geometry, but this is not a replay of LevelRenderer's terrain/sky/weather/OIT world graph.
 * All calls, including disposal, belong to the render thread; source/cache owners must be recreated on reload.
 */
public final class NativeFeatureScene implements AutoCloseable {
    private static final ThreadLocal<Scope> CURRENT = new ThreadLocal<>();
    private final Minecraft client;
    private final RenderBuffers buffers;
    private final GameRenderState detachedState = new GameRenderState();
    private final FeatureRenderDispatcher features;
    private final GpuShaderSource source;
    private final PipelineCache pipelines;
    private final ProjectionMatrixBuffer projection;
    private boolean closed;
    private boolean rendering;

    public NativeFeatureScene(Minecraft client, ResourceManager resources) {
        RenderSystem.assertOnRenderThread();
        this.client = Objects.requireNonNull(client);
        Objects.requireNonNull(resources);
        this.buffers = new RenderBuffers(0);
        FeatureRenderDispatcher dispatcher = null;
        GpuShaderSource shaders = null;
        PipelineCache cache = null;
        try {
            dispatcher = new FeatureRenderDispatcher(this.buffers, client.getModelManager(), client.getAtlasManager(), client.font, this.detachedState);
            shaders = new GpuShaderSource(resources);
            cache = new PipelineCache(RenderSystem.getDevice(), shaders);
            this.projection = new ProjectionMatrixBuffer("Veil extracted feature scene projection");
        } catch (RuntimeException | Error failure) {
            if (cache != null) cache.close();
            if (shaders != null) shaders.close();
            if (dispatcher != null) dispatcher.close();
            this.buffers.close();
            throw failure;
        }
        this.features = dispatcher;
        this.source = shaders;
        this.pipelines = cache;
    }

    /** Borrowed resources remain owned by the caller; native RenderSetup selects its real lightmap sampler. */
    public record Inputs(OptionsRenderState options, GpuTextureView lightmap,
                         @Nullable GpuBufferSlice fog, @Nullable GpuBufferSlice lights, @Nullable GpuBuffer globals) {
        public Inputs { Objects.requireNonNull(options); Objects.requireNonNull(lightmap); }
        public static Inputs capture(Minecraft client) {
            return new Inputs(copyOptions(client.gameRenderer.gameRenderState().optionsRenderState), client.gameRenderer.lightmap(),
                    RenderSystem.getShaderFog(), RenderSystem.getShaderLights(), RenderSystem.getGlobalSettingsUniform());
        }
    }

    /**
     * Preserves target color/depth, so several scenes can compose into it. The caller supplies any clearing.
     * Preparation, pass execution and submission finish before return. Matrices/options are copied before callbacks.
     * Requested improved transparency requires a separate real OIT graph and is rejected before any submission.
     */
    public void render(GpuFramebuffer target, Matrix4fc projectionMatrix, com.mojang.blaze3d.ProjectionType projectionType, Matrix4fc modelView,
                       Inputs inputs, Consumer<SubmitNodeCollector> submit) {
        RenderSystem.assertOnRenderThread();
        if (this.closed) throw new IllegalStateException("Feature scene is closed");
        if (this.rendering) throw new IllegalStateException("A feature scene cannot reenter its own prepared frame");
        Objects.requireNonNull(target); Objects.requireNonNull(projectionType); Objects.requireNonNull(inputs); Objects.requireNonNull(submit);
        OptionsRenderState options = copyOptions(inputs.options());
        if (options.improvedTransparency) throw new UnsupportedOperationException("Feature scenes require explicit classic transparency; native world OIT needs its own targets and stages");
        if (target.isClosed() || inputs.lightmap().isClosed()) throw new IllegalStateException("Feature scene requires live borrowed target and lightmap");
        validateUniform(inputs.fog()); validateUniform(inputs.lights());
        if (inputs.globals() != null && (inputs.globals().isClosed() || (inputs.globals().usage() & GpuBuffer.USAGE_UNIFORM) == 0))
            throw new IllegalArgumentException("Global settings must be a live native uniform buffer");
        Matrix4f copiedProjection = new Matrix4f(projectionMatrix);
        Matrix4f copiedView = new Matrix4f(modelView);
        SubmitNodeStorage nodes = new SubmitNodeStorage();
        nodes.setUseImprovedTransparency(false);
        GpuBufferSlice projectionBuffer = this.projection.getBuffer(copiedProjection);
        this.rendering = true;
        try (Scope ignored = new Scope(this.pipelines, projectionBuffer, projectionType, copiedView, inputs);
             var view = NativeShaderViews.beginScene(copiedProjection, copiedView)) {
            submit.accept(nodes);
            FeatureFrameContext context = new FeatureFrameContext(options, this.client.font,
                    this.client.getModelManager().getBlockStateModelSet(), this.client.getBlockColors(),
                    this.client.getTextureManager(), this.client.getAtlasManager(), inputs.lightmap(), this.buffers.stagedVertexBuffer());
            var encoder = RenderSystem.getDevice().createCommandEncoder();
            NativeFeatureDispatcherInvoker dispatcher = (NativeFeatureDispatcherInvoker) this.features;
            FeatureRenderDispatcher.PreparedFrame prepared;
            try { prepared = dispatcher.veil$prepareFrame(context, nodes); }
            catch (RuntimeException | Error failure) {
                // Native preparation opens its singleton frame before executing custom geometry callbacks.
                // Retire that partial frame too, so a bad producer cannot poison this scene's next draw.
                try { dispatcher.veil$preparedFrame().close(); }
                catch (RuntimeException | Error cleanup) { failure.addSuppressed(cleanup); }
                throw failure;
            }
            try (var frame = prepared) {
                try (var pass = target.beginPass(encoder)) { FeatureRenderDispatcher.renderAllFeatures(pass, frame); }
            }
            encoder.submit();
        } finally {
            this.rendering = false;
            this.buffers.endFrame();
        }
    }

    /** Used only by the genuine RenderType.prepare lightmap getter injection. */
    public static @Nullable GpuTextureView scopedLightmap() {
        Scope scope = CURRENT.get(); return scope == null ? null : scope.inputs.lightmap();
    }

    private static void validateUniform(@Nullable GpuBufferSlice buffer) {
        if (buffer != null && (buffer.buffer().isClosed() || (buffer.buffer().usage() & GpuBuffer.USAGE_UNIFORM) == 0))
            throw new IllegalArgumentException("Feature defaults must borrow live native uniform slices");
    }
    @Override public void close() {
        RenderSystem.assertOnRenderThread();
        if (this.closed) return;
        if (this.rendering) throw new IllegalStateException("Cannot dispose a feature scene during its draw");
        this.closed = true;
        this.pipelines.close(); this.source.close(); this.projection.close(); this.features.close(); this.buffers.close();
    }

    private static final class Scope implements AutoCloseable {
        final Scope previous = CURRENT.get();
        final Inputs inputs;
        final GpuBufferSlice projection = RenderSystem.getProjectionMatrixBuffer();
        final com.mojang.blaze3d.ProjectionType projectionType = RenderSystem.getProjectionType();
        final GpuBufferSlice fog = RenderSystem.getShaderFog();
        final GpuBufferSlice lights = RenderSystem.getShaderLights();
        final GpuBuffer globals = RenderSystem.getGlobalSettingsUniform();
        final ScissorState scissor = new ScissorState(RenderSystem.getScissorStateForRenderTypeDraws());
        final PipelineCache previousCache;
        Scope(PipelineCache cache, GpuBufferSlice projection, com.mojang.blaze3d.ProjectionType projectionType, Matrix4f view, Inputs inputs) {
            this.inputs = inputs;
            this.previousCache = RenderSystem.setCurrentPipelineCache(cache);
            RenderSystem.getModelViewStack().pushMatrix().set(view);
            RenderSystem.setProjectionMatrix(projection, projectionType);
            RenderSystem.setShaderFog(inputs.fog()); RenderSystem.setShaderLights(inputs.lights()); RenderSystem.setGlobalSettingsUniform(inputs.globals());
            RenderSystem.disableScissorForRenderTypeDraws();
            CURRENT.set(this);
        }
        @Override public void close() {
            RenderSystem.getModelViewStack().popMatrix();
            RenderSystem.setProjectionMatrix(this.projection, this.projectionType);
            RenderSystem.setShaderFog(this.fog); RenderSystem.setShaderLights(this.lights); RenderSystem.setGlobalSettingsUniform(this.globals);
            RenderSystem.getScissorStateForRenderTypeDraws().setFrom(this.scissor);
            RenderSystem.setCurrentPipelineCache(this.previousCache);
            if (this.previous == null) CURRENT.remove(); else CURRENT.set(this.previous);
        }
    }

    private static OptionsRenderState copyOptions(OptionsRenderState input) {
        Objects.requireNonNull(input);
        OptionsRenderState copy = new OptionsRenderState();
        copy.cloudRange = input.cloudRange; copy.cutoutLeaves = input.cutoutLeaves; copy.improvedTransparency = input.improvedTransparency;
        copy.ambientOcclusion = input.ambientOcclusion; copy.menuBackgroundBlurriness = input.menuBackgroundBlurriness;
        copy.panoramaSpeed = input.panoramaSpeed; copy.maxAnisotropyValue = input.maxAnisotropyValue; copy.textureFiltering = input.textureFiltering;
        copy.bobView = input.bobView; copy.screenEffectScale = input.screenEffectScale; copy.glintSpeed = input.glintSpeed;
        copy.glintStrength = input.glintStrength; copy.damageTiltStrength = input.damageTiltStrength;
        copy.backgroundForChatOnly = input.backgroundForChatOnly; copy.textBackgroundOpacity = input.textBackgroundOpacity;
        copy.cloudStatus = input.cloudStatus; copy.cameraType = input.cameraType; copy.renderDistance = input.renderDistance;
        copy.chunkSectionFadeInTime = input.chunkSectionFadeInTime; copy.prioritizeChunkUpdates = input.prioritizeChunkUpdates; copy.fov = input.fov;
        return copy;
    }
}
