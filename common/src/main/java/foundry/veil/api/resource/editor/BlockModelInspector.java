package foundry.veil.api.resource.editor;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.*;
import foundry.veil.Veil;
import foundry.veil.api.client.imgui.VeilImGuiUtil;
import foundry.veil.api.resource.VeilEditorEnvironment;
import foundry.veil.api.resource.VeilResourceInfo;
import foundry.veil.api.resource.VeilResourceManager;
import foundry.veil.api.resource.type.BlockModelResource;
import imgui.ImGui;
import imgui.flag.ImGuiCond;
import imgui.flag.ImGuiWindowFlags;
import imgui.type.ImBoolean;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.network.chat.Component;
import org.jetbrains.annotations.ApiStatus;
import org.joml.*;

import java.io.Reader;
import java.lang.Math;
import java.util.List;

/**
 * Viewer for block models
 *
 * @author ryanhcode
 */
@ApiStatus.Internal
public class BlockModelInspector implements ResourceFileEditor<BlockModelResource> {

    private static final Component TITLE = Component.translatable("inspector.veil.block_model.title");

    private final ImBoolean open;
    private final VeilResourceManager resourceManager;
    private final BlockModelResource resource;

    private net.minecraft.client.resources.model.geometry.ItemQuads quads=net.minecraft.client.resources.model.geometry.ItemQuads.EMPTY;
    private foundry.veil.api.client.render.gpu.NativeFeatureScene scene;

    public BlockModelInspector(VeilEditorEnvironment environment, BlockModelResource resource) {
        this.open = new ImBoolean(true);
        this.resourceManager = environment.getResourceManager();
        this.resource = resource;
        this.loadFromDisk();
    }

    @Override
    public void render() {
        if (this.resource == null || !this.open.get()) {
            return;
        }

        VeilResourceInfo resourceInfo = this.resource.resourceInfo();

        ImGui.setNextWindowSizeConstraints(256.0F, 256.0F, Float.MAX_VALUE, Float.MAX_VALUE);
        ImGui.setNextWindowSize(256.0F, 256.0F, ImGuiCond.Once);
        if (ImGui.begin(TITLE.getString() + "###model_editor_" + resourceInfo.fileName(), this.open, ImGuiWindowFlags.NoScrollbar | ImGuiWindowFlags.NoSavedSettings)) {
            VeilImGuiUtil.resourceLocation(resourceInfo.location());
            int desiredWidth = ((int) ImGui.getContentRegionAvailX() - 2) * 2;
            int desiredHeight = ((int) ImGui.getContentRegionAvailY() - 2) * 2;

            if (desiredWidth <= 0 || desiredHeight <= 0) {
                ImGui.end();
                return;
            }

            var texture = VeilImGuiUtil.renderArea(desiredWidth, desiredHeight, fbo -> {
                double time = ImGui.getTime();
                double yaw = Math.toRadians(time * 45.0);
                double pitch = Math.toRadians(30.0);

                Quaterniond cameraOrientation = new Quaterniond().rotateX(pitch).rotateY(yaw);
                Vector3d cameraPos = cameraOrientation.transformInverse(new Vector3d(0.0, 0.0, 2.8)).add(0.5, 0.5, 0.5);

                Matrix4f viewMatrix = new Matrix4f().rotate(new Quaternionf(cameraOrientation)).translate((float) -cameraPos.x, (float) -cameraPos.y, (float) -cameraPos.z);

                float aspect = (float) desiredWidth / (float) desiredHeight;
                var deviceInfo=RenderSystem.getDevice().getDeviceInfo();
                Matrix4f projMat=new Matrix4f().perspective((float)Math.toRadians(40),aspect,1000f,.3f,deviceInfo.isZZeroToOne());
                if(deviceInfo.backendName().toLowerCase(java.util.Locale.ROOT).contains("vulkan"))projMat.m11(-projMat.m11());
                Matrix4f modelView = new Matrix4f().mul(viewMatrix);

                final var client=Minecraft.getInstance();
                if(scene==null)scene=new foundry.veil.api.client.render.gpu.NativeFeatureScene(client,client.getResourceManager());
                scene.render(fbo,projMat,com.mojang.blaze3d.ProjectionType.PERSPECTIVE,modelView,
                        foundry.veil.api.client.render.gpu.NativeFeatureScene.Inputs.capture(client),collector->{
                    collector.submitItem(new PoseStack(),net.minecraft.world.item.ItemDisplayContext.NONE,
                            net.minecraft.util.LightCoordsUtil.FULL_BRIGHT,OverlayTexture.NO_OVERLAY,0,new int[0],quads,
                            net.minecraft.client.renderer.item.ItemStackRenderState.FoilType.NONE);
                });
            });

            if (ImGui.beginChild("3D View", desiredWidth / 2.0F + 2, desiredHeight / 2.0F + 2, false, ImGuiWindowFlags.NoScrollbar)) {
                foundry.imgui.api.ImGuiMC.image(foundry.imgui.api.ImGuiMC.getTexture(texture), desiredWidth / 2.0F, desiredHeight / 2.0F, 0, 1, 1, 0);
            }
            ImGui.endChild();
        }
        ImGui.end();
    }

    @Override public void close(){if(scene!=null){scene.close();scene=null;}}

    @Override
    public boolean isClosed() {
        return !this.open.get();
    }

    @Override
    public BlockModelResource getResource() {
        return this.resource;
    }

    @Override
    public void loadFromDisk() {
        Minecraft client = Minecraft.getInstance();
        try(Reader reader=resource.resourceInfo().openAsReader(resourceManager)){
            final var location=net.minecraft.resources.FileToIdConverter.json("models").fileToId(resource.resourceInfo().location());
            final var replacement=foundry.veil.api.client.util.NativeBlockModelBaker.bake(location,reader,client.getResourceManager(),client.getAtlasManager());
            this.quads=replacement;
        }catch(Exception failure){Veil.LOGGER.error("Failed to bake editor block model {}",resource.resourceInfo().location(),failure);}
        if(scene!=null){scene.close();scene=null;}
    }
}
