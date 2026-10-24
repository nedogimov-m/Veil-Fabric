package foundry.veil.api.client.render.gpu;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.commands.CommandEncoder;
import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.api.commands.RenderPassDescriptor;
import com.mojang.renderpearl.api.device.GpuDevice;
import com.mojang.renderpearl.api.pipeline.ColorTargetState;
import com.mojang.renderpearl.api.textures.GpuTexture;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import org.jetbrains.annotations.Nullable;
import org.joml.Vector4fc;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalDouble;

/**
 * Backend-independent framebuffer storage for Minecraft 26.3 render passes.
 * Owns textures and views allocated by its builder; borrowed RenderTarget views
 * remain owned by Minecraft. Commands are recorded into the caller's encoder,
 * so several passes/copies can share one submission. No OpenGL IDs or binds.
 */
public final class GpuFramebuffer implements AutoCloseable {
    private static final int USAGE = GpuTexture.USAGE_RENDER_ATTACHMENT | GpuTexture.USAGE_TEXTURE_BINDING
            | GpuTexture.USAGE_COPY_SRC | GpuTexture.USAGE_COPY_DST;
    private final GpuDevice device;
    private final String label;
    private final List<GpuFormat> colorFormats;
    @Nullable private final GpuFormat depthFormat;
    private final boolean owned;
    private List<Attachment> colors;
    @Nullable private Attachment depth;
    private int width;
    private int height;
    private boolean closed;

    private GpuFramebuffer(GpuDevice device, String label, int width, int height,
                           List<GpuFormat> colorFormats, @Nullable GpuFormat depthFormat) {
        this.device = Objects.requireNonNull(device);
        this.label = Objects.requireNonNull(label);
        this.colorFormats = List.copyOf(colorFormats);
        this.depthFormat = depthFormat;
        this.owned = true;
        this.colors = List.of();
        this.allocate(width, height);
    }

    private GpuFramebuffer(GpuDevice device, String label, RenderTarget target) {
        this.device = Objects.requireNonNull(device);
        this.label = Objects.requireNonNull(label);
        this.owned = false;
        this.width = target.width;
        this.height = target.height;
        GpuTextureView colorView = target.getColorTextureView();
        this.colors = colorView != null ? List.of(Attachment.borrow(colorView)) : List.of();
        this.colorFormats = this.colors.stream().map(attachment -> attachment.texture().getFormat()).toList();
        GpuTextureView depthView = target.getDepthTextureView();
        this.depth = depthView != null ? Attachment.borrow(depthView) : null;
        this.depthFormat = this.depth != null ? this.depth.texture().getFormat() : null;
        validateLayout(device, this.colorFormats, this.depthFormat, width, height);
    }

    private GpuFramebuffer(GpuDevice device,String label,List<GpuTextureView> colorViews,@Nullable GpuTextureView depthView) {
        this.device=Objects.requireNonNull(device);this.label=Objects.requireNonNull(label);this.owned=false;
        final var views=List.copyOf(colorViews);
        if(views.isEmpty()&&depthView==null)throw new IllegalArgumentException("A framebuffer needs at least one attachment");
        final var first=views.isEmpty()?depthView:views.getFirst();
        requireBorrowedView(device,Objects.requireNonNull(first));this.width=first.getWidth(0);this.height=first.getHeight(0);
        for(var view:views){requireBorrowedView(device,view);requireSize(view,width,height);}
        if(depthView!=null){requireBorrowedView(device,depthView);requireSize(depthView,width,height);}
        this.colors=views.stream().map(Attachment::borrow).toList();
        this.depth=depthView!=null?Attachment.borrow(depthView):null;
        this.colorFormats=views.stream().map(view->view.texture().getFormat()).toList();
        this.depthFormat=depthView!=null?depthView.texture().getFormat():null;
        validateLayout(device,colorFormats,depthFormat,width,height);
    }

    private static void requireSize(GpuTextureView view,int width,int height) {
        if(view.getWidth(0)!=width||view.getHeight(0)!=height)throw new IllegalArgumentException("Borrowed framebuffer attachments need equal dimensions");
    }

