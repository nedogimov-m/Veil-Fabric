package foundry.veil.api.client.render.gpu;

import com.mojang.blaze3d.ProjectionType;
import com.mojang.blaze3d.resource.CrossFrameResourcePool;
import com.mojang.blaze3d.systems.RenderSystem;
import foundry.veil.ext.NativePerspectiveLevelExtension;
import foundry.veil.ext.RenderTargetExtension;
import foundry.veil.impl.client.render.dynamicbuffer.NativeDynamicBuffers;
import foundry.veil.impl.client.render.perspective.LevelPerspectiveCamera;
import foundry.veil.impl.client.render.perspective.VeilSectionOcclusionGraph;
import foundry.veil.impl.client.render.shader.injection.NativeShaderViews;
import foundry.veil.mixin.perspective.accessor.NativePerspectiveExtractorInvoker;
import foundry.veil.mixin.perspective.accessor.NativePerspectiveBlockEntityAccessor;
import foundry.veil.mixin.perspective.accessor.NativePerspectiveTerrainAccessor;
import net.minecraft.client.Minecraft;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.renderer.*;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.client.renderer.extract.LevelExtractor;
import net.minecraft.client.renderer.feature.FeatureRenderDispatcher;
import net.minecraft.client.renderer.fog.FogRenderer;
import net.minecraft.client.renderer.state.GameRenderState;
import net.minecraft.client.renderer.state.OptionsRenderState;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.core.SectionPos;
import net.minecraft.util.ARGB;
import net.minecraft.world.attribute.EnvironmentAttributes;
import net.minecraft.world.entity.Entity;
import org.jetbrains.annotations.Nullable;
import org.joml.*;

import java.util.Objects;

/**
 * A genuine independent LevelRenderer graph over the client's already compiled terrain.
 * It re-extracts native entities, block entities, sky, weather, border and particles for the explicit camera.
 * It does not load or compile chunks outside the primary renderer's resident terrain cache.
 */
public final class NativeLevelPerspective implements AutoCloseable {
    private final Minecraft client;
    private final GameRenderState state = new GameRenderState();
    private final RenderBuffers buffers = new RenderBuffers(0);
    private final LevelRenderer renderer;
    private final FeatureRenderDispatcher features;
    private final LevelExtractor extractor;
    private final LevelPerspectiveCamera camera = new LevelPerspectiveCamera();
    private final VeilSectionOcclusionGraph visibility = new VeilSectionOcclusionGraph();
    private final ProjectionMatrixBuffer projection = new ProjectionMatrixBuffer("Veil native perspective projection");
    private final FogRenderer fog = new FogRenderer();
    private final CrossFrameResourcePool resources = new CrossFrameResourcePool(3);
    private boolean rendering, closed;
    private static final ThreadLocal<NativeLevelPerspective> CURRENT = new ThreadLocal<>();

    public NativeLevelPerspective(Minecraft client) {
        this(supportedClient(client), true);
    }
    private static Minecraft supportedClient(Minecraft client) {
        RenderSystem.assertOnRenderThread(); Objects.requireNonNull(client);
        requireSupportedBackend(client.levelRenderer);
        return client;
    }
    private NativeLevelPerspective(Minecraft client, boolean supported) {
        this.client = client;
        var target = client.gameRenderer.mainRenderTarget();
        this.renderer = new LevelRenderer(client.getEntityRenderDispatcher(), client.getBlockEntityRenderDispatcher(), client.getModelManager(),
                client.getTextureManager(), client.getAtlasManager(), client.getShaderManager(), client.gameRenderer, target.width, target.height);
        this.features = new FeatureRenderDispatcher(this.buffers, client.getModelManager(), client.getAtlasManager(), client.font, this.state);
        ((NativePerspectiveLevelExtension) this.renderer).veil$configurePerspective(this.state, this.buffers, this.features);
        this.extractor = new LevelExtractor(client, this.state.levelRenderState, this.renderer);
    }
    public static boolean isRendering() { return CURRENT.get() != null; }
    public static void requireSupportedBackend(LevelRenderer renderer) {
        String unsupported = ((NativePerspectiveLevelExtension) renderer).veil$unsupportedPerspectiveBackend();
        if (unsupported != null) throw new UnsupportedOperationException(unsupported);
    }
    public LevelRenderer renderer() { return this.renderer; }
    public CameraRenderState cameraState() { return this.state.levelRenderState.cameraRenderState; }

