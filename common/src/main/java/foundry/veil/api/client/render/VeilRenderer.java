package foundry.veil.api.client.render;

import com.mojang.blaze3d.platform.Window;
import com.mojang.blaze3d.systems.RenderSystem;
import foundry.veil.Veil;
import foundry.veil.api.client.editor.EditorManager;
import foundry.veil.api.client.render.dynamicbuffer.DynamicBufferType;
import foundry.veil.api.client.render.framebuffer.FramebufferManager;
import foundry.veil.api.client.render.light.renderer.LightRenderer;
import foundry.veil.api.client.render.post.PostPipeline;
import foundry.veil.api.client.render.post.PostProcessingManager;
import foundry.veil.api.client.render.rendertype.VeilRenderType;
import foundry.veil.api.client.render.shader.ShaderManager;
import foundry.veil.api.client.render.shader.ShaderModificationManager;
import foundry.veil.api.client.render.shader.ShaderPreDefinitions;
import foundry.veil.api.client.render.shader.NativeShaderDefaults;
import foundry.veil.api.flare.FlareEffectManager;
import foundry.veil.api.quasar.particle.ParticleSystemManager;
import foundry.veil.impl.client.render.dynamicbuffer.DynamicBufferManager;
import foundry.veil.impl.client.render.dynamicbuffer.VanillaShaderCompiler;
import foundry.veil.impl.client.render.dynamicbuffer.NativeDynamicBuffers;
import foundry.veil.impl.client.render.pipeline.VeilBloomRenderer;
import foundry.veil.impl.client.render.pipeline.VeilFirstPersonRenderer;
import foundry.veil.impl.client.render.rendertype.DynamicRenderTypeManager;
import foundry.veil.impl.client.render.shader.injection.ShaderInjectionManager;
import foundry.veil.impl.client.render.shader.injection.NativeShaderInjectionService;
import foundry.veil.impl.client.render.shader.injection.NativeShaderInjectionFrames;
import foundry.veil.impl.client.render.shader.injection.NativeShaderViews;
import foundry.veil.api.client.render.shader.processor.ShaderInjectProcessor;
import foundry.veil.api.client.render.shader.texture.ShaderTextureSource;
import foundry.veil.api.client.render.texture.TextureFilter;
import foundry.veil.mixin.pipeline.accessor.PipelineReloadableResourceManagerAccessor;
import net.minecraft.ChatFormatting;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.PreparableReloadListener;
import net.minecraft.server.packs.resources.ReloadableResourceManager;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.ResourceManagerReloadListener;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNull;

import java.util.Arrays;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/**
 * Manages the render pipeline for Veil.
 *
 * @author Ocelot
 */
public class VeilRenderer implements ResourceManagerReloadListener {

    public static final Identifier ALBEDO_BUFFER_TEXTURE = Veil.veilPath("dynamic_buffer/albedo");
    public static final Identifier NORMAL_BUFFER_TEXTURE = Veil.veilPath("dynamic_buffer/normal");
    public static final Identifier LIGHT_UV_BUFFER_TEXTURE = Veil.veilPath("dynamic_buffer/light_uv");
    public static final Identifier LIGHT_COLOR_BUFFER_TEXTURE = Veil.veilPath("dynamic_buffer/light_color");
    public static final Identifier DEBUG_BUFFER_TEXTURE = Veil.veilPath("dynamic_buffer/debug");

    public static final Identifier COMPOSITE = Veil.veilPath("core/composite");

