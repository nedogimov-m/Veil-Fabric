package foundry.veil.api.quasar.particle;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.renderpearl.api.vertex.VertexFormat;
import com.mojang.serialization.Codec;
import foundry.veil.api.client.render.VeilRenderSystem;
import foundry.veil.api.client.render.rendertype.VeilRenderType;
import foundry.veil.api.client.render.vertex.VeilVertexFormat;
import foundry.veil.api.client.render.vertex.VertexArray;
import foundry.veil.api.client.render.gpu.NativeParticlePass;
import foundry.veil.impl.quasar.QuasarParticleHandler;
import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import com.mojang.renderpearl.api.pipeline.PrimitiveTopology;
import org.lwjgl.system.MemoryUtil;
import foundry.veil.api.quasar.registry.RenderStyleRegistry;
import foundry.veil.api.util.CodecUtil;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.ApiStatus;
import org.joml.*;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.NativeResource;

import java.lang.Math;
import java.nio.ByteBuffer;
import java.util.List;


/**
 * Defines how a particle emitter renders a set of particles.
 *
 * @author Ocelot, BL
 */
public abstract class RenderStyle implements NativeResource {

    public static final Codec<RenderStyle> CODEC = CodecUtil.registryOrLegacyCodec(RenderStyleRegistry.REGISTRY);

    protected VertexArray vertexArray;
    protected ByteBufferBuilder meshStorage;
    protected final int bufferSize;
    protected int maxParticles;

    public static final int MIN_PARTICLES = 400;

    public RenderStyle(int bufferSize) {
        this.bufferSize = bufferSize;
        this.maxParticles = 0;
    }

    /**
     * Called after the Veil renderer is available, sets up anything necessary for rendering.
     *
     * @since 4.4.0
     */
    public void init() {
        if (this.bufferSize <= 0) {
            return;
        }

        if(this.vertexArray != null)this.vertexArray.close();
        this.vertexArray = VertexArray.create();
        try(var storage=new ByteBufferBuilder(1024)) {
            this.meshStorage=storage;
            var encoder=RenderSystem.getDevice().createCommandEncoder();
            this.vertexArray.upload(encoder,this.createMesh(),VertexArray.DrawUsage.STATIC);encoder.submit();
        } catch(RuntimeException|Error failure){this.vertexArray.close();this.vertexArray=null;throw failure;}
        finally{this.meshStorage=null;}
    }

    /**
     * Called before rendering any particles. This will not be fired if the emitter has no particles.
     *
     * @param particleCount The number of particles that will be rendered with {@link #render(List, Camera)}
     * @return Whether the particles are allowed to render
     * @since 1.3.0
     */
    public boolean setup(int particleCount) {
        return true;
    }

    /**
     * Called after rendering all particles.
     *
     * @since 1.3.0
     */
    public void clear() {
    }

    /**
     * Draws a list of particles using a batched VertexArray.
     *
     * @param particles A list of currently active particles
     * @param camera    The camera to use to render the particles
     * @since 4.4.0
     */
    public void render(List<QuasarParticle> particles, CameraRenderState camera) {
        if (particles.isEmpty()) {
            return;
        }

        RenderType renderType = particles.getFirst().getRenderData().getRenderType();
        if (renderType == null) {
            return;
        }

        List<QuasarParticle> visibleParticles=particles.stream().filter(particle->camera.cullFrustum.isVisible(
                new net.minecraft.world.phys.AABB(particle.getPosition().x-particle.getRenderData().getRenderRadius(),particle.getPosition().y-particle.getRenderData().getRenderRadius(),particle.getPosition().z-particle.getRenderData().getRenderRadius(),
                        particle.getPosition().x+particle.getRenderData().getRenderRadius(),particle.getPosition().y+particle.getRenderData().getRenderRadius(),particle.getPosition().z+particle.getRenderData().getRenderRadius()))).toList();
        if(visibleParticles.isEmpty())return;
        if(this.vertexArray==null)this.init();
        if(this.bufferSize!=NativeParticlePass.INSTANCES.getVertexSize())throw new IllegalStateException("RenderStyle requires a migrated native instance layout");
        var bytes=MemoryUtil.memAlloc(Math.multiplyExact(visibleParticles.size(),this.bufferSize));
        try {
            for(var particle:visibleParticles){int start=bytes.position();this.putBufferData(particle,camera,bytes);if(bytes.position()!=start+this.bufferSize)throw new IllegalStateException("Incorrect particle instance stride");}
            bytes.flip();QuasarParticleHandler.queue(renderType,this.vertexArray,bytes,visibleParticles.size(),camera);
        } finally{MemoryUtil.memFree(bytes);}
    }

