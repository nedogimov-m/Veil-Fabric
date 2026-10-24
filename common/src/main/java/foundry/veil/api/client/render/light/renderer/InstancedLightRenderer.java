package foundry.veil.api.client.render.light.renderer;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.renderpearl.api.pipeline.PrimitiveTopology;
import com.mojang.renderpearl.api.vertex.VertexFormat;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import com.mojang.renderpearl.api.textures.GpuSampler;
import foundry.veil.api.client.render.CullFrustum;
import foundry.veil.api.client.render.light.InstancedLightData;
import foundry.veil.api.client.render.light.data.LightData;
import foundry.veil.api.client.render.vertex.VertexArray;
import net.minecraft.resources.Identifier;
import org.joml.Vector3f;
import org.joml.Vector3fc;
import org.jetbrains.annotations.Nullable;
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;
import java.util.*;

/** Native instanced storage; each replacement survives all recorded mesh leases. */
public abstract class InstancedLightRenderer<T extends LightData & InstancedLightData> implements LightTypeRenderer<T> {
    protected final int lightSize;
    private final List<LightHandle> lights = new ArrayList<>();
    private final List<LightHandle> visible = new ArrayList<>();
    private final List<LightHandle> uploaded = new ArrayList<>();
    private final VertexArray mesh;
    private boolean freed;
    private boolean forceUpload = true;
    private @Nullable GpuTextureView voxelGrid;
    private @Nullable GpuSampler voxelSampler;
    private final Vector3f voxelOrigin = new Vector3f();

    protected InstancedLightRenderer(int lightSize) {
        this.lightSize = lightSize;
        this.mesh = VertexArray.create();
        var encoder = RenderSystem.getDevice().createCommandEncoder();
        try (var storage = new ByteBufferBuilder(256)) {
            var builder = new BufferBuilder(storage, PrimitiveTopology.TRIANGLE_STRIP, DefaultVertexFormat.POSITION);
            this.createMesh(builder);
            this.mesh.upload(encoder, builder.buildOrThrow(), VertexArray.DrawUsage.STATIC); encoder.submit();
        }
        catch (RuntimeException | Error failure) { this.mesh.close(); throw failure; }
    }
    protected abstract void createMesh(VertexConsumer builder);
    protected abstract VertexFormat instanceFormat();
    protected abstract Identifier shader();
    protected void storeInstance(T light, ByteBuffer buffer) { light.store(buffer); }

    public final void setVoxelGrid(GpuTextureView view, GpuSampler sampler, Vector3fc origin) {
        this.voxelGrid = Objects.requireNonNull(view); this.voxelSampler = Objects.requireNonNull(sampler); this.voxelOrigin.set(origin);
    }
    @Override public LightRenderHandle<T> addLight(T light) {
        if (this.freed) throw new IllegalStateException("Light renderer is retired");
        var handle = new LightHandle(Objects.requireNonNull(light)); this.lights.add(handle); return handle;
    }
    @Override public LightRenderHandle<T> steal(LightRenderHandle<T> handle) {
        if (this.lights.contains(handle) && handle.isValid()) return handle;
        T data = handle.getLightData(); handle.free(); return this.addLight(data);
    }
    @Override public void prepareLights(LightRenderer owner, CullFrustum frustum) {
        this.visible.clear();
        for (var handle : this.lights) if (handle.data.isVisible(frustum)) this.visible.add(handle);
    }
    @Override public void renderLights(LightRenderer owner) {
        if (this.visible.isEmpty()) return;
        boolean changed = this.forceUpload || !this.uploaded.equals(this.visible);
        if (!changed) for (var light : this.visible) if (light.revision != light.data.getRevision()) { changed = true; break; }
        var encoder = RenderSystem.getDevice().createCommandEncoder();
        try {
            if (changed) {
                ByteBuffer bytes = MemoryUtil.memAlloc(Math.multiplyExact(this.visible.size(), this.lightSize));
                try {
                    for (var light : this.visible) {
                        int offset = bytes.position(); this.storeInstance(light.data, bytes);
                        if (bytes.position() != offset + this.lightSize) throw new IllegalStateException("Incorrect native light instance stride");
                    }
                    bytes.flip(); this.mesh.uploadVertexBuffer(encoder, VertexArray.INSTANCE_BUFFER, bytes, this.instanceFormat(), VertexArray.DrawUsage.STREAM);
                } finally { MemoryUtil.memFree(bytes); }
            }
            owner.draw(encoder, this.shader(), this.mesh, this.visible.size(), true, program -> {
                if (this.voxelGrid != null) DDALightRenderer.uploadVoxelGridUniforms(program, this.voxelGrid, this.voxelSampler, this.voxelOrigin);
            });
            this.uploaded.clear(); this.uploaded.addAll(this.visible);
            for (var light : this.visible) light.revision = light.data.getRevision();
            this.forceUpload = false;
        } catch (RuntimeException | Error failure) { this.forceUpload = true; throw failure; }
    }
    @Override public List<? extends LightRenderHandle<T>> getLights() { return Collections.unmodifiableList(this.lights); }
    @Override public int getVisibleLights() { return this.visible.size(); }
    @Override public void free() {
        if (this.freed) return; this.freed = true; this.mesh.close();
        this.lights.clear(); this.visible.clear(); this.uploaded.clear();
    }
    private final class LightHandle implements LightRenderHandle<T> {
        private final T data;
        private long revision = Long.MIN_VALUE;
        private boolean removed;
        private LightHandle(T data) { this.data = data; }
        @Override public T getLightData() { return this.data; }
        @Override public void markDirty() { this.data.markDirty(); }
        @Override public boolean isValid() { return !freed && !this.removed; }
        @Override public void free() { if (this.removed) return; this.removed = true; lights.remove(this); visible.remove(this); forceUpload = true; }
    }
}
