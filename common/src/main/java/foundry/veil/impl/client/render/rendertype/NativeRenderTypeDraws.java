package foundry.veil.impl.client.render.rendertype;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.systems.ScissorState;
import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import com.mojang.renderpearl.api.commands.CommandEncoder;
import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import foundry.veil.api.client.render.framebuffer.FramebufferManager;
import foundry.veil.api.client.render.framebuffer.VeilFramebuffers;
import foundry.veil.api.client.render.gpu.GpuFramebuffer;
import foundry.veil.api.client.render.gpu.GpuShaderProgram;
import foundry.veil.api.client.render.gpu.GpuUniformBuffer;
import foundry.veil.api.client.render.rendertype.VeilRenderTypeBuilder;
import foundry.veil.api.client.render.rendertype.NativeRenderTypeOutput;
import foundry.veil.api.client.render.shader.ShaderManager;
import foundry.veil.api.client.render.shader.block.ShaderBlock;
import foundry.veil.api.client.render.shader.texture.ShaderTextureSource;
import foundry.veil.api.client.render.texture.TextureFilter;
import foundry.veil.api.client.render.vertex.VertexArray;
import foundry.veil.mixin.rendertype.accessor.RenderTypeAccessor;
import foundry.veil.api.event.VeilRenderLevelStageEvent;
import foundry.veil.impl.client.render.pipeline.VeilShaderBlockState;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.StagedVertexBuffer;
import net.minecraft.client.renderer.rendertype.PreparedRenderType;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.resources.Identifier;
import org.jetbrains.annotations.Nullable;

import java.util.*;
import java.util.concurrent.Executor;
import java.util.function.Consumer;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;

/**
 * Reuses the real native staged mesh for each layer. Vanilla-only layers execute in their original
 * pass; managed shaders and alternate targets retain detached draw inputs until END_MAIN, where
 * owned uniform uploads and explicit output passes are legal. All retained buffers expire this frame.
 */
