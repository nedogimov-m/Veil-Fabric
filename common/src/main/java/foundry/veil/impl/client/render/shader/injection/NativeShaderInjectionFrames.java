package foundry.veil.impl.client.render.shader.injection;

import com.mojang.renderpearl.api.device.GpuDevice;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.commands.RenderPass;
import foundry.veil.api.client.render.shader.block.ShaderBlock;
import foundry.veil.api.client.render.shader.texture.ShaderTextureSource;

import java.util.*;
import java.util.function.Function;
import java.util.function.Supplier;

/** Prepares replacement resources at native frame entry. Native draw-owned blocks remain with each draw. */
public final class NativeShaderInjectionFrames implements AutoCloseable {
    private static NativeShaderInjectionFrames runtime;
    private static final Scope EMPTY = new Scope(null, null, null, Map.of());
    private final NativeShaderInjectionService service;
    private final GpuDevice device;
    private final ShaderTextureSource.Context textures;
    private final Function<Collection<String>, Map<String, ShaderBlock.Snapshot>> blocks;
    private final Supplier<? extends Collection<String>> layoutNames;
    private Scope current;
    private boolean closed;

    public NativeShaderInjectionFrames(NativeShaderInjectionService service, GpuDevice device,
            ShaderTextureSource.Context textures, Function<Collection<String>, Map<String, ShaderBlock.Snapshot>> blocks) {
        this(service, device, textures, blocks, Set::of);
    }
    public NativeShaderInjectionFrames(NativeShaderInjectionService service, GpuDevice device,
            ShaderTextureSource.Context textures, Function<Collection<String>, Map<String, ShaderBlock.Snapshot>> blocks,
            Supplier<? extends Collection<String>> layoutNames) {
        this.service = Objects.requireNonNull(service); this.device = Objects.requireNonNull(device);
        this.textures = Objects.requireNonNull(textures); this.blocks = Objects.requireNonNull(blocks);
        this.layoutNames = Objects.requireNonNull(layoutNames);
    }
    public static synchronized void bindRuntime(NativeShaderInjectionFrames owner) {
        if (runtime != null && runtime != owner) throw new IllegalStateException("Native shader resource frame runtime already bound");
        runtime = Objects.requireNonNull(owner);
    }
    public static synchronized void unbindRuntime(NativeShaderInjectionFrames owner) { if (runtime == owner) runtime = null; }
    public static Scope beginRuntimeFrame() {
        NativeShaderInjectionFrames owner; synchronized (NativeShaderInjectionFrames.class) { owner = runtime; }
        return owner == null ? EMPTY : owner.beginFrame();
    }
    private static Scope currentRuntimeFrame() {
        NativeShaderInjectionFrames owner; synchronized (NativeShaderInjectionFrames.class) { owner = runtime; }
        return owner == null ? null : owner.current;
    }
    public static boolean hasNamedBinding(String name) {
        Scope scope = currentRuntimeFrame(); GpuBufferSlice value = scope == null ? null : scope.uniforms.get(name);
        return value != null && !scope.closed && !value.buffer().isClosed();
    }
    public static Optional<AutoCloseable> retainNamedFrame() {
        Scope scope = currentRuntimeFrame();
        return scope == null || scope.closed ? Optional.empty() : Optional.of(scope.resources.retain());
    }
    /** Bind generated extra UBOs only from already prepared native scope snapshots. No GPU commands are prepared here. */
    public static void bindNamed(RenderPass pass, Set<String> names) {
        if (names.isEmpty()) return;
        Scope scope = currentRuntimeFrame();
        for (String name : names) {
            GpuBufferSlice value = scope == null ? null : scope.uniforms.get(name);
            if (value == null || scope.closed || value.buffer().isClosed())
                throw new IllegalStateException("Prepare the selected named native shader block before opening the pass: " + name);
            pass.setUniform(name, value);
        }
    }
    /** Called before native passes open; owned block values become independent encoder-owned transient UBO ranges. */
    public Scope beginFrame() {
        if (this.closed) throw new IllegalStateException("Native shader resource frame runtime is closed");
        Set<String> names = new HashSet<>(this.service.frameUniformBlocks());
        names.addAll(this.layoutNames.get());
        names.removeAll(Set.of("Projection", "DynamicTransforms", "Globals", "Fog", "Lighting"));
        names = Set.copyOf(names);
        Map<String, ShaderBlock.Snapshot> snapshots = new HashMap<>(Objects.requireNonNull(this.blocks.apply(names)));
        if (!names.containsAll(snapshots.keySet())) throw new IllegalArgumentException("Shader frame capture included unrequested native/global blocks");
        foundry.veil.api.client.render.shader.NativeShaderDefaults.snapshot(this.device)
                .ifPresent(snapshot -> snapshots.put(foundry.veil.api.client.render.shader.NativeShaderDefaults.BLOCK, snapshot));
        Map<String, GpuBufferSlice> uniforms = new HashMap<>();
        List<GpuBuffer> owned = new ArrayList<>();
        NativeShaderInjectionService.Frame resources;
        try {
            for (var entry : snapshots.entrySet()) {
                ShaderBlock.Snapshot snapshot = entry.getValue();
                if (snapshot.isBorrowed()) {
                    uniforms.put(entry.getKey(), snapshot.prepare(this.device.createCommandEncoder()));
                } else {
                    var bytes = snapshot.bytes();
                    // Host writes issue no pending upload. A scope can therefore retire an unused buffer immediately,
                    // and a nested scene submission cannot retire the enclosing view's immutable native storage.
                    GpuBuffer buffer = this.device.createBuffer(() -> "Veil native view block " + entry.getKey(),
                            GpuBuffer.USAGE_UNIFORM | GpuBuffer.USAGE_MAP_WRITE, bytes.remaining());
                    owned.add(buffer);
                    try (var mapped = buffer.map(false, true)) { mapped.data().put(bytes); }
                    uniforms.put(entry.getKey(), buffer.slice());
                }
            }
            resources = this.service.prepareFrame(this.device, this.textures, (request, name) -> uniforms.get(name));
            resources.own(() -> owned.forEach(GpuBuffer::close));
        } catch (RuntimeException | Error failure) { owned.forEach(GpuBuffer::close); throw failure; }
        Scope scope = new Scope(this, this.current, resources, Map.copyOf(uniforms)); this.current = scope; return scope;
    }
    public static final class Scope implements AutoCloseable {
        private final NativeShaderInjectionFrames owner;
        private final Scope previous;
        private final NativeShaderInjectionService.Frame resources;
        private final Map<String, GpuBufferSlice> uniforms;
        private boolean closed;
        private Scope(NativeShaderInjectionFrames owner, Scope previous, NativeShaderInjectionService.Frame resources, Map<String, GpuBufferSlice> uniforms) {
            this.owner = owner; this.previous = previous; this.resources = resources;
            this.uniforms = uniforms;
        }
        @Override public void close() {
            if (this.owner == null || this.closed) return;
            if (this.owner.current != this) throw new IllegalStateException("Native shader resource frame scopes must close in stack order");
            this.resources.close(); this.closed = true; this.owner.current = this.previous;
        }
    }
    @Override public void close() {
        if (this.closed) return;
        while (this.current != null) this.current.close();
        this.closed = true; unbindRuntime(this);
    }
}