    private static void requireBorrowedView(GpuDevice device,GpuTextureView view) {
        if(view.isClosed()||view.texture().isClosed())throw new IllegalArgumentException("Cannot borrow a closed texture/view");
        if(view.baseMipLevel()!=0||view.mipLevels()!=1||view.texture().getMipLevels()!=1)
            throw new IllegalArgumentException("Framebuffer operations require single-mip level-zero attachment views");
        if(view.texture().getDepthOrLayers()!=1)throw new IllegalArgumentException("Framebuffer attachment views must select a single 2D layer");
        // The public resource interfaces do not expose ownership. Read the genuine backend owner
        // identities rather than accepting an incompatible device or inventing a compatibility ID.
        if(!(device instanceof com.mojang.renderpearl.frontend.FrontendGpuDevice))throw new IllegalArgumentException("Expected a native frontend GPU device");
        final Object backend=nativeField(com.mojang.renderpearl.frontend.FrontendGpuDevice.class,device,"backend");
        final Object owner;
        if(view.texture() instanceof com.mojang.renderpearl.backend.vulkan.VulkanGpuTexture texture)
            owner=nativeField(com.mojang.renderpearl.backend.vulkan.VulkanGpuTexture.class,texture,"device");
        else if(view.texture() instanceof com.mojang.renderpearl.backend.opengl.GlTexture texture) {
            owner=nativeField(com.mojang.renderpearl.backend.opengl.GlTexture.class,texture,"frameBufferCache");
            if(owner!=nativeField(backend.getClass(),backend,"frameBufferCache"))throw new IllegalArgumentException("Texture belongs to another GPU device");
            return;
        } else throw new IllegalArgumentException("Unknown native GPU texture owner: "+view.texture().getClass().getName());
        if(owner!=backend)throw new IllegalArgumentException("Texture belongs to another GPU device");
    }

    private static Object nativeField(Class<?> owner,Object instance,String name) {
        try {final var field=owner.getDeclaredField(name);field.setAccessible(true);return field.get(instance);}
        catch(ReflectiveOperationException failure){throw new IllegalStateException("Cannot verify actual native GPU resource ownership",failure);}
    }

    public static Builder builder(String label, int width, int height) { return new Builder(label, width, height); }

    /** The borrow expires when the Minecraft RenderTarget is resized or destroyed. */
    public static GpuFramebuffer borrow(GpuDevice device, String label, RenderTarget target) {
        return new GpuFramebuffer(device, label, Objects.requireNonNull(target));
    }

    /** Compose actual caller-owned attachments, e.g. owned HDR color with borrowed main depth.
     * All views must be live, single-mip, equally sized and owned by this real GPU device.
     * Closing the composite never closes any texture/view; resizing remains each owner's job. */
    public static GpuFramebuffer borrowAttachments(GpuDevice device,String label,List<GpuTextureView> colors,@Nullable GpuTextureView depth) {
        return new GpuFramebuffer(device,label,colors,depth);
    }

    public int width() { this.ensureOpen(); return this.width; }
    public int height() { this.ensureOpen(); return this.height; }
    public int colorCount() { this.ensureOpen(); return this.colors.size(); }
    public boolean hasDepth() { this.ensureOpen(); return this.depth != null; }
    public boolean isClosed() { return this.closed; }
    public String label() { return this.label; }
    public Attachment color(int index) { this.ensureOpen(); return this.colors.get(index); }
    public Attachment depth() { this.ensureOpen(); return Objects.requireNonNull(this.depth, "Framebuffer has no depth attachment"); }

    /**
     * Allocate all replacements before closing the old set. Invalid dimensions or
     * a failed allocation leave the previous framebuffer usable and leak no views.
     */
    public void resize(int width, int height) {
        this.ensureOpen();
        if (!this.owned) throw new IllegalStateException("A borrowed RenderTarget must be resized by its owner");
        if (width != this.width || height != this.height) this.allocate(width, height);
    }