    private final VanillaShaderCompiler vanillaShaderCompiler;
    private final DynamicBufferManager dynamicBufferManager;
    private final ShaderModificationManager shaderModificationManager;
    private final ShaderInjectionManager shaderInjectionManager;
    private final ShaderPreDefinitions shaderPreDefinitions;
    private final ShaderManager shaderManager;
    private final NativeShaderInjectionService nativeShaderInjections;
    private final NativeShaderInjectionFrames nativeShaderFrames;
    private final NativeShaderViews.Handler nativeShaderViews;
    private final Supplier<NativeShaderDefaults.Values> nativeShaderDefaults = () -> NativeShaderDefaults.Values.capture(
            System.currentTimeMillis(), direction -> {
                var level = net.minecraft.client.Minecraft.getInstance().level;
                return level == null ? 1.0 : level.cardinalLighting().byFace(direction);
            });
    private final FramebufferManager framebufferManager;
    private final PostProcessingManager postProcessingManager;
    private final DynamicRenderTypeManager dynamicRenderTypeManager;
    private final ParticleSystemManager quasarParticleManager;
    private final FlareEffectManager flareEffectManager;
    private final EditorManager editorManager;
    private final CameraMatrices cameraMatrices;
    private final LightRenderer lightRenderer;
    private final GuiInfo guiInfo;

    @ApiStatus.Internal
    public VeilRenderer(ReloadableResourceManager resourceManager, Window window) {
        this.vanillaShaderCompiler = new VanillaShaderCompiler();
        this.dynamicBufferManager = new DynamicBufferManager(RenderSystem.getDevice(), resourceManager, window.getWidth(), window.getHeight(),
                this.vanillaShaderCompiler, this::dynamicBuffersChanged);
        NativeDynamicBuffers.bindRuntime(this.dynamicBufferManager);
        this.shaderPreDefinitions = new ShaderPreDefinitions();
        foundry.veil.api.client.render.shader.NativeShaderBlocks.bindRuntime(() -> {
            var layouts = new java.util.HashMap<Identifier, VeilShaderBufferLayout<?>>();
            foundry.veil.api.client.registry.VeilShaderBufferRegistry.REGISTRY.entrySet()
                    .forEach(entry -> layouts.put(entry.getKey().identifier(), entry.getValue()));
            return java.util.Map.copyOf(layouts);
        });
        this.shaderModificationManager = new ShaderModificationManager();
        this.shaderInjectionManager = new ShaderInjectionManager();
        this.shaderManager = new ShaderManager(RenderSystem.getDevice(), ShaderManager.PROGRAM_SET, this.shaderPreDefinitions);
        this.shaderManager.addPreprocessorRegistry((resources, registry) -> registry.addPreprocessor(new ShaderInjectProcessor(this.shaderInjectionManager), false));
        this.shaderManager.addPreprocessorRegistry((resources, registry) -> foundry.veil.VeilClient.clientPlatform().onRegisterShaderPreProcessors(resources, registry));
        this.shaderManager.setGlobal(program -> foundry.veil.VeilClient.clientPlatform().onVeilCompileShaders(
                this.shaderManager, java.util.Map.of(program.definition().getLocation(), program)));
        this.framebufferManager = new FramebufferManager();
        this.nativeShaderInjections = new NativeShaderInjectionService(this.shaderInjectionManager, this.shaderManager::getDefinitions, this.shaderPreDefinitions);
        foundry.veil.api.client.render.shader.NativePipelineBridge.bindRuntime(this.shaderPreDefinitions, this.nativeShaderInjections,
                List.of((resources, registry) -> foundry.veil.VeilClient.clientPlatform().onRegisterShaderPreProcessors(resources, registry)));
        this.nativeShaderFrames = new NativeShaderInjectionFrames(this.nativeShaderInjections, RenderSystem.getDevice(), new ShaderTextureSource.Context() {
            @Override public foundry.veil.api.client.render.gpu.GpuFramebuffer getFramebuffer(Identifier name) {
                return VeilRenderer.this.framebufferManager.getFramebuffer(name);
            }
            @Override public TextureFilter framebufferFilter(Identifier name, int attachment, boolean depth) {
                var definition = VeilRenderer.this.framebufferManager.getFramebufferDefinition(name);
                if (definition == null) return TextureFilter.CLAMP;
                if (depth) {
                    if (definition.depthBuffer() == null) throw new IllegalArgumentException("Replacement shader requested a missing framebuffer depth texture: " + name);
                    return definition.depthBuffer().filter();
                }
                if (attachment < 0 || attachment >= definition.colorBuffers().length) throw new IllegalArgumentException("Replacement shader requested a missing framebuffer color attachment: " + name);
                return definition.colorBuffers()[attachment].filter();
            }
        }, VeilRenderSystem::captureShaderBlockSnapshots, foundry.veil.api.client.render.shader.NativeShaderBlocks::layoutNames);
        NativeShaderDefaults.bindRuntime(this.nativeShaderDefaults);
        NativeShaderInjectionFrames.bindRuntime(this.nativeShaderFrames);
        this.postProcessingManager = new PostProcessingManager(RenderSystem.getDevice(), resourceManager, this.shaderManager,
                this.framebufferManager, net.minecraft.util.Util.backgroundExecutor(), this.dynamicBufferManager::getBufferTexture,
                this.dynamicBufferManager::getAvailableBuffers,
                mask -> this.dynamicBufferManager.setActiveBuffers(Identifier.parse("veil:post"), mask));
        this.dynamicRenderTypeManager = new DynamicRenderTypeManager();
        VeilRenderType.bindRuntime(this.shaderManager, this.framebufferManager, net.minecraft.util.Util.backgroundExecutor());
        VeilRenderType.setDynamicManager(this.dynamicRenderTypeManager);
        this.quasarParticleManager = new ParticleSystemManager();
        this.flareEffectManager = new FlareEffectManager();
        this.editorManager = new EditorManager(resourceManager);
        foundry.veil.impl.client.render.profiler.VeilRenderProfilerImpl.bindRuntime(RenderSystem.getDevice(),
                () -> this.editorManager.isVisible(inspector -> inspector instanceof foundry.veil.impl.client.editor.PipelineStatisticsViewer));
        foundry.veil.impl.client.editor.ShaderInspector.bindRuntime(this.shaderManager, this.shaderPreDefinitions, resourceManager);
        this.cameraMatrices = new CameraMatrices();
        this.lightRenderer = new LightRenderer();
        this.guiInfo = new GuiInfo();
        this.nativeShaderViews = this::enterNativeShaderView;
        NativeShaderViews.bindRuntime(this.nativeShaderViews);

        List<PreparableReloadListener> listeners = ((PipelineReloadableResourceManagerAccessor) resourceManager).getListeners();

        // This must finish loading before the game renderer so modifications can apply on load
        listeners.add(0, this.shaderInjectionManager);
        // This must be before vanilla shaders so vanilla shaders can be replaced
        listeners.add(1, this.shaderManager);
        resourceManager.registerReloadListener(this.framebufferManager);
        resourceManager.registerReloadListener(this.postProcessingManager);
        resourceManager.registerReloadListener(this.dynamicRenderTypeManager);
        resourceManager.registerReloadListener(this.flareEffectManager.getShellManager());
        resourceManager.registerReloadListener(this);
    }