    /**
     * @return The render type to use for the specified particle.
     * @apiNote This is only called when the render data is marked dirty
     * @see RenderData#markDirty()
     * @since 1.3.0
     */
    public RenderType getRenderType(QuasarParticle particle, RenderData renderData) {
        boolean additive = renderData.isAdditive();
        TextureAtlasSprite atlasSprite = renderData.getAtlasSprite();
        if (atlasSprite != null) {
            return VeilRenderType.quasarParticle(atlasSprite.atlasLocation(), additive);
        }

        SpriteData spriteData = renderData.getSpriteData();
        if (spriteData != null) {
            return VeilRenderType.quasarParticle(spriteData.sprite(), additive);
        }

        return VeilRenderType.quasarParticle(RenderData.BLANK, additive);
    }

    @Override
    public void free() {
        if(this.vertexArray!=null){this.vertexArray.close();this.vertexArray=null;}
    }

    /**
     * @return The MeshData to use for each particle.
     * @since 4.4.0
     */
    protected abstract MeshData createMesh();

    /**
     * Put information about each particle into the vertex attribute array.
     *
     * @param particle The particle being loaded
     * @param camera   The camera rendering the particles
     * @param buffer   The buffer of the current VertexArray
     * @since 4.4.0
     */
    protected abstract void putBufferData(QuasarParticle particle, CameraRenderState camera, ByteBuffer buffer);

    @ApiStatus.Internal
    public static final class Cube extends RenderStyle {
        private static final Vector3fc[] CUBE_POSITIONS = {
                // TOP
                new Vector3f(1, 1, -1), new Vector3f(1, 1, 1), new Vector3f(-1, 1, 1), new Vector3f(-1, 1, -1),

                // BOTTOM
                new Vector3f(-1, -1, -1), new Vector3f(-1, -1, 1), new Vector3f(1, -1, 1), new Vector3f(1, -1, -1),

                // FRONT
                new Vector3f(-1, -1, 1), new Vector3f(-1, 1, 1), new Vector3f(1, 1, 1), new Vector3f(1, -1, 1),

                // BACK
                new Vector3f(1, -1, -1), new Vector3f(1, 1, -1), new Vector3f(-1, 1, -1), new Vector3f(-1, -1, -1),

                // LEFT
                new Vector3f(-1, -1, -1), new Vector3f(-1, 1, -1), new Vector3f(-1, 1, 1), new Vector3f(-1, -1, 1),

                // RIGHT
                new Vector3f(1, -1, 1), new Vector3f(1, 1, 1), new Vector3f(1, 1, -1), new Vector3f(1, -1, -1)
        };
        private static final float[] CUBE_NORMALS = {0, 1, 0, 0, -1, 0, 0, 0, 1, 0, 0, -1, -1, 0, 0, 1, 0, 0};

        public Cube() {
            super(Float.BYTES * 25 + Short.BYTES * 2);
        }

        @Override
        protected MeshData createMesh() {
            BufferBuilder builder = new BufferBuilder(this.meshStorage, PrimitiveTopology.QUADS, VeilVertexFormat.QUASAR_PARTICLE);

            for (int i = 0; i < 6; i++) {
                for (int j = 0; j < 4; j++) {
                    Vector3fc pos = CUBE_POSITIONS[i * 4 + j];

                    builder.addVertex(pos.x(), pos.y(), pos.z());
                    builder.setNormal(CUBE_NORMALS[i * 3], CUBE_NORMALS[i * 3 + 1], CUBE_NORMALS[i * 3 + 2]);
                }
            }

            return builder.buildOrThrow();
        }

