package foundry.veil.api.client.render.texture;

import com.mojang.renderpearl.api.device.GpuDevice;
import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.backend.vulkan.VulkanDevice;
import com.mojang.renderpearl.api.textures.GpuTexture;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import foundry.veil.mixin.pipeline.accessor.NativeTextureDeviceAccessor;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkPhysicalDeviceProperties;
import static org.lwjgl.vulkan.VK12.vkGetPhysicalDeviceProperties;
import java.util.Locale;

/** Narrow native view-creation intent; no fake usage bits or altered ordinary/cube textures. */
public final class NativeArrayTextureViews {
    private static final ThreadLocal<Integer> LAYERS=new ThreadLocal<>();
    public static int creatingLayers(){final var layers=LAYERS.get();return layers==null?0:layers;}
    public static void requireBackend(GpuDevice device) {
        if(!device.getDeviceInfo().backendName().toLowerCase(Locale.ROOT).contains("vulkan"))
            throw new UnsupportedOperationException("Native array texture allocation/views/uploads need the OpenGL backend extension");
    }
    /** Implement the missing native frontend array feature against real Vulkan storage and physical limits. */
    public static GpuTexture allocate(GpuDevice device,String label,int width,int height,int layers) {
        requireBackend(device);
        final var backend=((NativeTextureDeviceAccessor)device).veil$textureBackend();
        if(!(backend instanceof VulkanDevice vulkan))throw new IllegalStateException("Expected the genuine Vulkan device");
        try(var stack=MemoryStack.stackPush()) {
            final var properties=VkPhysicalDeviceProperties.calloc(stack);
            vkGetPhysicalDeviceProperties(vulkan.vkDevice().getPhysicalDevice(),properties);
            final int limit=properties.limits().maxImageDimension2D();
            if(width<1||height<1||width>limit||height>limit||layers<1||layers>properties.limits().maxImageArrayLayers())
                throw new IllegalArgumentException("Array dimensions/layers exceed actual Vulkan physical-device limits");
        }
        final int usage=GpuTexture.USAGE_COPY_SRC|GpuTexture.USAGE_COPY_DST|GpuTexture.USAGE_TEXTURE_BINDING;
        final var texture=backend.createTexture(label,usage,GpuFormat.RGBA8_UNORM,width,height,layers,1);
        foundry.veil.api.client.util.NativeTextureRegistry.array(texture);return texture;
    }
    public static GpuTextureView create(GpuDevice device,GpuTexture texture) {
        requireBackend(device);
        if(LAYERS.get()!=null)throw new IllegalStateException("Nested array-view creation");
        LAYERS.set(texture.getDepthOrLayers());
        try {return device.createTextureView(texture);}finally {LAYERS.remove();}
    }
    private NativeArrayTextureViews() { }
}
