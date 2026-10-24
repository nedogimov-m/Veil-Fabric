package foundry.veil.impl.client.editor;

import com.mojang.blaze3d.systems.RenderSystem;
import foundry.veil.api.client.editor.SingleWindowInspector;
import imgui.ImGui;
import net.minecraft.network.chat.Component;
import org.jetbrains.annotations.ApiStatus;
import java.util.Map;

/** Reports the active native device and its genuine portable capabilities, without a GL context. */
@ApiStatus.Internal
public class DeviceInfoViewer extends SingleWindowInspector {
    public static final Component TITLE=Component.translatable("inspector.veil.device_info.title");
    private static final Map<Integer,Component> SHADER_TYPES=Map.of(
        0x8B31,Component.translatable("inspector.veil.shader.vertex_shader"),
        0x8E88,Component.translatable("inspector.veil.shader.tess_control_shader"),
        0x8E87,Component.translatable("inspector.veil.shader.tess_eval_shader"),
        0x8DD9,Component.translatable("inspector.veil.shader.geometry_shader"),
        0x8B30,Component.translatable("inspector.veil.shader.fragment_shader"),
        0x91B9,Component.translatable("inspector.veil.shader.compute_shader"));
    public static Component getShaderName(int shader){return SHADER_TYPES.get(shader);}
    private static void value(String label,Object value){ImGui.text(label+": "+value);}
    @Override public Component getDisplayName(){return TITLE;}
    @Override public Component getGroup(){return INFO_GROUP;}
    @Override protected void renderComponents(){
        final var device=RenderSystem.getDevice();final var info=device.getDeviceInfo();
        value("Backend",info.backendName());value("Device",info.name());value("Vendor",info.vendorName());
        value("Driver",info.driverInfo());value("Device type",info.type());value("Zero to one depth",info.isZZeroToOne());
        value("GPU timestamp period",info.timestampPeriod());value("Debug output",device.isDebuggingEnabled());
        ImGui.separator();final var limits=info.limits();
        value("Maximum texture size",limits.maxTextureSize());value("Maximum color attachments",limits.maxColorAttachments());
        value("Maximum anisotropy",limits.maxAnisotropy());value("Uniform offset alignment",limits.minUniformOffsetAlignment());
        value("Maximum allocation bytes",limits.maxMemoryAllocationSize());
        value("Maximum interleaved draws",limits.maxMultiDrawDirectInterleavedDrawCount());
        value("Maximum indirect draws",limits.maxDrawIndirectDrawCount());
        ImGui.separator();final var features=info.features();
        value("Wireframe",features.wireframeFillMode());value("Shader draw parameters",features.shaderDrawParameters());
        value("Interleaved multi-draw",features.multiDrawDirectInterleaved());value("Separate multi-draw",features.multiDrawDirectSeparate());
        value("Indirect draw",features.drawIndirect());value("Indirect multi-draw",features.multiDrawIndirect());
        value("Non-zero first instance",features.nonZeroFirstInstance());value("Persistent mapping",features.persistentMapping());
        if(ImGui.collapsingHeader("Device extensions"))info.underlyingExtensions().stream().sorted().forEach(ImGui::text);
        if(ImGui.collapsingHeader("GPU messages"))device.getLastDebugMessages().forEach(ImGui::text);
    }
    @Override public void render(){ImGui.setNextWindowSizeConstraints(400,460,Float.MAX_VALUE,Float.MAX_VALUE);super.render();}
}