public final class NativeRenderTypeDraws {
    private static final Map<RenderType, Definition> TYPES = Collections.synchronizedMap(new WeakHashMap<>());
    private static final Map<PreparedRenderType, List<Piece>> PREPARED = Collections.synchronizedMap(new WeakHashMap<>());
    private static final List<Pending> PENDING = new ArrayList<>();
    private static final Map<Identifier,NativeRenderTypeOutput> OUTPUTS=new HashMap<>();
    private static final ThreadLocal<Boolean> DRAWING_NATIVE = ThreadLocal.withInitial(() -> false);
    private static final ThreadLocal<Capture> CAPTURE = new ThreadLocal<>();
    private static Runtime runtime;
    private static @Nullable VeilShaderBlockState uniformBlocks;
    public static void register(RenderType type, VeilRenderTypeBuilder.State state, int bufferSize) {
        final var previous = TYPES.put(type, new Definition(type, state, bufferSize));
        if (previous != null) previous.release();
    }
    public static void replace(RenderType type, VeilRenderTypeBuilder.State state) {
        final var previous = TYPES.get(type);
        register(type, state, previous != null ? previous.bufferSize : RenderType.TRANSIENT_BUFFER_SIZE);
    }
    public static @Nullable VeilRenderTypeBuilder.State state(RenderType type) {
        final var definition = TYPES.get(type); return definition == null ? null : definition.state;
    }
    public static void bindRuntime(ShaderManager shaders, FramebufferManager framebuffers, Executor workers) {
        if (runtime != null && (runtime.shaders != shaders || runtime.framebuffers != framebuffers)) {
            if (!PENDING.isEmpty()) throw new IllegalStateException("Cannot replace render-type owners with pending frame draws");
            TYPES.values().forEach(Definition::release);
        }
        runtime = new Runtime(Objects.requireNonNull(shaders), Objects.requireNonNull(framebuffers), Objects.requireNonNull(workers));
    }
    public static void bindUniformBlocks(VeilShaderBlockState blocks) { uniformBlocks = Objects.requireNonNull(blocks); }
    public static void registerOutput(Identifier name,NativeRenderTypeOutput output){OUTPUTS.put(Objects.requireNonNull(name),Objects.requireNonNull(output));}
    public static void unregisterOutput(Identifier name){OUTPUTS.remove(name);}
    public static @Nullable NativeRenderTypeOutput output(Identifier name){return OUTPUTS.get(name);}
    /** Shutdown drops unsubmitted draws and releases their native owners before manager disposal. */
    public static void unbindRuntime() {
        new Submission(List.copyOf(PENDING)).close();
        PENDING.clear(); PREPARED.clear();
        TYPES.values().forEach(Definition::release);
        runtime = null; uniformBlocks = null;OUTPUTS.clear();
    }
    public static void capture(RenderType type, PreparedRenderType prepared) {
        final var definition = TYPES.get(type);
        PREPARED.put(prepared, List.of(new Piece(type, prepared, definition)));
    }
    public static void layered(PreparedRenderType first, List<PreparedRenderType> layers) {
        final var pieces = new ArrayList<Piece>();
        for (var layer : layers) pieces.addAll(PREPARED.getOrDefault(layer, List.of(new Piece(null, layer, null))));
        PREPARED.put(first, List.copyOf(pieces));
    }
    /** Called by the actual PreparedRenderType draw mixin. */
    public static boolean draw(PreparedRenderType prepared, StagedVertexBuffer.ExecuteInfo geometry, RenderPass pass) {
        if (DRAWING_NATIVE.get()) return false;
        final var pieces = PREPARED.get(prepared);
        if (pieces == null || pieces.size() == 1 && !pieces.getFirst().deferred()) return false;
        for (Piece piece : pieces) {
            if (piece.deferred()) enqueue(piece, geometry, null, null);
            else {
                DRAWING_NATIVE.set(true);
                try { piece.prepared.drawFromBuffer(geometry, pass); }
                finally { DRAWING_NATIVE.set(false); }
            }
        }
        return true;
    }
    /** Captures every ordered layer with draw-owned material values before the caller resets its properties.
     * The mesh lease survives shell reload/free. Call from native collection or a Capture before passes open. */
    public static void captureMesh(RenderType type, VertexArray mesh, Matrix4fc modelView, Consumer<@Nullable GpuShaderProgram> material) {
        RenderSystem.assertOnRenderThread();
        PreparedRenderType prepared = type.prepare();
        var pieces = PREPARED.getOrDefault(prepared, List.of(new Piece(type, prepared, TYPES.get(type))));
        List<Pending> queue = queue(); int start = queue.size();
        try {
            for (Piece piece : pieces) {
                if (piece.type == null) throw new IllegalStateException("Native render type has no captured transform owner");
                var transform = ((RenderTypeAccessor) (Object) piece.type).veil$writeDynamicTransforms(new Matrix4f(modelView));
                var transformed = new PreparedRenderType(piece.prepared.name(), piece.prepared.pipeline(), null, transform,
                        piece.prepared.scissorState(), piece.prepared.textures());
                VertexArray.Draw lease = mesh.retain();
                try { enqueue(new Piece(piece.type, transformed, piece.definition), lease.stagedGeometry(piece.type), lease, material); }
                catch (RuntimeException | Error failure) { lease.close(); throw failure; }
            }
        } catch (RuntimeException | Error failure) {
            new Submission(List.copyOf(queue.subList(start, queue.size()))).close(); queue.subList(start, queue.size()).clear();
            throw failure;
        }
    }
    /** Explicit target and queue ownership for offscreen scenes; submit while the scene's native view is active. */
    public static Capture beginCapture(GpuFramebuffer target) {
        RenderSystem.assertOnRenderThread(); Capture capture = new Capture(Objects.requireNonNull(target)); CAPTURE.set(capture); return capture;
    }
    private static List<Pending> queue() { Capture capture = CAPTURE.get(); return capture == null ? PENDING : capture.draws; }
    private static void enqueue(Piece piece, StagedVertexBuffer.ExecuteInfo geometry, @Nullable VertexArray.Draw geometryLease,
                                @Nullable Consumer<@Nullable GpuShaderProgram> material) {
        if (runtime == null) throw new IllegalStateException("Veil native render-type runtime has not been bound");
        final var state = piece.definition == null ? null : piece.definition.state;
        final var route=state != null && state.output()!=null?OUTPUTS.get(state.output()):null;
        final var output=route!=null?route.acquire():null;
        if(route!=null&&output==null){if(geometryLease!=null)geometryLease.close();return;}
        Capture capture = CAPTURE.get();
        final boolean main = state == null || state.output() == null || state.output().equals(VeilFramebuffers.MAIN);
        final boolean borrowed = output==null&&main&&capture==null;
        final GpuFramebuffer target = output!=null?output.framebuffer():main && capture != null ? capture.target : borrowed
                ? GpuFramebuffer.borrow(RenderSystem.getDevice(), "Veil deferred main", Minecraft.getInstance().gameRenderer.mainRenderTarget())
                : runtime.framebuffers.getFramebuffer(state.output());
        if (target == null) throw new IllegalStateException("Unknown render-type framebuffer: " + state.output());
        GpuShaderProgram.Lease lease = null;
        try {
            final var pipeline = piece.definition == null ? piece.prepared.pipeline() : piece.definition.pipeline(target);
            var external = standardUniforms(piece.prepared);
            GpuShaderProgram program = null;
            Map<String, GpuUniformBuffer.Snapshot> uniforms = Map.of();
            Map<String, ShaderBlock.Snapshot> blocks = Map.of();
            Map<String, GpuShaderProgram.TextureBinding> textures = Map.of();
            if (state != null && state.veilShader() != null) {
                program = runtime.shaders.getShader(state.veilShader(), pipeline, runtime.workers);
                lease = program.retain();
                program.clearTextureBindings();
                runtime.shaders.bindTextures(state.veilShader(), program, runtime);
                for (var texture : piece.prepared.textures()) if (program.hasUniform(texture.name()))
                    program.setTexture(texture.name(), texture.textureView(), texture.sampler());
                final var bindings = new LinkedHashMap<>(program.snapshotExternalUniforms());
                final var bindingTypes = program.bindingTypes();
                external.forEach((name, value) -> {
                    if (bindingTypes.get(name) == com.mojang.renderpearl.api.pipeline.UniformType.UNIFORM_BUFFER) bindings.put(name, value);
                });
                external = bindings;
                if (material != null) material.accept(program);
                uniforms = program.snapshotOwnedUniforms();
                if (uniformBlocks != null) blocks = uniformBlocks.snapshot(program);
                textures = program.snapshotTextures();
                for (var texture : textures.values()) {
                    for (int index = 0; index < target.colorCount(); index++)
                        if (texture.view().texture() == target.color(index).texture()) throw new IllegalArgumentException("Render type samples its color output: " + state.veilShader());
                    if (target.hasDepth() && texture.view().texture() == target.depth().texture()) throw new IllegalArgumentException("Render type samples its depth output: " + state.veilShader());
                }
            }
            if (program == null && material != null) material.accept(null);
            queue().add(new Pending(piece.prepared, geometry, target, borrowed, pipeline, program,
                    Map.copyOf(external), uniforms, blocks, textures, new ScissorState(piece.prepared.scissorState()), lease,output,geometryLease));
        } catch (RuntimeException | Error exception) {
            if (lease != null) lease.close();
            if (borrowed) target.close();
            if(output!=null)output.close();
            throw exception;
        }
    }
    private static Map<String, GpuBufferSlice> standardUniforms(PreparedRenderType prepared) {
        final var values = new LinkedHashMap<String, GpuBufferSlice>();
        if (prepared.dynamicTransforms() != null) values.put("DynamicTransforms", prepared.dynamicTransforms());
        if (RenderSystem.getProjectionMatrixBuffer() != null) values.put("Projection", RenderSystem.getProjectionMatrixBuffer());
        if (RenderSystem.getShaderFog() != null) values.put("Fog", RenderSystem.getShaderFog());
        if (RenderSystem.getShaderLights() != null) values.put("Lighting", RenderSystem.getShaderLights());
        if (RenderSystem.getGlobalSettingsUniform() != null) values.put("Globals", RenderSystem.getGlobalSettingsUniform().slice());
        return values;
    }
    public static void drawPending(VeilRenderLevelStageEvent.Stage stage, VeilRenderLevelStageEvent.Context context) {
        if (stage != VeilRenderLevelStageEvent.Stage.END_MAIN) return;
        if (PENDING.isEmpty()) { PREPARED.clear(); return; }
        final var encoder = context.createCommandEncoder();
        try (var submission = flush(encoder)) { submission.submit(encoder); }
    }
    /** Record into the caller's encoder, submit it, then close the returned resource lease. */
    public static Submission flush(CommandEncoder encoder) {
        final var pending = List.copyOf(PENDING);
        PENDING.clear(); PREPARED.clear();
        return flush(encoder, pending);
    }
    private static Submission flush(CommandEncoder encoder, List<Pending> pending) {
        try {
            for (Pending draw : pending) {
                if(draw.output!=null)draw.output.prepare().accept(encoder);
                final Map<String, GpuBufferSlice> bindings = new LinkedHashMap<>();
                if (draw.program != null) bindings.putAll(draw.program.prepareUniformSnapshots(encoder, draw.uniforms));
                bindings.putAll(draw.external);
                bindings.putAll(VeilShaderBlockState.prepareSnapshots(encoder, draw.blocks));
                try (var pass = draw.target.beginPass(encoder)) {
                    if (draw.program != null) draw.program.bind(pass, bindings, draw.textures);
                    else {
                        pass.setPipeline(RenderSystem.getCompiledPipeline(draw.pipeline));
                        bindings.forEach(pass::setUniform);
                        for (var texture : draw.prepared.textures()) pass.setUniform(texture.name(), texture.textureView(), texture.sampler());
                    }
                    if (draw.scissor.enabled()) pass.enableScissor(draw.scissor.x(), draw.scissor.y(), draw.scissor.width(), draw.scissor.height());
                    else pass.disableScissor();
                    if (draw.geometry.vertexBuffer().isClosed() || draw.geometry.indexBuffer().isClosed())
                        throw new IllegalStateException("A deferred render-type mesh expired before its frame was flushed");
                    pass.setVertexBuffer(0, draw.geometry.vertexBuffer().slice());
                    pass.setIndexBuffer(draw.geometry.indexBuffer(), draw.geometry.indexType());
                    pass.drawIndexed(draw.geometry.indexCount(), 1, draw.geometry.firstIndex(), draw.geometry.baseVertex(), 0);
                }
            }
            return new Submission(pending);
        } catch (RuntimeException | Error failure) {
            new Submission(pending).close();
            throw failure;
        }
    }
    public static final class Submission implements AutoCloseable {
        private final List<Pending> draws;
        private boolean closed;
        private boolean submitted;
        private Submission(List<Pending> draws) { this.draws = draws; }
        /** Submit the caller's commands before publishing output callbacks, then release this lease. */
        public void submit(CommandEncoder encoder){if(closed||submitted)throw new IllegalStateException("Native draw submission is already retired/submitted");encoder.submit();submitted=true;for(var draw:draws)if(draw.output!=null)draw.output.submitted().run();}
        @Override public void close() {
            if (this.closed) return;
            this.closed = true;
            for (var draw : this.draws) {
                if (draw.lease != null) draw.lease.close();
                if (draw.borrowed) draw.target.close();
                if(draw.output!=null)draw.output.close();
                if(draw.geometryLease!=null)draw.geometryLease.close();
            }
        }
    }
    public static final class Capture implements AutoCloseable {
        private final Capture previous = CAPTURE.get();
        private final GpuFramebuffer target;
        private final List<Pending> draws = new ArrayList<>();
        private boolean closed;
        private Capture(GpuFramebuffer target) { this.target = target; if(target.isClosed())throw new IllegalStateException("Capture target is closed"); }
        public void submit(CommandEncoder encoder) {
            if(this.closed||CAPTURE.get()!=this)throw new IllegalStateException("Submit the current native capture before closing it");
            var pending=List.copyOf(this.draws);this.draws.clear();
            try(var submission=flush(encoder,pending)){submission.submit(encoder);}
        }
        @Override public void close() {
            if(this.closed)return;
            if(CAPTURE.get()!=this)throw new IllegalStateException("Native captures must close in stack order");
            try{new Submission(List.copyOf(this.draws)).close();this.draws.clear();}
            finally{if(this.previous==null)CAPTURE.remove();else CAPTURE.set(this.previous);this.closed=true;}
        }
    }
    public static void release(RenderType type) {
        final var definition = TYPES.remove(type);
        if (definition != null) definition.release();
    }
    private record Runtime(ShaderManager shaders, FramebufferManager framebuffers, Executor workers) implements ShaderTextureSource.Context {
        @Override public GpuFramebuffer getFramebuffer(Identifier name) { return this.framebuffers.getFramebuffer(name); }
        @Override public TextureFilter framebufferFilter(Identifier name, int color, boolean depth) {
            final var definition = this.framebuffers.getFramebufferDefinition(name);
            if (definition == null) return TextureFilter.CLAMP;
            final var attachment = depth ? definition.depthBuffer() : definition.colorBuffers()[color];
            if (attachment == null) throw new IllegalArgumentException("Framebuffer has no configured attachment: " + name);
            return attachment.filter();
        }
    }
    private record Piece(@Nullable RenderType type, PreparedRenderType prepared, @Nullable Definition definition) {
        boolean deferred() { return this.definition != null && (this.definition.state.veilShader() != null || this.definition.state.output() != null); }
    }
    private static final class Definition {
        private final String name;
        private final VertexDescription vertex;
        private final VeilRenderTypeBuilder.State state;
        private final int bufferSize;
        private final Map<Layout, RenderPipeline> variants = new HashMap<>();
        private ShaderManager shaderOwner;
        Definition(RenderType type, VeilRenderTypeBuilder.State state, int bufferSize) {
            this.name = type.toString(); this.state = state; this.bufferSize = bufferSize;
            this.vertex = new VertexDescription(type.format(), type.primitiveTopology());
        }
        RenderPipeline pipeline(GpuFramebuffer target) {
            if (this.shaderOwner != null && this.shaderOwner != runtime.shaders) this.release();
            this.shaderOwner = runtime.shaders;
            final var layout = new Layout(java.util.stream.IntStream.range(0, target.colorCount()).mapToObj(index -> target.color(index).texture().getFormat()).toList(),
                    target.hasDepth() ? target.depth().texture().getFormat() : null);
            return this.variants.computeIfAbsent(layout, value -> VeilRenderTypeBuilder.pipeline(
                    Identifier.fromNamespaceAndPath("veil", "draw/" + Integer.toUnsignedString(this.name.hashCode(), 16)), this.state,
                    this.vertex.format, this.vertex.topology, value.colors, value.depth));
        }
        void release() {
            if (this.shaderOwner != null && this.state.veilShader() != null)
                this.variants.values().forEach(pipeline -> this.shaderOwner.releaseShader(this.state.veilShader(), pipeline));
            this.shaderOwner = null; this.variants.clear();
        }
    }
    private record VertexDescription(com.mojang.renderpearl.api.vertex.VertexFormat format, com.mojang.renderpearl.api.pipeline.PrimitiveTopology topology) {}
    private record Layout(List<GpuFormat> colors, @Nullable GpuFormat depth) {}
    private record Pending(PreparedRenderType prepared, StagedVertexBuffer.ExecuteInfo geometry, GpuFramebuffer target,
                           boolean borrowed, RenderPipeline pipeline, @Nullable GpuShaderProgram program,
                           Map<String, GpuBufferSlice> external, Map<String, GpuUniformBuffer.Snapshot> uniforms,
                           Map<String, ShaderBlock.Snapshot> blocks,
                           Map<String, GpuShaderProgram.TextureBinding> textures, ScissorState scissor, @Nullable GpuShaderProgram.Lease lease,
                           @Nullable NativeRenderTypeOutput.Target output, @Nullable VertexArray.Draw geometryLease) {}
    private NativeRenderTypeDraws() {}
}
