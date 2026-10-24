import com.google.gson.GsonBuilder;
import foundry.veil.api.client.util.NativeBlockModelBaker;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.resources.Identifier;
import java.io.StringReader;
import java.nio.file.*;
import java.util.*;

/** New editor-native model bake over the real loaded Minecraft atlases/resources only. */
public final class NativeBlockModelBakerAcceptance implements ClientModInitializer {
    private int ticks;private boolean ran;
    @Override public void onInitializeClient(){ClientTickEvents.END_CLIENT_TICK.register(client->{
        if(ran||++ticks<5||!client.isGameLoadFinished())return;ran=true;final var report=new LinkedHashMap<String,Object>();
        try{
            final var id=Identifier.parse("veil:editor_native_bake");final var resources=client.getResourceManager();final var atlases=client.getAtlasManager();
            final var root=new StringReader("{\"parent\":\"minecraft:block/cube_all\",\"textures\":{\"all\":\"minecraft:block/diamond_block\"}}");
            final var cube=NativeBlockModelBaker.bake(id,root,resources,atlases);
            check(cube.all().size()==6,"Actual inherited native cube geometry has six faces");
            for(var quad:cube.all()){
                check(quad.materialInfo().sprite().contents().name().equals(Identifier.parse("minecraft:block/diamond_block")),"Native parent texture slot resolves to actual resident diamond sprite");
                check(quad.materialInfo().itemRenderType()!=null,"Native material retains genuine item render pipeline");
                for(int vertex=0;vertex<4;vertex++){final var pos=quad.position(vertex);check(pos.x()>=0&&pos.x()<=1&&pos.y()>=0&&pos.y()<=1&&pos.z()>=0&&pos.z()<=1,"Actual native cuboid positions stay in block units");}
            }
            final var rotated=NativeBlockModelBaker.bake(id,new StringReader("{\"textures\":{\"all\":\"minecraft:block/stone\"},\"elements\":[{\"from\":[0,0,0],\"to\":[16,16,16],\"rotation\":{\"origin\":[8,8,8],\"axis\":\"y\",\"angle\":45,\"rescale\":true},\"faces\":{\"north\":{\"texture\":\"#all\",\"uv\":[0,0,16,16],\"rotation\":90}}}]}"),resources,atlases);
            check(rotated.all().size()==1,"Edited root element replaces parent geometry through native baking");
            boolean outside=false;for(int vertex=0;vertex<4;vertex++){final var pos=rotated.all().getFirst().position(vertex);check(Float.isFinite(pos.x())&&Float.isFinite(pos.y())&&Float.isFinite(pos.z()),"Native rotated/rescaled vertices remain finite");outside|=pos.x()<0||pos.x()>1||pos.z()<0||pos.z()>1;}
            check(outside,"Actual 45-degree element rotation/rescale changes native geometry");
            final var missing=NativeBlockModelBaker.bake(id,new StringReader("{\"parent\":\"minecraft:block/cube_all\",\"textures\":{\"all\":\"veil:missing_editor_texture\"}}"),resources,atlases);
            final var missingSprite=atlases.getAtlasOrThrow(net.minecraft.data.AtlasIds.BLOCKS).missingSprite();
            check(missing.all().size()==6&&missing.all().stream().allMatch(quad->quad.materialInfo().sprite()==missingSprite),"Missing material uses the actual vanilla atlas missing sprite");
            try{NativeBlockModelBaker.bake(id,new StringReader("{\"parent\":\"veil:editor_native_bake\"}"),resources,atlases);throw new AssertionError("Cyclic parent was accepted");}catch(IllegalArgumentException expected){}
            report.put("status","passed");report.put("checks",List.of("actual parent geometry and texture-slot inheritance over resident native atlases","native MaterialBaker/FaceBakery item render types and block-unit positions","edited root rotation/rescale/UV parsing drives real native geometry","actual vanilla missing-material fallback and cyclic parent rejection"));
        }catch(Throwable failure){failure.printStackTrace();report.put("status","failed");report.put("error",failure.toString());}
        finally{try{Files.writeString(Path.of(System.getProperty("veil.block.model.bake.report")),new GsonBuilder().setPrettyPrinting().create().toJson(report));}catch(Exception failure){throw new RuntimeException(failure);}client.stop();}
    });}
    private static void check(boolean value,String message){if(!value)throw new AssertionError(message);}
}
