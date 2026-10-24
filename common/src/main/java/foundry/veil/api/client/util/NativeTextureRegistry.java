package foundry.veil.api.client.util;

import com.mojang.renderpearl.api.textures.GpuTexture;
import java.util.*;

/** Weak native resource inventory; UI selection keeps the actual texture object, never a GL integer. */
public final class NativeTextureRegistry {
    public enum Kind { TEXTURE_2D, CUBEMAP, ARRAY }
    private static final Map<GpuTexture,Kind> TEXTURES=Collections.synchronizedMap(new WeakHashMap<>());
    private NativeTextureRegistry(){}
    public static void track(GpuTexture texture){
        if(texture==null)return;
        TEXTURES.putIfAbsent(texture,(texture.usage()&GpuTexture.USAGE_CUBEMAP_COMPATIBLE)!=0?Kind.CUBEMAP:
                texture.getDepthOrLayers()>1?Kind.ARRAY:Kind.TEXTURE_2D);
    }
    public static void array(GpuTexture texture){TEXTURES.put(texture,Kind.ARRAY);}
    public static List<Entry> snapshot(){synchronized(TEXTURES){return TEXTURES.entrySet().stream().filter(entry->!entry.getKey().isClosed())
            .map(entry->new Entry(entry.getKey(),entry.getValue())).sorted(Comparator.comparing(entry->Objects.toString(entry.texture.getLabel(),""))).toList();}}
    public record Entry(GpuTexture texture,Kind kind){}
}
