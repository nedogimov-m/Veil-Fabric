package foundry.veil.api.client.render.light.renderer;

import com.mojang.blaze3d.systems.RenderSystem;
import foundry.veil.Veil;
import foundry.veil.api.client.registry.LightTypeRegistry;
import foundry.veil.api.client.render.CullFrustum;
import foundry.veil.api.client.render.VeilRenderSystem;
import foundry.veil.api.client.render.VeilRenderer;
import foundry.veil.api.client.render.dynamicbuffer.DynamicBufferType;
import foundry.veil.api.client.render.gpu.GpuFramebuffer;
import foundry.veil.api.client.render.gpu.GpuShaderProgram;
import foundry.veil.api.client.render.gpu.NativeLightPass;
import foundry.veil.api.client.render.texture.TextureFilter;
import foundry.veil.impl.client.render.pipeline.VeilShaderBlockState;
import com.mojang.renderpearl.api.commands.CommandEncoder;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import com.mojang.renderpearl.api.pipeline.PrimitiveTopology;
import com.mojang.renderpearl.api.vertex.VertexFormat;
import com.mojang.renderpearl.api.GpuFormat;
import org.jetbrains.annotations.Nullable;
import org.joml.Vector4f;
import java.util.List;
import java.util.HashMap;
import foundry.veil.api.client.render.light.data.LightData;
import foundry.veil.api.client.render.vertex.VertexArray;
import foundry.veil.impl.client.render.light.VoxelShadowGrid;
import it.unimi.dsi.fastutil.objects.Object2ObjectArrayMap;
import net.minecraft.resources.Identifier;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.UnmodifiableView;
import org.lwjgl.system.NativeResource;

import java.util.Collection;
import java.util.Collections;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;



/**
 * Renders all lights in a scene.
 * <br>
 * There is no way to retrieve a light, so care should be taken to keep track of what lights
 * have been added to the scene and when they should be removed.
 *
 * @author Ocelot
 */
public final class LightRenderer implements NativeResource {

    private static final Identifier BUFFER_ID = Veil.veilPath("lights");
    private final Map<LightTypeRegistry.LightType<?>, LightTypeRenderer<?>> renderers;
    private final Map<LightTypeRegistry.LightType<?>, LightTypeRenderer<?>> renderersView;

    /**
     * Creates a new light renderer.
     */
    public LightRenderer() {
        this.renderers = new Object2ObjectArrayMap<>();
        this.renderersView = Collections.unmodifiableMap(this.renderers);
    }

    /**
     * Draws the lights to the specified framebuffer.
     *
     * @param lightFbo The framebuffer to render lights into
     * @return If any lights were actually rendered
     */
    private final Map<DrawState, RenderPipeline> pipelines = new HashMap<>();
    private @Nullable GpuFramebuffer activeTarget;
    private @Nullable GpuFramebuffer activeSource;

    @ApiStatus.Internal
    public boolean render(CullFrustum frustum, GpuFramebuffer target) {
        return this.render(frustum, target, VeilRenderSystem.getMainFramebuffer());
    }

    /** Uses the depth belonging to this scene, including an independent first-person scene. */
    @ApiStatus.Internal
    public boolean render(CullFrustum frustum, GpuFramebuffer target, GpuFramebuffer sceneSource) {
        if (this.activeTarget != null) throw new IllegalStateException("Light preparation cannot nest");
        Objects.requireNonNull(sceneSource, "Light scene source");
        VeilRenderer owner = VeilRenderSystem.renderer();
        boolean visible = false;
        for (var renderer : this.renderers.values()) {
            renderer.prepareLights(this, frustum);
            visible |= renderer.getVisibleLights() > 0;
        }
        if (!visible) { owner.disableBuffers(BUFFER_ID); return false; }
        int required = DynamicBufferType.encode(DynamicBufferType.ALBEDO, DynamicBufferType.NORMAL);
        boolean changed = owner.enableBuffers(BUFFER_ID, DynamicBufferType.ALBEDO, DynamicBufferType.NORMAL);
        if (changed || (owner.getDynamicBufferManger().getAvailableBuffers() & required) != required) return false;
        var encoder = RenderSystem.getDevice().createCommandEncoder();
        target.clear(encoder, new Vector4f(0), 0);
        if (target.hasDepth()) sceneSource.copyDepthTo(encoder, target);
        encoder.submit();
        this.activeTarget = target;
        this.activeSource = sceneSource;
        try {
            boolean gridPrepared = false;
            for (var renderer : this.renderers.values()) {
                if (renderer.getVisibleLights() == 0) continue;
                if (renderer instanceof DDALightRenderer<?> dda) {
                    if (!gridPrepared) { VoxelShadowGrid.setup(); gridPrepared = true; }
                    dda.uploadVoxelGridUniforms(VoxelShadowGrid.getTextureView(), VoxelShadowGrid.getSampler(), VoxelShadowGrid.getUniformGridPos());
                }
                renderer.renderLights(this);
            }
            return true;
        } finally { this.activeSource = null; this.activeTarget = null; }
    }

