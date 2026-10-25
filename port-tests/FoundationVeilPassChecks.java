import java.util.*;
import net.fabricmc.fabric.api.client.gametest.v1.context.*;

/** Genuine installed Veil algorithms on the native GPU, without source overlays. */
public final class FoundationVeilPassChecks {
    public static void run(ClientGameTestContext test,TestSingleplayerContext world,Map<String,Object> report,List<String> checks){
        var result=new LinkedHashMap<String,Object>();report.put("veilPasses",result);
        result.put("scope","Complete installed-mod world plus genuine native lighting, particles, bloom and texture preview/export GPU assertions. Identity/offscreen fixtures isolate exact pixels inside a supported native scene view. These API tests complement the real editor/world checks.");
        var selection=System.getProperty("foundation.passes","all");
        test.runOnClient(client->{
            try (var view=foundry.veil.impl.client.render.shader.injection.NativeShaderViews.beginScene(new org.joml.Matrix4f(),new org.joml.Matrix4f())) {
            if(selection.equals("all")||selection.equals("lights"))verify(result,"lights",NativeLightAcceptance.verifyNative(client));
            if(selection.equals("all")||selection.equals("particles"))verify(result,"particles",NativeParticleAcceptance.verifyNative(client));
            if(selection.equals("all")||selection.equals("bloom"))verify(result,"bloom",NativeBloomAcceptance.verifyNative(client));
            if(selection.equals("all")||selection.equals("textures"))verify(result,"textures",NativeEditorTextureAcceptance.verifyNative(client,java.nio.file.Path.of(System.getProperty("port.smoke.report")).resolveSibling("texture-exports")));
            }
        });
        test.waitTicks(6);result.put("screenshot",test.takeScreenshot("foundation-veil-native-gpu-passes-returned-to-world").toString());result.put("status","passed");
        checks.add("Installed Veil selected native GPU lighting/voxel/particle/bloom/texture algorithms produce the expected readback pixels; ownership returns to the real world after offscreen checks");
    }
    private static void verify(Map<String,Object> out,String name,Map<String,Object> value){out.put(name,value);if(!"passed".equals(value.get("status")))throw new AssertionError("Installed Veil "+name+" GPU assertions failed: "+value);}
    private FoundationVeilPassChecks(){}
}
