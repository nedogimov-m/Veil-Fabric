package foundry.veil.api.client.util;

import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.device.GpuDevice;
import com.mojang.renderpearl.api.pipeline.*;
import com.mojang.renderpearl.api.textures.*;
import foundry.veil.api.client.render.gpu.*;
import net.minecraft.resources.Identifier;
import java.util.*;

/** Real sampled RGBA8 previews, including depth, HDR, integer, cube faces and array layers. */
public final class NativeTexturePreview implements AutoCloseable {
    public enum Projection { TEXTURE_2D, CUBEMAP, CUBEMAP_FACE, ARRAY }
    private static final Identifier VERTEX=Identifier.parse("veil:native/texture_preview_vertex");
    private static final Identifier FRAGMENT=Identifier.parse("veil:native/texture_preview_fragment");
    private static final GpuUniformBuffer.Layout SETTINGS=GpuUniformBuffer.layoutBuilder().field("Settings",GpuUniformBuffer.ValueType.VEC4).build();
    private final GpuDevice device;
    private final GpuSampler sampler;
    private final Map<Variant,GpuShaderProgram> programs=new HashMap<>();
    private GpuFramebuffer output;
    private boolean closed;
    public NativeTexturePreview(GpuDevice device){
        this.device=Objects.requireNonNull(device);
        sampler=device.createSampler(AddressMode.CLAMP_TO_EDGE,AddressMode.CLAMP_TO_EDGE,FilterMode.NEAREST,FilterMode.NEAREST,1,OptionalDouble.empty());
    }
    /** The returned image remains preview-owned. Source ownership always stays with the caller. */
    public GpuTextureView render(GpuTextureView source,Projection projection,int layer,int mip){
        if(closed)throw new IllegalStateException("Texture preview is closed");
        if(source.isClosed()||source.texture().isClosed())throw new IllegalArgumentException("Preview source has expired");
        if(mip<0||mip>=source.mipLevels())throw new IllegalArgumentException("Preview mip is out of range");
        if(projection==Projection.CUBEMAP_FACE&&(layer<0||layer>=6))throw new IllegalArgumentException("Cubemap face is out of range");
        if(projection==Projection.ARRAY&&(layer<0||layer>=source.texture().getDepthOrLayers()))throw new IllegalArgumentException("Array layer is out of range");
        final int width=source.getWidth(mip)*(projection==Projection.CUBEMAP?2:1),height=source.getHeight(mip);
        if(output==null||output.width()!=width||output.height()!=height){
            final var replacement=GpuFramebuffer.builder("Veil native texture preview",width,height).color(GpuFormat.RGBA8_UNORM).build(device);
            final var previous=output;output=replacement;if(previous!=null)previous.close();
        }
        final var format=source.texture().getFormat();
        final var program=programs.computeIfAbsent(new Variant(projection,format),this::createProgram);
        final float scale=switch(format.componentType()){
            case UINT_8->1f/255;case SINT_8->1f/127;case UINT_16->1f/65535;case SINT_16->1f/32767;
            case UINT_32->1f/4294967295f;case SINT_32->1f/2147483647f;default->1;
        };
        program.setTexture("SourceSampler",source,sampler);
        program.getUniformBlock("Preview").setVector("Settings",layer,mip,format.hasDepthAspect()||format==GpuFormat.S8_UINT?1:0,scale);
        final var encoder=device.createCommandEncoder();program.prepare(encoder);
        try(var pass=output.beginPass(encoder)){program.bind(pass);pass.draw(3,1,0,0);}encoder.submit();return output.color(0).view();
    }
    private GpuShaderProgram createProgram(Variant variant){
        final String prefix=switch(variant.format.componentType()){
            case UINT_8,UINT_16,UINT_32->"u";case SINT_8,SINT_16,SINT_32->"i";default->"";
        };
        final String samplerType=prefix+switch(variant.projection){case TEXTURE_2D->"sampler2D";case CUBEMAP,CUBEMAP_FACE->"samplerCube";case ARRAY->"sampler2DArray";};
        final String coordinates=switch(variant.projection){
            case TEXTURE_2D->"texCoord";
            case ARRAY->"vec3(texCoord,Settings.x)";
            case CUBEMAP->"vec3(cos(texCoord.x*6.28318530718)*cos((0.5-texCoord.y)*3.14159265359),sin((0.5-texCoord.y)*3.14159265359),sin(texCoord.x*6.28318530718)*cos((0.5-texCoord.y)*3.14159265359))";
            case CUBEMAP_FACE->"cubeFace(int(Settings.x),texCoord*2.0-1.0)";
        };
        final String vertex="""
                #version 450
                layout(location=0) out vec2 texCoord;
                void main(){vec2 uv=vec2((gl_VertexIndex<<1)&2,gl_VertexIndex&2);texCoord=uv;gl_Position=vec4(uv*2.0-1.0,0,1);}
                """;
        final String fragment="#version 450\nlayout(location=0) in vec2 texCoord;layout(location=0) out vec4 fragColor;\n"
                +"layout(std140) uniform Preview{vec4 Settings;};uniform "+samplerType+" SourceSampler;\n"
                +"vec3 cubeFace(int face,vec2 p){if(face==0)return vec3(1,-p.y,-p.x);if(face==1)return vec3(-1,-p.y,p.x);if(face==2)return vec3(p.x,1,p.y);if(face==3)return vec3(p.x,-1,-p.y);if(face==4)return vec3(p.x,-p.y,1);return vec3(-p.x,-p.y,-1);}\n"
                +"void main(){vec4 sampled=vec4(textureLod(SourceSampler,"+coordinates+",Settings.y))*Settings.w;"
                +(variant.format.componentCount()<4?"sampled.a=1.0;":"")
                +"fragColor=Settings.z>0.5?vec4(sampled.rrr,1):sampled;}";
        final var pipeline=RenderPipeline.builder().withLocation(FRAGMENT).withVertexShader(VERTEX).withFragmentShader(FRAGMENT)
                .withPrimitiveTopology(PrimitiveTopology.TRIANGLES).withCull(false).withDepthStencilState(Optional.empty())
                .withBindGroupLayout(BindGroupLayout.builder().withUniform("Preview",UniformType.UNIFORM_BUFFER).withUniform("SourceSampler",UniformType.COMBINED_IMAGE_SAMPLER).build())
                .withColorTargetState(new ColorTargetState(Optional.empty(),GpuFormat.RGBA8_UNORM,ColorTargetState.WRITE_ALL)).build();
        try(var source=new ShaderSource(){
            public String getShader(Identifier id,ShaderType type){return type==ShaderType.VERTEX?vertex:fragment;}
            public CachedIncludeSource getInclude(Identifier id){throw new IllegalArgumentException("Texture preview shader has no include: "+id);}
            public void close(){}
        }){final var program=new GpuShaderProgram(device,pipeline,source,Runnable::run);program.createUniformBlock("Preview",SETTINGS);return program;}
    }
    @Override public void close(){if(closed)return;closed=true;programs.values().forEach(GpuShaderProgram::close);programs.clear();if(output!=null)output.close();sampler.close();}
    private record Variant(Projection projection,GpuFormat format){}
}
