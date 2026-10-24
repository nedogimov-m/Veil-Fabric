package foundry.veil.mixin.pipeline.client;

import foundry.veil.api.client.render.texture.NativeArrayTextureViews;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.lwjgl.vulkan.VK12;

/** Minecraft's Vulkan allocation already owns all layers; select the real native array view. */
@Mixin(targets="com.mojang.renderpearl.backend.vulkan.VulkanGpuTextureView",remap=false)
public abstract class NativeVulkanArrayViewMixin {
    @ModifyArg(method="<init>",at=@At(value="INVOKE",target="Lorg/lwjgl/vulkan/VkImageViewCreateInfo;viewType(I)Lorg/lwjgl/vulkan/VkImageViewCreateInfo;"),index=0)
    private int veil$arrayViewType(int type){return NativeArrayTextureViews.creatingLayers()>0?VK12.VK_IMAGE_VIEW_TYPE_2D_ARRAY:type;}
    @ModifyArg(method="<init>",at=@At(value="INVOKE",target="Lorg/lwjgl/vulkan/VkImageSubresourceRange;layerCount(I)Lorg/lwjgl/vulkan/VkImageSubresourceRange;"),index=0)
    private int veil$arrayLayerCount(int count){final int layers=NativeArrayTextureViews.creatingLayers();return layers>0?layers:count;}
}
