import com.mojang.blaze3d.vertex.PoseStack;
import foundry.veil.api.client.render.gpu.NativeVertexSnapshot;
import foundry.veil.api.client.util.DebugFrustumRenderer;
import foundry.veil.api.client.util.DebugRenderHelper;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import org.joml.Matrix4f;
import java.util.*;

public final class NativeDebugGeometryAcceptance {
    private static void check(boolean value,String message){if(!value)throw new AssertionError(message);}
    public static void main(String[] args){
        var source=new NativeVertexSnapshot.Capture(RenderTypes.lines().format());var poses=new PoseStack();poses.translate(2,3,4);
        DebugRenderHelper.renderCube(poses,source,1,.5f,0,1);var cube=source.finish();
        check(cube.vertices().size()==24,"Twelve native cube edges");
        for(var v:cube.vertices())check(v.lineWidth()==1&&Float.isFinite(v.nx())&&v.x()>=2&&v.x()<=3,"Actual posed lines carry width/tangents");
        var view=new Matrix4f().translation(-5,-2,-3);var projection=new Matrix4f().perspective((float)Math.PI/2,1,10,1,true);
        var frustum=DebugFrustumRenderer.snapshot(view,projection,true,1,0,1,1);view.zero();projection.zero();
        check(frustum.vertices().size()==24,"Detached native frustum has twelve edges");
        Set<Float> z=new HashSet<>();float maxX=Float.NEGATIVE_INFINITY,minX=Float.POSITIVE_INFINITY;
        for(var v:frustum.vertices()){z.add(Math.round(v.z()*1000)/1000f);maxX=Math.max(maxX,v.x());minX=Math.min(minX,v.x());check(v.lineWidth()==1&&Float.isFinite(v.nx())&&Float.isFinite(v.ny())&&Float.isFinite(v.nz()),"Finite tangent/width");}
        check(z.equals(Set.of(-7f,2f))&&Math.abs(maxX-15)<.001f&&Math.abs(minX+5)<.001f,"Homogeneous division yields actual perspective near/far corners");
        var captured=frustum.vertices();check(captured.getFirst().x()!=0,"Mutated source matrices cannot alter detached vertices");
        boolean invalid=false;try{DebugFrustumRenderer.snapshot(new Matrix4f().zero(),new Matrix4f(),true,1,1,1,1);}catch(IllegalArgumentException expected){invalid=true;}check(invalid,"Singular input rejected");
        System.out.println("Native debug geometry3checks PASS: native posed line attrs, copied perspective homogeneous corners, invalid matrix rejection");
    }
}
