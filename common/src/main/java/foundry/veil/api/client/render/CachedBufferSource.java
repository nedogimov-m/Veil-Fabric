package foundry.veil.api.client.render;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import foundry.veil.api.client.render.gpu.NativeVertexSnapshot;
import foundry.veil.api.client.render.VertexSource;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.rendertype.RenderType;
import org.lwjgl.system.NativeResource;
import java.util.*;

/** CPU capture submitted during actual native feature collection, with independent connected strips. */
public final class CachedBufferSource implements VertexSource, NativeResource {
    private final Map<RenderType,NativeVertexSnapshot.Capture> started=new LinkedHashMap<>();
    private final List<Batch> ready=new ArrayList<>();
    @Override public VertexConsumer getBuffer(RenderType type){
        if(!type.canConsolidateConsecutiveGeometry())this.finish(type);
        return this.started.computeIfAbsent(type,key->new NativeVertexSnapshot.Capture(key.format()));
    }
    private void finish(RenderType type){var capture=this.started.remove(type);if(capture!=null){var snapshot=capture.finish();if(!snapshot.vertices().isEmpty())this.ready.add(new Batch(type,snapshot));}}
    public void submit(SubmitNodeCollector collector,PoseStack poses){
        for(var type:List.copyOf(this.started.keySet()))this.finish(type);
        try{for(var batch:this.ready)collector.submitCustomGeometry(poses,batch.type,batch.snapshot);}finally{this.ready.clear();}
    }
    @Override public void free(){this.started.clear();this.ready.clear();}
    private record Batch(RenderType type,NativeVertexSnapshot snapshot){}
}