    private void dynamicBuffersChanged(foundry.veil.api.client.render.dynamicbuffer.DynamicBuffersChange change) {
        this.shaderManager.setActiveBuffers(change.enabledBuffersMask());
        foundry.veil.VeilClient.clientPlatform().onVeilDynamicBuffersChanged(change);
    }

    @ApiStatus.Internal
    public void addDebugInfo(Consumer<String> consumer) {
        consumer.accept("");
        consumer.accept(ChatFormatting.UNDERLINE + "Veil");

        this.lightRenderer.addDebugInfo(consumer);
        int mask = this.dynamicBufferManager.getActiveBuffers();
        if (mask != 0) {
            String buffers = Arrays.stream(DynamicBufferType.decode(mask)).map(DynamicBufferType::getName).collect(Collectors.joining(", "));
            consumer.accept("Active Buffers: " + buffers);
        }
    }

    /**
     * Enables the specified dynamic render buffers.
     *
     * @param name    The name of the "source" of the buffer change
     * @param buffers The buffers to enable
     * @return Whether any change occurred
     */
    public boolean enableBuffers(Identifier name, DynamicBufferType... buffers) {
        RenderSystem.assertOnRenderThread();
        if (buffers.length == 0) {
            return false;
        }
        int active = this.dynamicBufferManager.getActiveBuffers(name) | DynamicBufferType.encode(buffers);
        return this.dynamicBufferManager.setActiveBuffers(name, active);
    }

