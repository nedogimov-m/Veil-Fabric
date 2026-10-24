import com.google.gson.GsonBuilder;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.api.pipeline.*;
import foundry.veil.api.client.render.gpu.*;
import foundry.veil.api.client.util.ScissorStack;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.resources.Identifier;
import org.joml.Vector4f;
import org.lwjgl.system.MemoryStack;
import java.nio.file.*;
import java.util.*;

public final class NativeScissorAcceptance implements ClientModInitializer {
    private int ticks; private boolean ran;
    @Override public void onInitializeClient() { ClientTickEvents.END_CLIENT_TICK.register(client -> {
        if (ran || ++ticks < 5 || !client.isGameLoadFinished()) return; ran = true;
        var device = RenderSystem.getDevice(); var report = new LinkedHashMap<String,Object>();
        report.put("backend", device.getDeviceInfo().backendName()); report.put("device", device.getDeviceInfo().name());
        try {
            var id = Identifier.parse("veil_test:native_scissor");
            var pipeline = RenderPipeline.builder().withLocation(id).withVertexShader(id).withFragmentShader(id)
                    .withPrimitiveTopology(PrimitiveTopology.TRIANGLES).withCull(false).withColorTargetState(ColorTargetState.DEFAULT)
                    .withPushConstantSize(16).withBindGroupLayout(BindGroupLayout.builder().withUniform("ScissorColor", UniformType.UNIFORM_BUFFER).build()).build();
            var layout = GpuUniformBuffer.layoutBuilder().field("Value", GpuUniformBuffer.ValueType.VEC4).build();
            try (var source = new GpuShaderSource(client.getResourceManager()); var program = new GpuShaderProgram(device, pipeline, source, Runnable::run);
                 var target = GpuFramebuffer.builder("native exact scissor pixels",8,8).color(GpuFormat.RGBA8_UNORM).build(device);
                 var red = new GpuUniformBuffer(device,"native scissor red",layout); var green = new GpuUniformBuffer(device,"native scissor green",layout);
                 var blue = new GpuUniformBuffer(device,"native scissor blue",layout); var white = new GpuUniformBuffer(device,"native scissor white",layout);
                 var read = device.createBuffer(() -> "native scissor exact pixels",GpuBuffer.USAGE_COPY_DST|GpuBuffer.USAGE_MAP_READ,256)) {
                red.setVector("Value",1,0,0,1);green.setVector("Value",0,1,0,1);blue.setVector("Value",0,0,1,1);white.setVector("Value",1,1,1,1);
                var encoder = device.createCommandEncoder(); target.clear(encoder,new Vector4f(0,0,0,1),0);
                for (var color : List.of(red,green,blue,white)) color.upload(encoder);
                try (var pass = target.beginPass(encoder); var stack = new ScissorStack(pass,2,8,8)) {
                    stack.push(1,.5f,2,2); stack.draw(() -> draw(pass,program,red,-1,-1,2,2));
                    stack.push(2,1,2,2); stack.draw(() -> draw(pass,program,green,-1,-1,2,2));
                    stack.push(0,3,1,1); stack.draw(() -> { throw new AssertionError("Empty native clip issued a draw"); });
                    stack.pop(); stack.pop(); stack.draw(() -> draw(pass,program,blue,-1,-1,2,.5f));
                    stack.pop(); stack.draw(() -> draw(pass,program,white,.75f,.75f,.25f,.25f));
                    check(stack.isEmpty(),"Every explicit scissor region was restored/popped");
                }
                encoder.copyTextureToBuffer(target.color(0).texture(),read,0,()->{},0);
                try (var fence = encoder.createFence()) { encoder.submit(); check(fence.awaitCompletion(10_000_000_000L),"Native scissor GPU timeout"); }
                try (var mapped = read.map(true,false)) {
                    var bytes = mapped.data();
                    for (int y=0;y<8;y++) for(int x=0;x<8;x++) {
                        int[] expected = x==7&&y==7 ? new int[]{255,255,255,255}
                                : x>=2&&x<6&&y==1 ? new int[]{0,0,255,255}
                                : x>=4&&x<6&&y>=2&&y<5 ? new int[]{0,255,0,255}
                                : x>=2&&x<6&&y>=2&&y<5 ? new int[]{255,0,0,255} : new int[]{0,0,0,255};
                        for(int c=0;c<4;c++) check((bytes.get((y*8+x)*4+c)&255)==expected[c],"Incorrect actual native scissor pixel "+x+","+y+" component"+c);
                    }
                }
            }
            report.put("checks",List.of("exact actual native top-left scissor pixels with GUI scale2", "nested clipping and genuinely empty disjoint child", "parent scissor restored after two pops and last pop disables native clipping"));
            report.put("status","passed");
        } catch(Throwable failure) { failure.printStackTrace(); report.put("status","failed"); report.put("error",failure.toString()); }
        finally { try { Files.writeString(Path.of(System.getProperty("veil.scissor.report")),new GsonBuilder().setPrettyPrinting().create().toJson(report)); } catch(Exception failure){throw new RuntimeException(failure);}client.stop(); }
    }); }
    private static void draw(RenderPass pass,GpuShaderProgram program,GpuUniformBuffer color,float x,float y,float width,float height) {
        program.bind(pass,Map.of("ScissorColor",color.buffer().slice()));
        try(var memory=MemoryStack.stackPush()){var rectangle=memory.malloc(16);rectangle.putFloat(x).putFloat(y).putFloat(width).putFloat(height).flip();pass.pushConstants(rectangle);}
        pass.draw(6,1,0,0);
    }
    private static void check(boolean value,String message) { if(!value)throw new AssertionError(message); }
}
