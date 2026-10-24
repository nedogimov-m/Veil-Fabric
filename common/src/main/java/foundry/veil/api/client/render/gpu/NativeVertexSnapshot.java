package foundry.veil.api.client.render.gpu;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.renderpearl.api.vertex.VertexFormat;
import net.minecraft.client.renderer.SubmitNodeCollector;
import java.util.ArrayList;
import java.util.List;

/** Detached procedural vertices; replay reads no mutable particle, camera or world state. */
public record NativeVertexSnapshot(VertexFormat format, List<Vertex> vertices) implements SubmitNodeCollector.CustomGeometryRenderer {
    public NativeVertexSnapshot { vertices = List.copyOf(vertices); }
    public record Vertex(float x,float y,float z,int color,float u,float v,int light,int overlay,
                         float nx,float ny,float nz,float u3,float v3,float lineWidth) { }
    @Override public void render(PoseStack.Pose pose, VertexConsumer output) {
        for(var vertex:this.vertices) {
            output.addVertex(pose,vertex.x,vertex.y,vertex.z);
            if(this.format.contains("Color"))output.setColor(vertex.color);
            if(this.format.contains("UV0"))output.setUv(vertex.u,vertex.v);
            if(this.format.contains("UV1"))output.setOverlay(vertex.overlay);
            if(this.format.contains("UV2"))output.setLight(vertex.light);
            if(this.format.contains("UV3"))output.setUv3(vertex.u3,vertex.v3);
            if(this.format.contains("Normal"))output.setNormal(pose,vertex.nx,vertex.ny,vertex.nz);
            if(this.format.contains("LineWidth"))output.setLineWidth(vertex.lineWidth);
        }
    }
    public static final class Capture implements VertexConsumer {
        private final VertexFormat format;
        private final List<Vertex> vertices=new ArrayList<>();
        private float x,y,z,u,v,nx,ny,nz,u3,v3,lineWidth;
        private int color,light,overlay;private boolean pending;
        public Capture(VertexFormat format){this.format=format;}
        private void flush(){if(this.pending){this.vertices.add(new Vertex(x,y,z,color,u,v,light,overlay,nx,ny,nz,u3,v3,lineWidth));this.pending=false;}}
        public NativeVertexSnapshot finish(){this.flush();return new NativeVertexSnapshot(this.format,this.vertices);}
        @Override public VertexConsumer addVertex(float x,float y,float z){this.flush();this.x=x;this.y=y;this.z=z;u=v=nx=nz=u3=v3=0;ny=lineWidth=1;color=-1;light=overlay=0;pending=true;return this;}
        @Override public VertexConsumer setColor(int r,int g,int b,int a){return this.setColor(a<<24|r<<16|g<<8|b);}
        @Override public VertexConsumer setColor(int color){this.color=color;return this;}
        @Override public VertexConsumer setUv(float u,float v){this.u=u;this.v=v;return this;}
        @Override public VertexConsumer setUv1(int u,int v){overlay=u&0xffff|v<<16;return this;}
        @Override public VertexConsumer setUv2(int u,int v){light=u&0xffff|v<<16;return this;}
        @Override public VertexConsumer setUv3(float u,float v){this.u3=u;this.v3=v;return this;}
        @Override public VertexConsumer setNormal(float x,float y,float z){nx=x;ny=y;nz=z;return this;}
        @Override public VertexConsumer setLineWidth(float width){this.lineWidth=width;return this;}
    }
}
