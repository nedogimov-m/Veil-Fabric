package foundry.veil.api.client.imgui;


import foundry.imgui.api.ImGuiMC;
import foundry.veil.Veil;
import foundry.veil.api.client.render.gpu.GpuFramebuffer;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import foundry.veil.impl.client.imgui.NativeImGuiAreas;
import imgui.ImFont;
import imgui.ImGui;
import imgui.ImVec4;
import imgui.flag.ImGuiCol;
import imgui.flag.ImGuiStyleVar;
import net.minecraft.client.StringSplitter;
import net.minecraft.network.chat.*;
import net.minecraft.resources.Identifier;

import java.util.function.Consumer;

/**
 * Extra components and helpers for ImGui.
 *
 * @author ryan, Ocelot
 */
public class VeilImGuiUtil {


    public static final Identifier ICON_FONT = Veil.veilPath("remixicon");

    /**
     * Displays a (?) with a hover tooltip. Useful for example information.
     *
     * @param text The tooltip text
     */
    public static void tooltip(String text) {
        ImGui.textColored(0xFF555555, "(?)");
        if (ImGui.isItemHovered()) {
            ImGui.beginTooltip();
            ImGui.pushTextWrapPos(ImGui.getFontSize() * 35.0f);
            ImGui.textColored(0xFFFFFFFF, text);
            ImGui.popTextWrapPos();
            ImGui.endTooltip();
        }
    }

    /**
     * Sets the tooltip to the specified component
     *
     * @param text The text to render
     */
    public static void setTooltip(FormattedText text) {
        ImGui.beginTooltip();
        component(text);
        ImGui.endTooltip();
    }

    /**
     * Fully renders Minecraft text into ImGui.
     *
     * @param text The text to render
     */
    public static void component(FormattedText text) {
        component(text, Float.POSITIVE_INFINITY);
    }

    /**
     * Fully renders wrapped Minecraft text into ImGui.
     *
     * @param text      The text to render
     * @param wrapWidth The width to wrap to
     */
    public static void component(FormattedText text, float wrapWidth) {
        ImGuiMC.component(text,wrapWidth);
    }

    /**
     * Renders an icon with the remixicon font
     *
     * @param code The icon code (ex. &#xED0F;)
     */
    public static void icon(int code) {
        ImGui.pushFont(ImGuiMC.getFont(ICON_FONT, false, false),0);
        ImGui.text("" + (char) code);
        ImGui.popFont();
    }

    /**
     * Renders an icon with the remixicon font and a color
     *
     * @param code  The icon code (ex. &#xED0F;)
     * @param color The color of the icon
     */
    public static void icon(int code, int color) {
        ImGui.pushFont(ImGuiMC.getFont(ICON_FONT, false, false),0);
        ImGui.textColored(color, "" + (char) code);
        ImGui.popFont();
    }

    /**
     * Helper to draw centered text.
     *
     * @param text  The text to render
     * @param width The width of the area to center on
     */
    public static void textCentered(String text, float width) {
        ImGui.setCursorPosX(ImGui.getCursorPosX() + (width - ImGui.getFont().calcTextSizeAX(ImGui.getFontSize(), Float.MAX_VALUE, 0, text)) / 2);
        ImGui.text(text);
    }

    /**
     * Displays a resource location with a dimmed namespace
     *
     * @param loc The resource location
     */
    public static void resourceLocation(Identifier loc) {
        ImGui.beginGroup();
        ImGui.textColored(colorOf(loc.getNamespace()), loc.getNamespace() + ":");

        ImGui.pushStyleVar(ImGuiStyleVar.ItemSpacing, 0, 0);
        ImGui.sameLine();
        ImGui.text(loc.getPath());
        ImGui.popStyleVar();

        ImGui.endGroup();

        if (ImGui.beginPopupContextItem("" + loc)) {
            if (ImGui.selectable("##copy_location")) {
                ImGui.setClipboardText(loc.toString());
            }

            ImGui.pushStyleVar(ImGuiStyleVar.ItemSpacing, 0, 0);
            ImGui.setNextItemAllowOverlap();
            ImGui.sameLine();
            VeilImGuiUtil.icon(0xEB91);
            ImGui.sameLine();
            ImGui.popStyleVar();
            ImGui.text("Copy Location");
            ImGui.endPopup();
        }
    }

    /**
     * Creates a rendering area of the specified size.
     *
     * @param width    The width of the area
     * @param height   The height of the area
     * @param renderer The renderer inside the area
     * @return The genuine native color view that can be displayed in ImGui
     */
    public static GpuTextureView renderArea(int width,int height,Consumer<GpuFramebuffer> renderer) {
        final var colors=ImGui.getStyle().getColors()[ImGuiCol.FrameBg];
        final var framebuffer=NativeImGuiAreas.allocate(width,height);
        final var encoder=com.mojang.blaze3d.systems.RenderSystem.getDevice().createCommandEncoder();
        framebuffer.clear(encoder,new org.joml.Vector4f(colors.x,colors.y,colors.z,colors.w),com.mojang.blaze3d.systems.RenderSystem.DEFAULT_DEPTH_CLEAR_VALUE);
        encoder.submit();renderer.accept(framebuffer);return framebuffer.color(0).view();
    }

    /**
     * Obtains the color of the modid
     *
     * @param modid The modid to get the color of
     * @return color The color based on the hash of the modid
     */
    public static int colorOf(String modid) {
        int color = (modid.hashCode() & 0xAAAAAA) + 0x444444;

//        Color dark = new Color(0.6F, 0.6F, 0.6F);
//        Color c = new Color(0xFF | color << 8);
//        c.mix(dark, 0.35F);
//        return 0xFF | (c.rgb() & 0xFFFFFF) << 8;

        int r = (int) ((color & 0xFF) * 0.65F + 53);
        int g = (int) (((color >> 8) & 0xFF) * 0.65F + 53);
        int b = (int) (((color >> 16) & 0xFF) * 0.65F + 53);
        return 0xFF000000 | r << 16 | g << 8 | b;
    }

    /**
     * Retrieves the ImGui font to use for the specified Minecraft style.
     *
     * @param style The style to get the font for
     * @return The ImFont to use
     */
    public static ImFont getStyleFont(Style style) {
        return ImGuiMC.getStyleFont(style);
    }

    /**
     * Retrieves the ARGB color for the specified ImGui style color.
     *
     * @param color The ImGui color index
     * @return The ARGB ImGui color
     */
    public static int getColor(int color) {
        ImVec4 colors = ImGui.getStyle().getColors()[color];
        return (int) (colors.w * 255) << 24 | (int) (colors.x * 255) << 16 | (int) (colors.y * 255) << 8 | (int) (colors.z * 255);
    }

    /**
     * @return A string splitter for ImGui fonts
     */
    public static StringSplitter getStringSplitter() {
        return ImGuiMC.getStringSplitter();
    }

}