    private void allocate(int width, int height) {
        validateLayout(this.device, this.colorFormats, this.depthFormat, width, height);
        var replacements = new ArrayList<Attachment>(this.colorFormats.size());
        Attachment newDepth = null;
        try {
            for (int index = 0; index < this.colorFormats.size(); index++) {
                replacements.add(Attachment.allocate(this.device, this.label + "/color" + index,
                        this.colorFormats.get(index), width, height));
            }
            if (this.depthFormat != null) {
                newDepth = Attachment.allocate(this.device, this.label + "/depth", this.depthFormat, width, height);
            }
        } catch (RuntimeException | Error failure) {
            for (Attachment attachment : replacements) attachment.close();
            if (newDepth != null) newDepth.close();
            throw failure;
        }
        List<Attachment> previous = this.colors;
        Attachment previousDepth = this.depth;
        this.colors = List.copyOf(replacements);
        this.depth = newDepth;
        this.width = width;
        this.height = height;
        for (Attachment attachment : previous) attachment.close();
        if (previousDepth != null) previousDepth.close();
    }

    /** Begin an explicit pass, retaining existing pixels in every attachment. */
    public RenderPass beginPass(CommandEncoder encoder) {
        return this.beginPass(encoder, List.of(), OptionalDouble.empty());
    }

    /**
     * Optional clear values are indexed by color attachment. Omitted entries load
     * existing pixels. MRT outputs retain their actual attachment indices.
     */
    public RenderPass beginPass(CommandEncoder encoder, List<Optional<Vector4fc>> clearColors,
                                OptionalDouble clearDepth) {
        this.ensureOpen();
        if (clearColors.size() > this.colors.size()) throw new IllegalArgumentException("Too many color clear values");
        if (clearDepth.isPresent() && this.depth == null) throw new IllegalArgumentException("No depth attachment to clear");
        var descriptor = RenderPassDescriptor.builder(() -> this.label);
        for (int index = 0; index < this.colors.size(); index++) {
            descriptor.withColorAttachment(this.colors.get(index).view(), index < clearColors.size()
                    ? clearColors.get(index) : Optional.empty());
        }
        if (this.depth != null) descriptor.withDepthAttachment(this.depth.view(), clearDepth);
        return encoder.createRenderPass(descriptor.build());
    }

    /** Clear every color texture and optional depth texture without binding global state. */
    public void clear(CommandEncoder encoder, Vector4fc color, double depth) {
        this.ensureOpen();
        for (Attachment attachment : this.colors) encoder.clearColorTexture(attachment.texture(), color);
        if (this.depth != null) encoder.clearDepthTexture(this.depth.texture(), depth);
    }

    /** Exact, same-format copy of a color attachment, with no filtering/conversion. */
    public void copyColorTo(CommandEncoder encoder, int sourceAttachment, GpuFramebuffer target, int targetAttachment) {
        this.copyTexture(encoder, this.color(sourceAttachment), target, target.color(targetAttachment));
    }

    public void copyDepthTo(CommandEncoder encoder, GpuFramebuffer target) {
        this.copyTexture(encoder, this.depth(), target, target.depth());
    }

    private void copyTexture(CommandEncoder encoder, Attachment source, GpuFramebuffer target, Attachment destination) {
        this.ensureOpen();
        target.ensureOpen();
        if (this.device != target.device) throw new IllegalArgumentException("Cannot copy between GPU devices");
        if (this.width != target.width || this.height != target.height) {
            throw new IllegalArgumentException("Texture copies need equal dimensions; use GpuFullscreenPass for scaled color blits");
        }
        if (source.texture().getFormat() != destination.texture().getFormat()) {
            throw new IllegalArgumentException("Texture copies need identical formats");
        }
        if (source.texture() == destination.texture()) throw new IllegalArgumentException("Cannot copy a texture onto itself");
        encoder.copyTextureToTexture(source.texture(), destination.texture(), 0, 0, 0, 0, 0, this.width, this.height);
    }

    private void ensureOpen() {
        if (this.closed) throw new IllegalStateException("Framebuffer is closed: " + this.label);
        for (Attachment attachment : this.colors) attachment.ensureOpen();
        if (this.depth != null) this.depth.ensureOpen();
    }