    /**
     * Disables the specified dynamic render buffers.
     *
     * @param name    The name of the "source" of the buffer change
     * @param buffers The buffers to disable
     * @return Whether any change occurred
     */
    public boolean disableBuffers(Identifier name, DynamicBufferType... buffers) {
        RenderSystem.assertOnRenderThread();
        if (buffers.length == 0) {
            return false;
        }

        int active = this.dynamicBufferManager.getActiveBuffers(name) & ~DynamicBufferType.encode(buffers);
        return this.dynamicBufferManager.setActiveBuffers(name, active);
    }

    /**
     * Disables the specified dynamic render buffers.
     *
     * @param name The name of the "source" of the buffer change
     * @return Whether any change occurred
     */
    public boolean disableBuffers(Identifier name) {
        RenderSystem.assertOnRenderThread();
        return this.dynamicBufferManager.setActiveBuffers(name, 0);
    }

    /**
     * Retrieves the currently active dynamic buffers.
     *
     * @return The currently active buffers
     * @see DynamicBufferType#decode(int)
     * @since 2.3.0
     */
    public int getActiveBuffers() {
        return this.dynamicBufferManager.getActiveBuffers();
    }

    /**
     * @return The Veil compiler for vanilla shaders
     */
    public VanillaShaderCompiler getVanillaShaderCompiler() {
        return this.vanillaShaderCompiler;
    }

    /**
     * @return The manger for all dynamically added framebuffer attachments
     */
    @ApiStatus.Internal
    public DynamicBufferManager getDynamicBufferManger() {
        return this.dynamicBufferManager;
    }

    /**
     * @return The manager for all custom shader modifications
     */
    @ApiStatus.ScheduledForRemoval(inVersion = "5.0.0")
    @Deprecated(forRemoval = true)
    public ShaderModificationManager getShaderModificationManager() {
        return this.shaderModificationManager;
    }

    /**
     * @return The manager for all shader injections
     */
    @ApiStatus.Internal
    public ShaderInjectionManager getShaderInjectionManager() {
        return this.shaderInjectionManager;
    }

    /**
     * @return The set of shader pre-definitions. Changes are automatically synced the next frame
     */
    public ShaderPreDefinitions getShaderDefinitions() {
        return this.shaderPreDefinitions;
    }

    /**
     * @return The manager for all veil shaders
     */
    public ShaderManager getShaderManager() {
        return this.shaderManager;
    }

    /**
     * @return The manager for all custom veil framebuffers
     */
    public FramebufferManager getFramebufferManager() {
        return this.framebufferManager;
    }

    /**
     * @return The manager for all {@link PostPipeline} instances
     */
    public PostProcessingManager getPostProcessingManager() {
        return this.postProcessingManager;
    }

    /**
     * @return The manager for all data-driven render types
     */
    @ApiStatus.Internal
    public DynamicRenderTypeManager getDynamicRenderTypeManager() {
        return this.dynamicRenderTypeManager;
    }

    /**
     * @return The manager for all quasar particles
     */
    public ParticleSystemManager getParticleManager() {
        return this.quasarParticleManager;
    }

    /**
     * @return The manager for rendering and managing effects
     * @since 2.5.0
     */
    public FlareEffectManager getEffectManager() {
        return this.flareEffectManager;
    }

    /**
     * @return The manager for all editors
     */
    public EditorManager getEditorManager() {
        return this.editorManager;
    }