    /** Caller matrices already use the actual native backend's clip range and reverse Z. */
    public void render(GpuFramebuffer target, @Nullable Entity entity, Matrix4fc modelView, Matrix4fc projection,
            Vector3dc position, Quaternionfc orientation, float distance, DeltaTracker delta, Runnable complete) {
        RenderSystem.assertOnRenderThread();
        requireSupportedBackend(this.client.levelRenderer);
        if (this.closed || this.rendering) throw new IllegalStateException("Native perspective is closed or already rendering");
        Objects.requireNonNull(target); Objects.requireNonNull(delta); Objects.requireNonNull(complete);
        if (this.client.level == null || this.client.levelRenderer.viewArea() == null) throw new IllegalStateException("Native perspective requires a loaded compiled client world");
        if (!(distance > 0) || !Float.isFinite(distance)) throw new IllegalArgumentException("Perspective view distance must be finite and positive");
        if (target.isClosed() || target.colorCount() != 1 || !target.hasDepth()) throw new IllegalArgumentException("Native world perspective requires one live color and depth target");
        if (target.color(0).texture().getFormat() != this.client.gameRenderer.mainRenderTarget().getColorTexture().getFormat())
            throw new IllegalArgumentException("Native world perspective color format must match the current vanilla world pipelines");
        var copiedProjection = new Matrix4f(projection); var copiedView = new Matrix4f(modelView);
        if (!copiedProjection.isFinite() || !copiedView.isFinite() || !position.isFinite() || !orientation.isFinite())
            throw new IllegalArgumentException("Native perspective requires finite camera matrices and position/orientation");
        this.camera.setup(new Vector3d(position), entity, this.client.level, new Quaternionf(orientation), distance,
                copiedView, copiedProjection, target.width(), target.height());
        var oldEntityCamera = this.client.getEntityRenderDispatcher().camera;
        var oldPicked = this.client.getEntityRenderDispatcher().crosshairPickEntity;
        var oldBlockCamera = ((NativePerspectiveBlockEntityAccessor) this.client.getBlockEntityRenderDispatcher()).veil$perspectiveCameraPos();
        var terrain = this.client.levelRenderer.sectionRenderDispatcher();
        var oldTerrainCamera = ((NativePerspectiveTerrainAccessor) terrain).veil$perspectiveCameraPosition().get();
        double oldViewScale = Entity.getViewScale();
        var oldProjection = RenderSystem.getProjectionMatrixBuffer(); var oldProjectionType = RenderSystem.getProjectionType();
        var oldFog = RenderSystem.getShaderFog(); var oldView = RenderSystem.getModelViewMatrixCopy();
        var extension = (RenderTargetExtension) this.client.gameRenderer.mainRenderTarget();
        var oldTarget = extension.veil$getWrapper();
        var previous = CURRENT.get(); this.rendering = true; CURRENT.set(this);
        var owner = (NativePerspectiveLevelExtension) this.renderer;
        try {
            owner.veil$borrowPerspectiveTerrain(this.client.levelRenderer);
            terrain.setCameraPosition(this.camera.position());
            this.extract(distance, delta);
            this.renderer.resize(target.width(), target.height());
            extension.veil$setWrapper(target);
            RenderSystem.setProjectionMatrix(this.projection.getBuffer(copiedProjection), ProjectionType.PERSPECTIVE);
            this.fog.updateBuffer(this.state.levelRenderState.cameraRenderState.fogData);
            var fogBuffer = this.fog.getBuffer(FogRenderer.FogMode.WORLD);
            RenderSystem.setShaderFog(fogBuffer);
            RenderSystem.getModelViewStack().identity();
            try (var view = NativeShaderViews.beginWorld(this.state.levelRenderState.cameraRenderState);
                 var dynamic = NativeDynamicBuffers.beginIndependentWorld(target.color(0).view(), target.depth().view())) {
                var camera = this.state.levelRenderState.cameraRenderState;
                this.renderer.render(this.resources, false, camera, fogBuffer, camera.fogData.color, true, false);
                this.renderer.blitEntityOutline();
                complete.run();
                RenderSystem.getDevice().createCommandEncoder().submit();
            }
        } finally {
            owner.veil$detachPerspectiveTerrain();
            this.renderer.visibleSections().clear(); this.renderer.nearbyVisibleSections().clear();
            extension.veil$setWrapper(oldTarget);
            RenderSystem.setProjectionMatrix(oldProjection, oldProjectionType); RenderSystem.setShaderFog(oldFog);
            RenderSystem.getModelViewStack().set(oldView);
            this.client.getEntityRenderDispatcher().prepare(oldEntityCamera, oldPicked);
            this.client.getBlockEntityRenderDispatcher().prepare(oldBlockCamera);
            terrain.setCameraPosition(oldTerrainCamera);
            Entity.setViewScale(oldViewScale);
            this.buffers.endFrame(); this.renderer.endFrame(); this.fog.endFrame(); this.resources.endFrame();
            this.rendering = false; if (previous == null) CURRENT.remove(); else CURRENT.set(previous);
        }
    }
    private void extract(float distance, DeltaTracker delta) {
        var level = this.client.level; var output = this.state.levelRenderState;
        output.reset(); copyOptions(this.client.gameRenderer.gameRenderState().optionsRenderState, this.state.optionsRenderState);
        this.state.optionsRenderState.renderDistance = (int) java.lang.Math.ceil(distance);
        float partial = delta.getGameTimeDeltaPartialTick(false);
        output.gameTime = level.getGameTime(); output.worldPartialTicks = partial;
        this.camera.extractRenderState(output.cameraRenderState, delta);
        output.cameraRenderState.fogData = this.fog.setupFog(this.camera, (int) java.lang.Math.ceil(distance), delta,
                this.client.gameRenderer.bossOverlayWorldDarkening(partial), level);
        output.shouldResetSkyRenderer = this.renderer.skyRenderer() == null; output.shouldResetChunkLayerSampler = true;
        output.shouldUseMultiDrawIndirectForTerrain = this.client.multiDrawIndirect;
        var frustum = this.camera.getCullFrustum();
        this.visibility.update(this.renderer.viewArea(), false, this.camera, frustum, this.renderer.visibleSections());
        for (var section : this.renderer.visibleSections()) {
            if (SectionPos.of(this.camera.position()).closerThan(SectionPos.of(section.getRenderOrigin()), 3)) this.renderer.nearbyVisibleSections().add(section);
        }
        this.client.getEntityRenderDispatcher().prepare(this.camera, null);
        this.client.getBlockEntityRenderDispatcher().prepare(this.camera.position());
        var extract = (NativePerspectiveExtractorInvoker) this.extractor;
        extract.veil$perspectiveLevel(level);
        extract.veil$perspectivePlayer(this.camera, delta, partial, output.playerRenderState);
        extract.veil$perspectiveEntities(this.camera, frustum, delta, output);
        extract.veil$perspectiveBlockEntities(this.camera, partial, output);
        extract.veil$perspectiveDestruction(this.camera, output);
        if (this.renderer.skyRenderer() != null) this.renderer.skyRenderer().extractRenderState(level, partial, this.camera, output.skyRenderState);
        else this.client.levelRenderer.skyRenderer().extractRenderState(level, partial, this.camera, output.skyRenderState);
        this.renderer.weatherEffectRenderer().extractRenderState(level, partial, this.camera.position(), output.weatherRenderState);
        this.renderer.worldBorderRenderer().extract(level.getWorldBorder(), partial, this.camera.position(), distance * 16, output.worldBorderRenderState);
        this.client.particleEngine.extract(output.particlesRenderState, new Frustum(frustum).offset(-3), this.camera, partial);
        output.cloudColor = ARGB.colorFromVector4f(this.camera.attributeProbe().getValue(EnvironmentAttributes.CLOUD_COLOR, partial));
        output.cloudHeight = this.camera.attributeProbe().getValue(EnvironmentAttributes.CLOUD_HEIGHT, partial);
    }
    private static void copyOptions(OptionsRenderState from, OptionsRenderState to) {
        to.cloudRange = from.cloudRange; to.cutoutLeaves = from.cutoutLeaves; to.improvedTransparency = from.improvedTransparency;
        to.ambientOcclusion = from.ambientOcclusion; to.menuBackgroundBlurriness = from.menuBackgroundBlurriness;
        to.panoramaSpeed = from.panoramaSpeed; to.maxAnisotropyValue = from.maxAnisotropyValue; to.textureFiltering = from.textureFiltering;
        to.bobView = from.bobView; to.screenEffectScale = from.screenEffectScale; to.glintSpeed = from.glintSpeed;
        to.glintStrength = from.glintStrength; to.damageTiltStrength = from.damageTiltStrength;
        to.backgroundForChatOnly = from.backgroundForChatOnly; to.textBackgroundOpacity = from.textBackgroundOpacity;
        to.cloudStatus = from.cloudStatus; to.cameraType = from.cameraType; to.renderDistance = from.renderDistance;
        to.chunkSectionFadeInTime = from.chunkSectionFadeInTime; to.prioritizeChunkUpdates = from.prioritizeChunkUpdates; to.fov = from.fov;
    }
    @Override public void close() {
        RenderSystem.assertOnRenderThread(); if (this.closed) return;
        if (this.rendering) throw new IllegalStateException("Cannot close an active native perspective");
        this.closed = true; ((NativePerspectiveLevelExtension) this.renderer).veil$detachPerspectiveTerrain();
        this.renderer.close(); this.features.close(); this.buffers.close(); this.projection.close(); this.fog.close(); this.resources.close();
    }
}