    @Override
    public void close() {
        if (this.closed) return;
        this.closed = true;
        for (Attachment attachment : this.colors) attachment.close();
        if (this.depth != null) this.depth.close();
    }

    private static void validateLayout(GpuDevice device, List<GpuFormat> colors, @Nullable GpuFormat depth, int width, int height) {
        if (colors.isEmpty() && depth == null) throw new IllegalArgumentException("A framebuffer needs at least one attachment");
        int limit = Math.min(ColorTargetState.MAX_COLOR_TARGETS, device.getDeviceInfo().limits().maxColorAttachments());
        if (colors.size() > limit) throw new IllegalArgumentException("Too many color attachments: " + colors.size() + ", limit " + limit);
        for (GpuFormat format : colors) {
            if (!format.hasColorAspect()) throw new IllegalArgumentException("Not a color format: " + format);
            // Metal has no unpacked three-component render textures. MoltenVK can
            // reach a native assertion instead of a recoverable allocation error.
            // Resource definitions must explicitly choose their RGBA storage.
            if (format.name().matches("RGB(?:8|16|32)_.*")) {
                throw new IllegalArgumentException("Unpacked RGB render attachments are not portable to Vulkan/Metal; use explicit RGBA storage: " + format);
            }
            validateSize(device, format, width, height);
        }
        if (depth != null) {
            if (!depth.hasDepthAspect()) throw new IllegalArgumentException("Not a depth format: " + depth);
            validateSize(device, depth, width, height);
        }
    }

    private static void validateSize(GpuDevice device, GpuFormat format, int width, int height) {
        int limit = device.getDeviceInfo().limits().maxTextureSizeForFormat(format);
        if (width <= 0 || height <= 0 || width > limit || height > limit) {
            throw new IllegalArgumentException("Invalid framebuffer size " + width + "x" + height + " for " + format + " (limit " + limit + ")");
        }
    }

    /** GPU handles are typed and the attachment owns both its texture and view. */
    public static final class Attachment implements AutoCloseable {
        private final GpuTexture texture;
        private final GpuTextureView view;
        private final boolean owned;
        private boolean closed;

        private Attachment(GpuTexture texture, GpuTextureView view, boolean owned) {
            this.texture = texture;
            this.view = view;
            this.owned = owned;
        }
        private static Attachment allocate(GpuDevice device, String label, GpuFormat format, int width, int height) {
            GpuTexture texture = device.createTexture(label, USAGE, format, width, height, 1, 1);
            try {
                return new Attachment(texture, device.createTextureView(texture), true);
            } catch (RuntimeException | Error failure) {
                texture.close();
                throw failure;
            }
        }
        private static Attachment borrow(GpuTextureView view) { return new Attachment(view.texture(), view, false); }
        public GpuTexture texture() { this.ensureOpen(); return this.texture; }
        public GpuTextureView view() { this.ensureOpen(); return this.view; }
        private void ensureOpen() {
            if (this.closed || this.texture.isClosed() || this.view.isClosed()) throw new IllegalStateException("Framebuffer attachment is closed");
        }
        @Override public void close() {
            if (this.closed) return;
            this.closed = true;
            if (this.owned) {
                try { this.view.close(); } finally { this.texture.close(); }
            }
        }
    }

    public static final class Builder {
        private String label;
        private final int width;
        private final int height;
        private final List<GpuFormat> colors = new ArrayList<>();
        @Nullable private GpuFormat depth;
        private Builder(String label, int width, int height) { this.label = label; this.width = width; this.height = height; }
        public Builder setDebugLabel(String label) { this.label = Objects.requireNonNull(label); return this; }
        public Builder color(GpuFormat format) { this.colors.add(Objects.requireNonNull(format)); return this; }
        public Builder depth(GpuFormat format) {
            if (this.depth != null) throw new IllegalArgumentException("Only one depth attachment is supported");
            this.depth = Objects.requireNonNull(format); return this;
        }
        public GpuFramebuffer build(GpuDevice device) { return new GpuFramebuffer(device, this.label, this.width, this.height, this.colors, this.depth); }
    }
}
