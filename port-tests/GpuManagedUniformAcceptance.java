import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.commands.CommandEncoder;
import com.mojang.renderpearl.api.device.GpuDevice;
import com.mojang.renderpearl.api.pipeline.*;
import foundry.veil.api.client.render.dynamicbuffer.DynamicBufferType;
import foundry.veil.api.client.render.gpu.*;
import foundry.veil.api.client.render.shader.*;
import foundry.veil.impl.client.render.dynamicbuffer.*;
import io.github.ocelot.glslprocessor.api.grammar.GlslTypeSpecifier;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.Identifier;
import net.minecraft.util.profiling.InactiveProfiler;
import org.joml.Matrix3f;
import org.joml.Matrix4f;

import java.nio.ByteOrder;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** Actual loose numeric GLSL declarations become one owned native schema with shared stage offsets. */
public final class GpuManagedUniformAcceptance {
    public static void run(Minecraft client, GpuDevice device, List<String> checks) {
        run(client,device,checks,false);
    }
    public static void runReload(Minecraft client, GpuDevice device, List<String> checks) {
        run(client,device,checks,true);
    }
    private static void run(Minecraft client, GpuDevice device, List<String> checks, boolean reloadOnly) {
        var definitions = new ShaderPreDefinitions();
        class Managed extends ShaderManager {
            Managed() { super(device, ShaderManager.PROGRAM_SET, definitions); }
            void load() { var resources = client.getResourceManager(); this.apply(this.prepare(resources, InactiveProfiler.INSTANCE), resources, InactiveProfiler.INSTANCE); }
        }
        var compiler = new VanillaShaderCompiler(device, Runnable::run, Runnable::run);
        var dynamic = new DynamicBufferManager(device, client.getResourceManager(), 2, 2, compiler, ignored -> {});
        NativeDynamicBuffers.bindRuntime(dynamic);
        try (compiler; dynamic; var shaders = new Managed();
             var output = GpuFramebuffer.builder("managed automatic numeric uniform output",2,2).color(GpuFormat.RGBA8_UNORM).build(device);
             var second = GpuFramebuffer.builder("managed automatic numeric uniform second output",2,2).color(GpuFormat.RGBA8_UNORM).build(device)) {
            var invalid = new AtomicBoolean(); int[] defaults = {0}; float[] gain = {.5f};
            shaders.addPreprocessorRegistry((resources, registry) -> registry.addPreprocessor((context, tree) -> {
                if (invalid.get() && context.isFragment() && context.isSourceFile())
                    tree.field("SharedTint").orElseThrow().getType().setSpecifier(GlslTypeSpecifier.BuiltinType.VEC3);
            },false));
            shaders.setGlobal(program -> {
                GpuUniformBuffer block = program.getUniformBlock(GpuShaderSource.MANAGED_UNIFORMS);
                check(block != null && program.hasUniform("SharedTint") && program.getUniform("Alphas").getArrayLength() == 2,
                        "Real source numeric fields must be discoverable through native owned metadata");
                var bytes = block.snapshot().bytes();
                for (int index=0; index<bytes.remaining(); index++) check(bytes.get(index)==0,"Discovered numeric uniforms must start at genuine zero defaults");
                defaults[0]++;
                values(program,gain[0],1,.5f,.25f);
            });
            shaders.load(); var id = Identifier.parse("veil:gpu_managed_uniforms");
            var state = RenderPipeline.builder().withLocation(Identifier.parse("veil_test:managed_uniforms"))
                    .withVertexShader(id).withFragmentShader(id).withPrimitiveTopology(PrimitiveTopology.TRIANGLES)
                    .withCull(false).withColorTargetState(ColorTargetState.DEFAULT).build();
            var program = shaders.getShader(id,state,Runnable::run);
            check(program.uniformLayouts().get(GpuShaderSource.MANAGED_UNIFORMS).fields().size()==9,
                    "Identical cross-stage uniform declarations must share one field, alongside all scalar/vector/matrix/array fields");
            if (reloadOnly) { reload(client,device,program,output,definitions,checks); return; }
            draw(device,program,output); assertTexture(device,output.color(0).texture(),.25f,.125f,.03125f,.375f);
            checks.add("real managed JSON with loose numeric GLSL uniforms creates an owned zero-initialized std140 schema shared across vertex/fragment; mat4/mat3, shared vec4, vec3/scalar packing, bool/uint/ivec and padded float array fields produce exact native pixels without JSON uniform_blocks");
            invalid.set(true); shaders.scheduleRecompile(id);
            try { shaders.getShader(id,state,Runnable::run); throw new AssertionError("Incompatible shared native stage uniform types were installed"); }
            catch (RuntimeException expected) { check(!program.isClosed(),"Invalid automatic uniform schema destroyed the working program"); }
            check(shaders.getShader(id,state,Runnable::run)==program,"A failed uniform schema must not retry unchanged source every draw");
            draw(device,program,output); assertTexture(device,output.color(0).texture(),.25f,.125f,.03125f,.375f);
            invalid.set(false); gain[0]=.25f; shaders.scheduleRecompile(id);
            var replacement = shaders.getShader(id,state,Runnable::run);
            check(program.isClosed() && replacement!=program && defaults[0]==2,"A valid later schema must install a fresh owned zero-initialized block and retire its old owner");
            draw(device,replacement,output); assertTexture(device,output.color(0).texture(),.125f,.0625f,.015625f,.375f);
            checks.add("incompatible actual vertex/fragment numeric declarations fail before native install, preserve old owner/pixels and avoid repeated retries; a later valid edit creates fresh owned fields, invokes real global setters and replaces the working native program");
            var encoder = device.createCommandEncoder();
            values(replacement,.5f,1,.5f,.25f); var first = replacement.prepareUniformSnapshots(encoder,replacement.snapshotOwnedUniforms());
            values(replacement,.25f,.5f,1,.25f); var later = replacement.prepareUniformSnapshots(encoder,replacement.snapshotOwnedUniforms());
            replacement.getUniform("Gain").setFloat(0);
            var owned = replacement.getUniformBlock(GpuShaderSource.MANAGED_UNIFORMS);
            var reads = new ArrayList<GpuBuffer>();
            dynamic.setActiveBuffers(Identifier.parse("veil_test:managed_uniforms"),DynamicBufferType.DEBUG.getMask());
            try (var lease = replacement.retain()) {
                shaders.close();
                try (var world = NativeDynamicBuffers.beginWorld(output.color(0).view(),null)) {
                    try (var pass = output.beginPass(encoder)) { replacement.bind(pass,first); pass.draw(3,1,0,0); }
                    reads.add(read(device,encoder,output.color(0).texture(),16));
                    reads.add(read(device,encoder,dynamic.getBufferTexture(DynamicBufferType.DEBUG).texture(),32));
                    try (var nested = NativeDynamicBuffers.beginWorld(second.color(0).view(),null)) {
                        try (var pass = second.beginPass(encoder)) { replacement.bind(pass,later); pass.draw(3,1,0,0); }
                        reads.add(read(device,encoder,second.color(0).texture(),16));
                        reads.add(read(device,encoder,dynamic.getBufferTexture(DynamicBufferType.DEBUG).texture(),32));
                    }
                    dynamic.close(); compiler.close();
                }
                check(!owned.isClosed() && !compiler.retirement().isDone(),"Queued snapshots and actual variants must retain owned uniforms/pipelines until submission");
                try (var fence=encoder.createFence()) { encoder.submit(); check(fence.awaitCompletion(10_000_000_000L),"Managed automatic uniform GPU submission timed out"); }
                assertPixels(reads.get(0),false,.25f,.125f,.03125f,.375f);
                assertPixels(reads.get(1),true,1,.5f,.25f,.125f);
                assertPixels(reads.get(2),false,.03125f,.25f,.015625f,.375f);
                assertPixels(reads.get(3),true,.5f,1,.25f,.125f);
            } finally { reads.forEach(GpuBuffer::close); }
            check(owned.isClosed() && compiler.retirement().isDone(),"Final actual draw leases must close the retired automatic uniform and native variant owners");
            checks.add("two detached automatic-field snapshots drive different queued native/dynamic pixels after live mutation and manager retirement; shared owned field offsets replay unchanged, all debug components survive and genuine draw/submission leases close retired owners exactly afterward");
        } finally { NativeDynamicBuffers.unbindRuntime(dynamic); }
    }
    private static void reload(Minecraft client,GpuDevice device,GpuShaderProgram program,GpuFramebuffer output,
                               ShaderPreDefinitions definitions,List<String> checks) {
        draw(device,program,output); assertTexture(device,output.color(0).texture(),.25f,.125f,.03125f,.375f);
        var oldBlock=program.getUniformBlock(GpuShaderSource.MANAGED_UNIFORMS);
        var gain=program.getUniform("Gain"); int oldOffset=gain.getOffset();
        try(var source=reloadSource(client,program,definitions,true,false)) { program.reload(source,Runnable::run); }
        var changed=program.getUniformBlock(GpuShaderSource.MANAGED_UNIFORMS);
        check(oldBlock.isClosed()&&changed!=oldBlock&&gain.getOffset()!=oldOffset&&Math.abs(gain.getFloat()-.5f)<.001f,
                "Direct native reload must migrate compatible fields by name to actual changed offsets and close old storage");
        check(program.getUniform("ReloadTint").getFloat()==0,"Newly discovered reload fields must retain zero defaults");
        draw(device,program,output);assertTexture(device,output.color(0).texture(),0,0,0,0);
        program.getUniform("ReloadTint").setVector(.5f,1,1,1);
        draw(device,program,output);assertTexture(device,output.color(0).texture(),.125f,.125f,.03125f,.375f);
        try(var source=reloadSource(client,program,definitions,true,true)) {
            try { program.reload(source,Runnable::run); throw new AssertionError("Invalid direct owned uniform schema replaced working storage"); }
            catch(RuntimeException expected) { check(program.getUniformBlock(GpuShaderSource.MANAGED_UNIFORMS)==changed&&!changed.isClosed(),"Failed direct reload retired actual working owned fields"); }
        }
        draw(device,program,output);assertTexture(device,output.color(0).texture(),.125f,.125f,.03125f,.375f);
        var removed=program.getUniform("ReloadTint");
        try(var source=reloadSource(client,program,definitions,false,false)) { program.reload(source,Runnable::run); }
        check(changed.isClosed()&&!removed.isValid()&&program.getUniform("ReloadTint")==null&&!program.hasUniform("ReloadTint"),
                "Successful direct reload must remove retired fields from live native metadata/accessors");
        draw(device,program,output);assertTexture(device,output.color(0).texture(),.25f,.125f,.03125f,.375f);
        checks.add("direct actual native program reload transactionally resizes discovered std140 fields, preserves compatible values by name across changed offsets, zeroes added fields, updates existing field accessors, preserves working owner/pixels after an incompatible stage schema, and removes stale fields/storage on a later valid edit");
    }
    private static GpuShaderSource reloadSource(Minecraft client,GpuShaderProgram program,ShaderPreDefinitions definitions,boolean extra,boolean invalid) {
        var processor=new foundry.veil.impl.client.render.shader.processor.NativeShaderProcessor(client.getResourceManager(),definitions,
                program.resourceDefinition().orElseThrow(),Map.of(),new HashSet<>(),0,List.of((resources,registry)->registry.addPreprocessor((context,tree)->{
                    if(!context.isSourceFile()||!context.isFragment())return;
                    if(extra) {
                        tree.getBody().add(0,io.github.ocelot.glslprocessor.api.GlslParser.parseExpression("uniform vec4 ReloadTint"));
                        tree.mainFunction().orElseThrow().getBody().add(io.github.ocelot.glslprocessor.api.GlslParser.parseExpression("fragColor *= ReloadTint"));
                    }
                    if(invalid)tree.field("SharedTint").orElseThrow().getType().setSpecifier(GlslTypeSpecifier.BuiltinType.VEC3);
                },false)));
        return new GpuShaderSource(client.getResourceManager(),ShaderManager.PROGRAM_SET.getFolder(),"pinwheel/shaders/include",Map.of(),processor);
    }
    private static void values(GpuShaderProgram program,float gain,float r,float g,float b) {
        program.getUniform("Transform").setMatrix(new Matrix4f());
        program.getUniform("SharedTint").setVector(r,g,b,1);
        program.getUniform("UseVertex").setInt(1);
        program.getUniform("RGBScale").setVector(.5f,1,1);
        program.getUniform("Gain").setFloat(gain);
        program.getUniform("Alphas").setFloats(.125f,.75f);
        program.getUniform("Enabled").setInt(1);
        program.getUniform("Flags").setVectorI(2,3);
        program.getUniform("AlphaMatrix").setMatrix(new Matrix3f().scale(.5f));
    }
    private static void draw(GpuDevice device,GpuShaderProgram program,GpuFramebuffer output) {
        var encoder=device.createCommandEncoder(); program.prepare(encoder);
        try(var pass=output.beginPass(encoder)) { program.bind(pass); pass.draw(3,1,0,0); } encoder.submit();
    }
    private static GpuBuffer read(GpuDevice device,CommandEncoder encoder,com.mojang.renderpearl.api.textures.GpuTexture texture,int size) {
        var read=device.createBuffer(()->"managed discovered field exact readback",GpuBuffer.USAGE_COPY_DST|GpuBuffer.USAGE_MAP_READ,size);
        encoder.copyTextureToBuffer(texture,read,0,()->{},0); return read;
    }
    private static void assertTexture(GpuDevice device,com.mojang.renderpearl.api.textures.GpuTexture texture,float... expected) {
        var encoder=device.createCommandEncoder();
        try(var read=read(device,encoder,texture,16);var fence=encoder.createFence()) { encoder.submit();check(fence.awaitCompletion(10_000_000_000L),"Managed field readback timed out");assertPixels(read,false,expected); }
    }
    private static void assertPixels(GpuBuffer read,boolean half,float... expected) {
        try(var mapped=read.map(true,false)) {
            var bytes=mapped.data().order(ByteOrder.nativeOrder());
            for(int index=0;index<bytes.remaining()/(half?2:1);index++) {
                float value=half?Float.float16ToFloat(bytes.getShort(index*2)):(bytes.get(index)&255)/255f;
                check(Math.abs(value-expected[index%4])<.005f,"Managed discovered uniform component "+index+" was "+value+", expected "+expected[index%4]);
            }
        }
    }
    private static void check(boolean value,String message) { if(!value)throw new AssertionError(message); }
}