    /**
     * @return The camera matrices instance
     */
    public CameraMatrices getCameraMatrices() {
        return this.cameraMatrices;
    }

    /**
     * @return The Veil light renderer instance
     */
    public LightRenderer getLightRenderer() {
        return this.lightRenderer;
    }

    /**
     * @return The gui info instance
     */
    public GuiInfo getGuiInfo() {
        return this.guiInfo;
    }

    // Internal Implementation

    @ApiStatus.Internal
    public void resize(int width, int height) {
        this.framebufferManager.setMainFramebuffer(net.minecraft.client.Minecraft.getInstance().gameRenderer.mainRenderTarget());
        this.framebufferManager.resizeFramebuffers(width, height);
        this.dynamicBufferManager.resizeFramebuffers(width, height);

        // The old texture is deleted, so we have to remake the framebuffer
        VeilFirstPersonRenderer.free();
        VeilBloomRenderer.free();
    }

    @ApiStatus.Internal
    public void endFrame() {
        this.framebufferManager.clear();
        this.dynamicBufferManager.endFrame();
        this.postProcessingManager.endFrame();
    }

    private NativeShaderViews.State enterNativeShaderView(NativeShaderViews.View view) {
        CameraMatrices previousCamera = new CameraMatrices();
        this.cameraMatrices.backup(previousCamera);
        GuiInfo.State previousGui = this.guiInfo.snapshot();
        NativeShaderViews.State restore = () -> {
            try { this.cameraMatrices.restore(previousCamera); }
            finally { this.guiInfo.restore(previousGui); }
        };
        try {
            if (view.kind() == NativeShaderViews.Kind.GUI) {
                this.cameraMatrices.updateGui(view.projection());
                this.guiInfo.update(view.guiScale());
            } else {
                this.cameraMatrices.update(view.projection(), view.view(), view.x(), view.y(), view.z());
                this.guiInfo.unbind();
            }
            return restore;
        } catch (RuntimeException | Error failure) {
            try { restore.close(); } catch (RuntimeException | Error cleanup) { failure.addSuppressed(cleanup); }
            throw failure;
        }
    }

    @ApiStatus.Internal
    public void free() {
        VeilLevelPerspectiveRenderer.free();
        VeilRenderType.unbindRuntime();
        this.editorManager.close();
        foundry.veil.impl.client.render.profiler.VeilRenderProfilerImpl.unbindRuntime(RenderSystem.getDevice());
        foundry.veil.impl.client.editor.ShaderInspector.unbindRuntime();
        foundry.veil.api.client.render.shader.NativePipelineBridge.flush();
        NativeShaderViews.unbindRuntime(this.nativeShaderViews);
        NativeShaderDefaults.unbindRuntime(this.nativeShaderDefaults);
        this.nativeShaderFrames.close();
        foundry.veil.api.client.render.shader.NativePipelineBridge.unbindRuntime();
        this.nativeShaderInjections.close();
        foundry.veil.api.client.render.shader.NativeShaderBlocks.unbindRuntime();
        NativeDynamicBuffers.flush(this.dynamicBufferManager);
        NativeDynamicBuffers.unbindRuntime(this.dynamicBufferManager);
        this.dynamicBufferManager.close();
        this.vanillaShaderCompiler.close();
        var retiredVariants = this.vanillaShaderCompiler.retirement();
        net.minecraft.client.Minecraft.getInstance().managedBlock(retiredVariants::isDone);
        retiredVariants.join();
        this.shaderManager.close();
        this.framebufferManager.free();
        this.postProcessingManager.free();
        this.quasarParticleManager.clear();
        this.flareEffectManager.getShellManager().free();
        this.lightRenderer.free();
    }

    @Override
    public void onResourceManagerReload(@NotNull ResourceManager resourceManager) {
        VeilLevelPerspectiveRenderer.free();
        VeilBloomRenderer.tryEnable();
    }
}