    /** Draw one native instanced light batch or fullscreen directional light and submit it. */
    public void draw(CommandEncoder encoder, Identifier shader, @Nullable VertexArray mesh, int instances,
                     boolean volume, Consumer<GpuShaderProgram> uniforms) {
        GpuFramebuffer target = Objects.requireNonNull(this.activeTarget, "Light draw outside its target scope");
        Map<Integer, VertexFormat> formats = mesh == null ? Map.of() : mesh.formats();
        PrimitiveTopology topology = mesh == null ? PrimitiveTopology.TRIANGLES : mesh.getDrawMode();
        var key = new DrawState(shader, formats, topology, target.color(0).texture().getFormat(), volume);
        var definition = this.pipelines.computeIfAbsent(key, ignored -> NativeLightPass.pipeline(shader, formats, topology, target, volume));
        var owner = VeilRenderSystem.renderer();
        var program = owner.getShaderManager().getShader(shader, definition, Runnable::run);
        var buffers = owner.getDynamicBufferManger();
        program.setTexture("AlbedoSampler", buffers.getBufferTexture(DynamicBufferType.ALBEDO), TextureFilter.CLAMP);
        program.setTexture("NormalSampler", buffers.getBufferTexture(DynamicBufferType.NORMAL), TextureFilter.CLAMP);
        if (volume) program.setTexture("DepthSampler", Objects.requireNonNull(this.activeSource, "Light draw outside its scene scope").depth().view(), TextureFilter.CLAMP);
        program.getUniformSafe("ScreenSize").setVector((float)target.width(), (float)target.height());
        uniforms.accept(program);
        var blocks = VeilShaderBlockState.prepareSnapshots(encoder, VeilRenderSystem.captureShaderBlockSnapshots(program.uniformBlockSizes().keySet()));
        try (var lease = program.retain(); var draw = mesh == null ? null : mesh.retain()) {
            NativeLightPass.record(encoder, target, program, draw, instances, blocks);
            encoder.submit();
        }
    }
    private record DrawState(Identifier shader, Map<Integer, VertexFormat> formats, PrimitiveTopology topology, GpuFormat color, boolean volume) { }

    /**
     * Adds a light to the renderer.
     *
     * @param lightData The light to add
     */
    @SuppressWarnings("unchecked")
    public <T extends LightData> LightRenderHandle<T> addLight(T lightData) {
        Objects.requireNonNull(lightData, "light");
        RenderSystem.assertOnRenderThread();
        return ((LightTypeRenderer<T>) this.renderers.computeIfAbsent(lightData.getType(), lightType -> lightType.rendererFactory().createRenderer())).addLight(lightData);
    }

    /**
     * Attempts to re-add the specified light handle to the renderer.
     *
     * @param handle The handle of the light to add
     * @return The same handle or a new one if re-added
     */
    @SuppressWarnings("unchecked")
    public <T extends LightData> LightRenderHandle<T> addLight(LightRenderHandle<T> handle) {
        Objects.requireNonNull(handle, "light");
        RenderSystem.assertOnRenderThread();
        return ((LightTypeRenderer<T>) this.renderers.computeIfAbsent(handle.getLightData().getType(), lightType -> lightType.rendererFactory().createRenderer())).steal(handle);
    }

    /**
     * Retrieves all lights of the specified type.
     *
     * @param type The type of lights to get
     * @return A list of lights for the specified type in the scene
     */
    @SuppressWarnings("unchecked")
    public <T extends LightData> Collection<? extends LightRenderHandle<T>> getLights(LightTypeRegistry.LightType<? extends T> type) {
        LightTypeRenderer<?> renderer = this.renderers.get(type);
        return renderer != null ? (Collection<? extends LightRenderHandle<T>>) renderer.getLights() : Collections.emptyList();
    }

    /**
     * @return A view of all existing light renderers
     * @since 3.3.0
     */
    @UnmodifiableView
    public Map<LightTypeRegistry.LightType<?>, LightTypeRenderer<?>> getRenderers() {
        return this.renderersView;
    }

    @Override
    public void free() {
        this.renderers.values().forEach(LightTypeRenderer::free);
        this.renderers.clear();
        var owner = VeilRenderSystem.renderer().getShaderManager();
        this.pipelines.forEach((state, pipeline) -> owner.releaseShader(state.shader, pipeline));
        this.pipelines.clear();
    }

    @ApiStatus.Internal
    public void addDebugInfo(Consumer<String> consumer) {
        int visible = 0;
        int all = 0;
        for (LightTypeRenderer<?> renderer : this.renderers.values()) {
            visible += renderer.getVisibleLights();
            all += renderer.getLights().size();
        }
        consumer.accept("Lights: " + visible + " / " + all);
    }
}
