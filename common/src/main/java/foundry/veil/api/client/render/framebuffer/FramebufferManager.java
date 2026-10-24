package foundry.veil.api.client.render.framebuffer;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.device.GpuDevice;
import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import foundry.veil.api.CodecReloadListener;
import foundry.veil.api.client.render.gpu.GpuFramebuffer;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.FileToIdConverter;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.util.profiling.ProfilerFiller;
import org.jetbrains.annotations.Nullable;
import org.joml.Vector4f;
import org.lwjgl.system.NativeResource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Resource-driven native GPU framebuffer registry. Allocation/reload/resize is
 * transactional: a failed replacement never destroys the last working target.
 * Manually supplied targets stay caller-owned; a borrowed main target never owns
 * Minecraft's GPU textures. All methods execute on the owning render thread.
 */
public class FramebufferManager extends CodecReloadListener<FramebufferDefinition> implements NativeResource {
    private static final Logger LOGGER = LoggerFactory.getLogger("Veil");
    private static final Identifier MAIN = Identifier.withDefaultNamespace("main");
    public static final Codec<Identifier> FRAMEBUFFER_CODEC = Codec.STRING.comapFlatMap(name -> {
        Identifier id = Identifier.tryParse(name.contains(":") ? name : "temp:" + name);
        return id != null ? DataResult.success(id) : DataResult.error(() -> "Invalid framebuffer ID: " + name);
    }, id -> "temp".equals(id.getNamespace()) ? id.getPath() : id.toString()).stable();
    public static final FileToIdConverter FRAMEBUFFER_LISTER = FileToIdConverter.json("pinwheel/framebuffers");

    private final GpuDevice device;
    private final Map<Identifier, FramebufferDefinition> definitions = new HashMap<>();
    private final Map<Identifier, GpuFramebuffer> framebuffers = new HashMap<>();
    private final Set<Identifier> owned = new HashSet<>();
    private final Set<Identifier> screenSized = new HashSet<>();
    private int screenWidth;
    private int screenHeight;

    public FramebufferManager() {
        this(RenderSystem.getDevice(), Minecraft.getInstance().getWindow().getWidth(), Minecraft.getInstance().getWindow().getHeight());
        this.setMainFramebuffer(Minecraft.getInstance().gameRenderer.mainRenderTarget());
    }

    /** Explicit device/size constructor also supports isolated native GPU acceptance. */
    public FramebufferManager(GpuDevice device, int screenWidth, int screenHeight) {
        super(FramebufferDefinition.CODEC, FRAMEBUFFER_LISTER);
        this.device = java.util.Objects.requireNonNull(device);
        if (screenWidth <= 0 || screenHeight <= 0) throw new IllegalArgumentException("Screen dimensions must be positive");
        this.screenWidth = screenWidth;
        this.screenHeight = screenHeight;
    }

    /** Refresh this borrow after Minecraft resizes its main RenderTarget. */
    public void setMainFramebuffer(RenderTarget target) {
        GpuFramebuffer replacement = GpuFramebuffer.borrow(this.device, "Minecraft main", target);
        this.replace(MAIN, replacement, true);
    }

    private GpuFramebuffer allocate(Identifier name, FramebufferDefinition definition, int width, int height) {
        GpuFramebuffer target = definition.createBuilder(width, height).setDebugLabel(name.toString()).build(this.device);
        try {
            var encoder = this.device.createCommandEncoder();
            target.clear(encoder, new Vector4f(0,0,0,0), RenderSystem.DEFAULT_DEPTH_CLEAR_VALUE);
            encoder.submit();
            return target;
        } catch (RuntimeException | Error failure) {
            target.close();
            throw failure;
        }
    }

    private void replace(Identifier name, GpuFramebuffer replacement, boolean managerOwned) {
        GpuFramebuffer previous = this.framebuffers.put(name, replacement);
        boolean closePrevious = this.owned.remove(name);
        if (managerOwned) this.owned.add(name);
        if (closePrevious && previous != replacement) previous.close();
    }

