package foundry.veil.impl.client.render.dynamicbuffer;

import com.mojang.renderpearl.api.commands.CommandEncoder;
import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.api.commands.RenderPassDescriptor;
import com.mojang.renderpearl.api.pipeline.CompiledRenderPipeline;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import net.minecraft.client.renderer.oit.OitStage;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/** Native frontend interception: append world attachments before opening a pass, retain owners until submit. */
public final class NativeDynamicBuffers {
    private static DynamicBufferManager manager;
    private static Scope current;
    private static OitScope oitStage;
    private static final Map<CommandEncoder, Submission> SUBMISSIONS = new IdentityHashMap<>();
    private static final Map<RenderPass, Selection> PASSES = new IdentityHashMap<>();
    public static void bindRuntime(DynamicBufferManager owner) {
        if (manager != null && manager != owner) throw new IllegalStateException("Dynamic world runtime is already bound");
        manager = owner;
    }
    public static void unbindRuntime(DynamicBufferManager owner) {
        if (manager == owner) manager = null;
    }
    /** Drain captured commands before renderer shutdown waits for compiler retirement. Passes must already be closed. */
    public static void flush(DynamicBufferManager owner) {
        if (PASSES.values().stream().anyMatch(selection -> selection.world.owner() == owner))
            throw new IllegalStateException("Close native world passes before shutting down their renderer");
        var encoders = new ArrayList<CommandEncoder>();
        SUBMISSIONS.forEach((encoder, submission) -> {
            if (submission.worlds.stream().anyMatch(world -> world.owner() == owner)) encoders.add(encoder);
        });
        encoders.forEach(CommandEncoder::submit);
    }
    /** Scope the actual LevelRenderer owner; callers may also render an independent world target explicitly. */
    public static Scope beginWorld(GpuTextureView color, GpuTextureView depth) {
        return beginWorld(color, depth, false);
    }
    /** Separate views such as first-person hands must preserve the main world's completed property targets. */
    public static Scope beginIndependentWorld(GpuTextureView color, GpuTextureView depth) {
        return beginWorld(color, depth, true);
    }
    private static Scope beginWorld(GpuTextureView color, GpuTextureView depth, boolean independent) {
        Scope scope = new Scope(current, manager == null ? null : manager.beginWorld(color, depth, independent || scopedWorld(manager) != null));
        if (scope.world != null && scope.world.mask() != 0) {
            try {
                var encoder = manager.device().createCommandEncoder();
                scope.world.clear(encoder); uses(encoder, scope.world);
            } catch (RuntimeException | Error failure) { scope.world.close(); throw failure; }
        }
        current = scope; return scope;
    }
    /** Post passes retain world inputs while recording their own unextended output descriptors. */
    public static Scope suspend() { Scope scope = new Scope(current, null); current = scope; return scope; }
    static DynamicBufferManager.World scopedWorld(DynamicBufferManager owner) {
        for (Scope scope = current; scope != null; scope = scope.previous)
            if (scope.world != null && scope.world.owner() == owner) return scope.world;
        return null;
    }
    public static Selection select(CommandEncoder encoder, RenderPassDescriptor descriptor) {
        if (current == null || current.world == null || current.world.mask() == 0) return null;
        var world = current.world;
        if (oitStage != null) {
            if (oitStage.stage != OitStage.ACCUMULATE) return null;
            // Storage clearing is queued on this same native encoder, so even a failed pass creation retains it through submit.
            uses(encoder, world);
            return new Selection(encoder, world, world.extendOit(encoder, descriptor), true);
        }
        if (!world.matches(descriptor)) return null;
        uses(encoder, world);
        try (var ignored = suspend()) {
            return new Selection(encoder, world, world.extendCoverage(encoder, descriptor), false);
        }
    }
    public static OitScope beginOit(OitStage stage) { OitScope scope = new OitScope(oitStage, stage); oitStage = scope; return scope; }
    /** Called after the actual native OIT accumulation/composite pass closes, before world post consumers. */
    public static void composeOit(GpuTextureView accumulation, GpuTextureView depthBounds, GpuTextureView[] coefficients) {
        if (current == null || current.world == null || !current.world.hasOit()) return;
        var world = current.world;
        var encoder = world.owner().device().createCommandEncoder(); uses(encoder, world);
        try (var ignored = suspend()) { world.composeOit(encoder, accumulation, depthBounds, coefficients); }
    }
    public static void opened(RenderPass pass, Selection selection) {
        if (selection == null) return;
        selection.submission = uses(selection.encoder, selection.world);
        PASSES.put(pass, selection);
    }
    private static Submission uses(CommandEncoder encoder, DynamicBufferManager.World world) {
        Submission submission = SUBMISSIONS.computeIfAbsent(encoder, ignored -> new Submission());
        if (!submission.worlds.contains(world)) { world.retain(); submission.worlds.add(world); }
        return submission;
    }
    public static CompiledRenderPipeline pipeline(RenderPass pass, CompiledRenderPipeline base) {
        Selection selection = PASSES.get(pass);
        if (selection == null) return base;
        var lease = selection.world.pipeline(base, selection.accumulation);
        selection.submission.pipelines.add(lease);
        return lease.pipeline();
    }
    public static void closed(RenderPass pass) {
        Selection selection = PASSES.remove(pass);
        if (selection != null && !selection.accumulation) {
            try (var ignored = suspend()) { selection.world.resolveCoverage(selection.encoder); }
        }
    }
    /** Called only after the genuine backend submission consumed its resource handles. */
    public static void submitted(CommandEncoder encoder) {
        Submission submission = SUBMISSIONS.remove(encoder);
        if (submission == null) return;
        submission.pipelines.forEach(VanillaShaderCompiler.Lease::close);
        submission.worlds.forEach(DynamicBufferManager.World::close);
    }
    public static final class Scope implements AutoCloseable {
        private final Scope previous;
        private final DynamicBufferManager.World world;
        private boolean closed;
        private Scope(Scope previous, DynamicBufferManager.World world) { this.previous = previous; this.world = world; }
        @Override public void close() {
            if (this.closed) return;
            if (current != this) throw new IllegalStateException("Dynamic world scopes must close in stack order");
            this.closed = true; current = this.previous;
            if (this.world != null) this.world.close();
        }
    }
    public static final class Selection {
        private final CommandEncoder encoder;
        private Submission submission;
        private final DynamicBufferManager.World world;
        private final RenderPassDescriptor descriptor;
        private final boolean accumulation;
        private Selection(CommandEncoder encoder, DynamicBufferManager.World world, RenderPassDescriptor descriptor, boolean accumulation) {
            this.encoder = encoder; this.world = world; this.descriptor = descriptor; this.accumulation = accumulation;
        }
        public RenderPassDescriptor descriptor() { return this.descriptor; }
    }
    public static final class OitScope implements AutoCloseable {
        private final OitScope previous;
        private final OitStage stage;
        private boolean closed;
        private OitScope(OitScope previous, OitStage stage) { this.previous = previous; this.stage = stage; }
        @Override public void close() {
            if (this.closed) return;
            if (oitStage != this) throw new IllegalStateException("Native OIT stages must close in stack order");
            this.closed = true; oitStage = this.previous;
        }
    }
    private static final class Submission {
        final List<DynamicBufferManager.World> worlds = new ArrayList<>();
        final List<VanillaShaderCompiler.Lease> pipelines = new ArrayList<>();
    }
    private NativeDynamicBuffers() { }
}
