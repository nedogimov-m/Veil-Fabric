package foundry.veil.impl.resource;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.textures.*;
import foundry.imgui.api.ImGuiMC;
import foundry.veil.Veil;
import foundry.veil.api.resource.VeilResource;
import foundry.veil.impl.resource.tree.VeilResourceFolder;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.PackType;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.Nullable;
import org.lwjgl.system.NativeResource;

import java.util.OptionalDouble;

@ApiStatus.Internal
public class VeilPackResources implements NativeResource {

    private final String name;
    private final VeilResourceFolder root;
    private GpuTexture texture;
    private GpuTextureView view;
    private GpuSampler sampler;

    public VeilPackResources(String name) {
        this.name = name;
        this.root = new VeilResourceFolder(name);
    }

    public void add(@Nullable PackType packType, Identifier loc, VeilResource<?> resource) {
        if (packType != null) {
            this.root.addResource(packType.getDirectory() + "/" + loc.getNamespace() + "/" + loc.getPath(), resource);
        } else {
            this.root.addResource(loc.getPath(), resource);
        }
    }

    public @Nullable VeilResource<?> getVeilResource(String namespace, String path) {
        VeilResourceFolder rootFolder = this.root.getFolder(namespace);
        if (rootFolder != null) {
            VeilResource<?> resource = rootFolder.getResource(path);
            if (resource != null) {
                return resource;
            }
        }

        for (PackType value : PackType.values()) {
            VeilResourceFolder packFolder = this.root.getFolder(value.getDirectory());
            if (packFolder == null) {
                continue;
            }

            VeilResourceFolder namespaceFolder = packFolder.getFolder(namespace);
            if (namespaceFolder != null) {
                VeilResource<?> rootResource = namespaceFolder.getResource(path);
                if (rootResource != null) {
                    return rootResource;
                }
            }
        }

        return null;
    }

    public void loadIcon(NativeImage image, boolean blur) {
        final var device = RenderSystem.getDevice();
        final var replacement = device.createTexture("Veil pack icon: " + this.name,
                GpuTexture.USAGE_COPY_DST | GpuTexture.USAGE_TEXTURE_BINDING,
                GpuFormat.RGBA8_UNORM, image.getWidth(), image.getHeight(), 1, 1);
        GpuTextureView replacementView = null;
        GpuSampler replacementSampler = null;
        try {
            final var upload = device.createCommandEncoder();
            upload.writeToTexture(replacement, image);
            upload.submit();
            replacementView = device.createTextureView(replacement);
            final var filter = blur ? FilterMode.LINEAR : FilterMode.NEAREST;
            replacementSampler = device.createSampler(AddressMode.CLAMP_TO_EDGE, AddressMode.CLAMP_TO_EDGE,
                    filter, filter, 1, OptionalDouble.of(0));
        } catch (Throwable failure) {
            if (replacementSampler != null) replacementSampler.close();
            if (replacementView != null) replacementView.close();
            replacement.close();
            throw failure;
        }
        this.free();
        this.texture = replacement;
        this.view = replacementView;
        this.sampler = replacementSampler;
    }

    public String getName() {
        return this.name;
    }

    public VeilResourceFolder getRoot() {
        return this.root;
    }

    public @Nullable GpuTextureView getTexture() {
        return this.view;
    }

    public @Nullable GpuSampler getSampler() {
        return this.sampler;
    }

    @Override
    public void free() {
        final var oldTexture = this.texture;
        final var oldView = this.view;
        final var oldSampler = this.sampler;
        this.texture = null;
        this.view = null;
        this.sampler = null;
        if (oldTexture != null) {
            final Runnable retire = () -> {
                oldSampler.close();
                oldView.close();
                oldTexture.close();
            };
            if (Veil.IMGUIMC) ImGuiMC.afterDraw(retire); else retire.run();
        }
    }
}