    public void setDefinition(Identifier name, FramebufferDefinition definition) {
        if (name.equals(MAIN)) throw new IllegalArgumentException("The main RenderTarget is externally owned");
        GpuFramebuffer replacement = this.allocate(name, definition, this.screenWidth, this.screenHeight);
        this.replace(name, replacement, true);
        this.definitions.put(name, definition);
        if (!definition.width().isConstant() || !definition.height().isConstant()) this.screenSized.add(name);
        else this.screenSized.remove(name);
    }

    /** Allocate every changed screen target before swapping any of them. */
    public void resizeFramebuffers(int width, int height) {
        if (width <= 0 || height <= 0) throw new IllegalArgumentException("Screen dimensions must be positive");
        var replacements = new HashMap<Identifier, GpuFramebuffer>();
        try {
            for (Identifier name : this.screenSized) {
                replacements.put(name, this.allocate(name, this.definitions.get(name), width, height));
            }
        } catch (RuntimeException | Error failure) {
            replacements.values().forEach(GpuFramebuffer::close);
            throw failure;
        }
        this.screenWidth = width;
        this.screenHeight = height;
        replacements.forEach((name, target) -> this.replace(name, target, true));
    }

    /** Auto-clear resource targets, never manual/main targets, with one encoder. */
    public void clear() {
        if (this.definitions.isEmpty()) return;
        var encoder = this.device.createCommandEncoder();
        this.definitions.forEach((name, definition) -> {
            if (definition.autoClear()) this.framebuffers.get(name).clear(encoder, new Vector4f(0,0,0,0), RenderSystem.DEFAULT_DEPTH_CLEAR_VALUE);
        });
        encoder.submit();
    }

    /** Caller retains ownership, including on manager free/reload. */
    public void setFramebuffer(Identifier name, GpuFramebuffer framebuffer) {
        if (name.equals(MAIN)) throw new IllegalArgumentException("Use setMainFramebuffer for the borrowed main target");
        this.replace(name, java.util.Objects.requireNonNull(framebuffer), false);
        this.definitions.remove(name);
        this.screenSized.remove(name);
    }

    public @Nullable GpuFramebuffer removeFramebuffer(Identifier name) {
        if (this.owned.contains(name)) return null;
        this.definitions.remove(name);
        this.screenSized.remove(name);
        return this.framebuffers.remove(name);
    }
    public @Nullable GpuFramebuffer getFramebuffer(Identifier name) { return this.framebuffers.get(name); }
    public @Nullable FramebufferDefinition getFramebufferDefinition(Identifier name) { return this.definitions.get(name); }
    public Map<Identifier, GpuFramebuffer> getFramebuffers() { return Collections.unmodifiableMap(this.framebuffers); }

    /** Resource reload swaps the complete managed set only after allocation succeeds. */
    @Override protected void apply(Map<Identifier, FramebufferDefinition> data, ResourceManager resources, ProfilerFiller profiler) {
        var replacements = new HashMap<Identifier, GpuFramebuffer>();
        try {
            for (var entry : data.entrySet()) {
                if (entry.getKey().equals(MAIN)) throw new IllegalArgumentException("Resource pack cannot replace the Minecraft main framebuffer");
                replacements.put(entry.getKey(), this.allocate(entry.getKey(), entry.getValue(), this.screenWidth, this.screenHeight));
            }
        } catch (RuntimeException | Error failure) {
            replacements.values().forEach(GpuFramebuffer::close);
            throw failure;
        }
        for (Identifier name : Set.copyOf(this.definitions.keySet())) {
            GpuFramebuffer previous = this.framebuffers.remove(name);
            if (this.owned.remove(name) && previous != null) previous.close();
        }
        this.definitions.clear();
        this.screenSized.clear();
        data.forEach((name, definition) -> {
            this.replace(name, replacements.get(name), true);
            this.definitions.put(name, definition);
            if (!definition.width().isConstant() || !definition.height().isConstant()) this.screenSized.add(name);
        });
        LOGGER.info("Loaded {} native GPU framebuffers", data.size());
    }

    @Override public void free() {
        for (Identifier name : this.owned) this.framebuffers.get(name).close();
        this.framebuffers.clear();
        this.owned.clear();
        this.definitions.clear();
        this.screenSized.clear();
    }
}
