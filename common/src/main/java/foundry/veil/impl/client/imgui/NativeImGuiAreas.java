package foundry.veil.impl.client.imgui;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.GpuFormat;
import foundry.imgui.api.ImGuiMC;
import foundry.veil.api.client.render.gpu.GpuFramebuffer;
import java.util.*;

/** Reusable native offscreen areas; resized/unused views survive until actual ImGui submission. */
public final class NativeImGuiAreas {
    private static final List<GpuFramebuffer> AREAS=new ArrayList<>();
    private static int pointer;
    private NativeImGuiAreas(){}
    public static GpuFramebuffer allocate(int width,int height){
        if(width<1||height<1)throw new IllegalArgumentException("ImGui render area dimensions must be positive");
        GpuFramebuffer framebuffer=pointer<AREAS.size()?AREAS.get(pointer):null;
        if(framebuffer==null||framebuffer.width()!=width||framebuffer.height()!=height){
            final var replacement=GpuFramebuffer.builder("ImGui native area "+pointer,width,height).color(GpuFormat.RGBA8_UNORM).depth(GpuFormat.D32_FLOAT).build(RenderSystem.getDevice());
            if(framebuffer!=null)ImGuiMC.afterDraw(framebuffer::close);
            framebuffer=replacement;if(pointer==AREAS.size())AREAS.add(framebuffer);else AREAS.set(pointer,framebuffer);
        }
        pointer++;return framebuffer;
    }
    public static void begin(){pointer=0;}
    public static void end(){while(AREAS.size()>pointer){final var unused=AREAS.removeLast();ImGuiMC.afterDraw(unused::close);}}
    public static void free(){pointer=0;end();}
}