        @Override
        protected void putBufferData(QuasarParticle particle, CameraRenderState camera, ByteBuffer buffer) {
            RenderData renderData = particle.getRenderData();
            Vector3fc rotation = renderData.getRenderRotation();
            SpriteData spriteData = renderData.getSpriteData();

            Vector3dc position=renderData.getRenderPosition();
            Matrix4f transformationMatrix=NativeParticlePass.transform(position.x(),position.y(),position.z(),camera.pos.x,camera.pos.y,camera.pos.z,rotation,null);

            float uMin = 0;
            float vMin = 0;
            float uMax = 1;
            float vMax = 1;
            if (spriteData != null) {
                uMin = spriteData.u(renderData.getRenderAge(), renderData.getAgePercent(), uMin);
                uMax = spriteData.u(renderData.getRenderAge(), renderData.getAgePercent(), uMax);
                vMin = spriteData.v(renderData.getRenderAge(), renderData.getAgePercent(), vMin);
                vMax = spriteData.v(renderData.getRenderAge(), renderData.getAgePercent(), vMax);
            }
            if(renderData.getAtlasSprite()!=null){var sprite=renderData.getAtlasSprite();uMin=sprite.getU0();vMin=sprite.getV0();uMax=sprite.getU1();vMax=sprite.getV1();}
            NativeParticlePass.writeInstance(buffer,transformationMatrix,renderData.getRenderRadius(),uMin,vMin,uMax,vMax,
                    renderData.getRed(),renderData.getGreen(),renderData.getBlue(),renderData.getAlpha(),renderData.getPackedLight());
        }
    }

    @ApiStatus.Internal
    public static final class Billboard extends RenderStyle {

        private static final Vector3fc[] PLANE_POSITIONS = {
                // plane from -1 to 1 on Y axis and -1 to 1 on X axis
                new Vector3f(1, -1, 0), new Vector3f(1, 1, 0), new Vector3f(-1, 1, 0), new Vector3f(-1, -1, 0),
        };

        public Billboard() {
            super(Float.BYTES * 25 + Short.BYTES * 2);
        }

        @Override
        protected MeshData createMesh() {
            BufferBuilder builder = new BufferBuilder(this.meshStorage, PrimitiveTopology.QUADS, VeilVertexFormat.QUASAR_PARTICLE);

            for (int j = 0; j < 4; j++) {
                Vector3fc pos = PLANE_POSITIONS[j];

                builder.addVertex(pos.x(), pos.y(), pos.z());
                builder.setNormal(0, 0, -1);
            }

            return builder.buildOrThrow();
        }

        @Override
        protected void putBufferData(QuasarParticle particle, CameraRenderState camera, ByteBuffer buffer) {
            RenderData renderData = particle.getRenderData();
            Quaternionf faceCameraRotation = new Quaternionf(camera.orientation);
            Vector3fc rotation = renderData.getRenderRotation();
            SpriteData spriteData = renderData.getSpriteData();

            Vector3dc position=renderData.getRenderPosition();
            Matrix4f transformationMatrix=NativeParticlePass.transform(position.x(),position.y(),position.z(),camera.pos.x,camera.pos.y,camera.pos.z,rotation,faceCameraRotation);

            float uMin = 0;
            float vMin = 0;
            float uMax = 1;
            float vMax = 1;
            if (spriteData != null) {
                uMin = spriteData.u(renderData.getRenderAge(), renderData.getAgePercent(), uMin);
                uMax = spriteData.u(renderData.getRenderAge(), renderData.getAgePercent(), uMax);
                vMin = spriteData.v(renderData.getRenderAge(), renderData.getAgePercent(), vMin);
                vMax = spriteData.v(renderData.getRenderAge(), renderData.getAgePercent(), vMax);
            }
            if(renderData.getAtlasSprite()!=null){var sprite=renderData.getAtlasSprite();uMin=sprite.getU0();vMin=sprite.getV0();uMax=sprite.getU1();vMax=sprite.getV1();}
            NativeParticlePass.writeInstance(buffer,transformationMatrix,renderData.getRenderRadius(),uMin,vMin,uMax,vMax,
                    renderData.getRed(),renderData.getGreen(),renderData.getBlue(),renderData.getAlpha(),renderData.getPackedLight());
        }
    }
}
