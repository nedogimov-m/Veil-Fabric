package foundry.veil.api.client.render.gpu;

import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import com.mojang.renderpearl.api.commands.CommandEncoder;
import com.mojang.renderpearl.api.device.GpuDevice;
import com.mojang.renderpearl.api.vertex.VertexFormat;
import foundry.veil.api.client.render.vertex.VertexArray;
import java.nio.ByteBuffer;
import java.util.Map;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.joml.Quaternionf;
import org.joml.Quaternionfc;
import org.joml.Vector3fc;
import org.jetbrains.annotations.Nullable;

/** Actual native particle instance layout and upload-before-pass draw. */
public final class NativeParticlePass {
    public static final VertexFormat INSTANCES = VertexFormat.builder(1)
            .addAttribute("ParticleMat",GpuFormat.RGBA32_FLOAT,4).addAttribute("Scale",GpuFormat.R32_FLOAT)
            .addAttribute("UV0Min",GpuFormat.RG32_FLOAT).addAttribute("UV0Max",GpuFormat.RG32_FLOAT)
            .addAttribute("Color",GpuFormat.RGBA32_FLOAT).addAttribute("UV2",GpuFormat.RG16_SINT).build();
    public static Matrix4f transform(double x,double y,double z,double cameraX,double cameraY,double cameraZ,
                                    Vector3fc rotation,@Nullable Quaternionfc billboard) {
        var orientation=billboard==null?new Quaternionf():new Quaternionf(billboard);
        orientation.rotateLocalX(rotation.x()).rotateLocalY(rotation.y()).rotateLocalZ(rotation.z());
        return new Matrix4f().translation((float)(x-cameraX),(float)(y-cameraY),(float)(z-cameraZ)).rotate(orientation);
    }
    public static void writeInstance(ByteBuffer output,Matrix4fc transform,float scale,
            float uMin,float vMin,float uMax,float vMax,float red,float green,float blue,float alpha,int light) {
        transform.get(output.position(),output);output.position(output.position()+64);
        output.putFloat(scale).putFloat(uMin).putFloat(vMin).putFloat(uMax).putFloat(vMax)
                .putFloat(red).putFloat(green).putFloat(blue).putFloat(alpha)
                .putShort((short)light).putShort((short)(light>>>16));
    }
    /** Caller keeps the returned instance storage, source mesh and program alive through submit. */
    public static GpuBuffer record(GpuDevice device,CommandEncoder encoder,GpuFramebuffer target,
            GpuShaderProgram program,VertexArray.Draw mesh,ByteBuffer instances,int count,Map<String,GpuBufferSlice> blocks) {
        if(count<=0||instances.remaining()!=Math.multiplyExact(count,INSTANCES.getVertexSize()))
            throw new IllegalArgumentException("Incorrect native particle instance count/stride");
        if(program.definition().getVertexFormatBindings().size()<2||!INSTANCES.equals(program.definition().getVertexFormatBinding(1)))
            throw new IllegalArgumentException("Particle pipeline must declare its real native instance binding");
        var storage=device.createBuffer(()->"Veil particle instances",GpuBuffer.USAGE_VERTEX|GpuBuffer.USAGE_COPY_DST,instances.remaining());
        try {
            encoder.writeToBuffer(storage.slice(),instances.duplicate());program.prepare(encoder);
            try(var pass=target.beginPass(encoder)){program.bind(pass,blocks);pass.setVertexBuffer(1,storage.slice());mesh.drawInstanced(pass,count);}
            return storage;
        } catch(RuntimeException|Error failure){storage.close();throw failure;}
    }
    private NativeParticlePass() { }
}
