package foundry.veil.porttest;

import com.google.gson.GsonBuilder;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import foundry.veil.api.client.necromancer.*;
import foundry.veil.api.client.necromancer.animation.Animator;
import foundry.veil.api.client.necromancer.render.*;
import foundry.veil.api.client.render.MatrixStack;
import foundry.veil.api.client.render.gpu.*;
import foundry.veil.api.client.render.shader.*;
import foundry.veil.api.event.VeilRenderLevelStageEvent;
import foundry.veil.fabric.FabricRenderTypeStageHandler;
import foundry.veil.fabric.event.FabricVeilRenderLevelStageEvent;
import foundry.veil.impl.client.necromancer.render.NecromancerRenderDispatcher;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.SubmitNodeStorage;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.PreparableReloadListener;
import net.minecraft.world.entity.decoration.ArmorStand;
import org.joml.*;

import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.*;

/** Native skeletal CPU extraction and real feature/world GPU submissions; no full Veil bootstrap. */
public final class GpuSkeletalAcceptance implements FabricClientGameTest {
    private static EntityRendererProvider.Context rendererContext;
    public static void rendererContext(EntityRendererProvider.Context value) { rendererContext = value; }
    public static final class TestSkeleton extends Skeleton {
        final Bone root = new Bone("root"), child = new Bone("child");
        TestSkeleton() { root.addChild(child); this.addBone(root); this.addBone(child); this.buildRoots(); }
    }
    private static final class TestArmorStand extends ArmorStand implements SkeletonParent<TestArmorStand, TestSkeleton> {
        private TestSkeleton skeleton; private Animator<TestArmorStand, TestSkeleton> animator;
        TestArmorStand(Minecraft client) { super(client.level, client.player.getX(), client.player.getY(), client.player.getZ()); }
        @Override public void setSkeleton(TestSkeleton value) { this.skeleton = value; }
        @Override public TestSkeleton getSkeleton() { return this.skeleton; }
        @Override public void setAnimator(Animator<TestArmorStand, TestSkeleton> value) { this.animator = value; }
        @Override public Animator<TestArmorStand, TestSkeleton> getAnimator() { return this.animator; }
    }
    private static final class TestRenderer extends NecromancerEntityRenderer<TestArmorStand, TestSkeleton> {
        TestRenderer(Skin skin) {
            super(Objects.requireNonNull(rendererContext), 0);
            this.addLayer(new NecromancerSkinEntityRenderLayer<>(this) {
                @Override public RenderType getRenderType(TestArmorStand entity) { return RenderTypes.debugQuads(); }
                @Override public Skin getSkin(TestArmorStand entity) { return skin; }
                @Override protected void renderSkin(TestArmorStand parent, TestSkeleton skeleton, Skin value, RenderType type,
                        NecromancerRenderer renderer, MatrixStack matrices, int light, float partial) {
                    renderer.setColor(.5f, 1, .5f, 1); super.renderSkin(parent, skeleton, value, type, renderer, matrices, light, partial);
                }
            });
        }
        @Override public TestSkeleton createSkeleton(TestArmorStand entity) { return new TestSkeleton(); }
        @Override public Animator<TestArmorStand, TestSkeleton> createAnimator(TestArmorStand parent, TestSkeleton skeleton) { return new Animator<>(parent, skeleton) {}; }
    }
    @Override public void runTest(ClientGameTestContext test) {
        var report = new LinkedHashMap<String, Object>(); var checks = new ArrayList<String>(); report.put("checks", checks);
        var liveSkin = new AtomicReference<Skin>(); var liveSkeleton = new AtomicReference<TestSkeleton>();
        var collected = new AtomicBoolean(); var worldPixel = new AtomicBoolean(); var failure = new AtomicReference<Throwable>();
        try {
            test.runOnClient(client -> {
                report.put("backend", RenderSystem.getDevice().getDeviceInfo().backendName()); report.put("device", RenderSystem.getDevice().getDeviceInfo().name());
                check(report.get("backend").toString().equalsIgnoreCase("vulkan"), "Actual Vulkan required");
                cpu(checks);
                FabricRenderTypeStageHandler.registerEvents();
                FabricVeilRenderLevelStageEvent.EVENT.register((stage, context) -> {
                    if (liveSkin.get() == null || failure.get() != null) return;
                    try {
                        if (stage == VeilRenderLevelStageEvent.Stage.COLLECT_SUBMITS && collected.compareAndSet(false, true)) {
                            var parent = NecromancerRenderDispatcher.getRenderer();
                            try (var nested = NecromancerRenderDispatcher.begin(new SubmitNodeStorage())) {
                                check(NecromancerRenderDispatcher.getRenderer() != parent, "Nested skeletal scope did not select its own collector");
                            }
                            check(NecromancerRenderDispatcher.getRenderer() == parent, "Nested skeletal collector did not restore parent selection");
                            boolean intentional = false;
                            try (var nested = NecromancerRenderDispatcher.begin(new SubmitNodeStorage())) { throw new IllegalStateException("deliberate skeletal scope failure"); }
                            catch (IllegalStateException expected) { intentional = true; }
                            check(intentional && NecromancerRenderDispatcher.getRenderer() == parent, "Exceptional nested skeletal scope failed to restore parent");
                            parent.setTransform(new Matrix4f(context.camera().viewRotationMatrix).invert().translate(0, 0, -3));
                            parent.setColor(.2f, .7f, .3f, 1); parent.draw(RenderTypes.debugQuads(), liveSkeleton.get(), liveSkin.get(), 0);
                            liveSkeleton.get().root.position.set(10_000); liveSkin.get().free();
                        } else if (stage == VeilRenderLevelStageEvent.Stage.END_MAIN && collected.get() && !worldPixel.get()) {
                            assertPixel(context.mainTarget().getColorTexture(), context.mainTarget().width / 2, context.mainTarget().height / 2, 51, 179, 77, 255);
                            boolean unscoped = false; try { NecromancerRenderDispatcher.getRenderer(); } catch (IllegalStateException expected) { unscoped = true; }
                            check(unscoped, "Native COLLECT_SUBMITS skeletal scope leaked into pass execution/END_MAIN"); worldPixel.set(true);
                        }
                    } catch (Throwable caught) { failure.compareAndSet(null, caught); }
                });
            });
            try (var world = test.worldBuilder().create()) {
                test.waitFor(client -> client.gameRenderer.gameRenderState().levelRenderState.cameraRenderState.initialized && rendererContext != null, 400);
                test.runOnClient(client -> {
                    entityScene(client, checks);
                    liveSkeleton.set(new TestSkeleton()); liveSkin.set(face(2, .5f));
                });
                test.waitFor(client -> failure.get() != null || worldPixel.get(), 200);
                if (failure.get() != null) throw new AssertionError("Actual native skeletal world failed", failure.get());
                check(worldPixel.get(), "Actual native skeletal world produced no pixels");
                checks.add("real Fabric COLLECT_SUBMITS event scope captures immutable skeletal geometry before native preparation; nested and exceptional scopes restore parent collectors, freed/mutated sources retain exact world pixels, and native pass execution has no live collection scope");
                report.put("cases", checks.size()); report.put("status", "passed");
            }
        } catch (Throwable caught) { caught.printStackTrace(); report.put("status", "failed"); report.put("error", caught.toString()); throw caught; }
        finally {
            test.runOnClient(client -> { liveSkin.set(null); liveSkeleton.set(null); NecromancerRenderDispatcher.delete(); });
            try { Files.writeString(Path.of(System.getProperty("veil.skeletal.report")), new GsonBuilder().setPrettyPrinting().create().toJson(report) + "\n"); }
            catch (Exception caught) { throw new RuntimeException(caught); }
        }
    }
    private static Skin face(float size, float z) {
        return Skin.builder(16, 16).startBone("child").addFace(-size, -size, z, 0, 0, size, -size, z, 1, 0,
                size, size, z, 1, 1, -size, size, z, 0, 1, 0, 0, 1).build();
    }
    private static void cpu(List<String> checks) {
        var skeleton = new TestSkeleton(); skeleton.buildRoots();
        check(skeleton.roots.size() == 1 && skeleton.child.parentChain.size() == 1, "Repeated native skeleton root rebuilding duplicated transforms");
        skeleton.root.position.set(2, 0, 0); skeleton.root.rotation.rotationZ((float) (java.lang.Math.PI / 2)); skeleton.child.position.set(0, 2, 0);
        skeleton.child.color.set(.2f, .4f, .6f, 1);
        var skin = Skin.builder(16, 16).startBone("child").addTri(0, 0, .5f, .2f, .3f, 1, 0, .5f, 1, 0, 0, 1, .5f, 0, 1, 1, 0, 0).build();
        var snapshot = skin.snapshot(skeleton, new Matrix4f(), new Vector4f(.5f, 1, .25f, .5f), 0xABCD1234, 0x00EF00AA, .5f);
        var first = snapshot.vertices().getFirst(); float diagonal = (float) java.lang.Math.sqrt(.5);
        check(close(first.x(), 1 - diagonal) && close(first.y(), diagonal) && close(first.z(), .5f), "Parent/child translation and quaternion interpolation were not captured");
        check(close(first.nx(), diagonal) && close(first.ny(), diagonal) && close(first.u(), .2f) && close(first.v(), .3f), "Native skeletal normal/UV capture changed");
        check(first.overlay() == 0xABCD1234 && first.light() == 0x00EF00AA && first.color() == 0x804DB333, "Native skeletal packed overlay/light or bone/instance tint lost precision: " + Integer.toHexString(first.color()));
        check(snapshot.indices().length == 3 && snapshot.vertices().size() == 3, "Triangle skin builder inserted unused vertex reservations");
        skeleton.child.position.set(100); skeleton.child.color.zero(); skin.free();
        check(snapshot.vertices().getFirst().equals(first), "Captured geometry retained mutable skeletal/source values");
        checks.add("actual bone hierarchy translation/quaternion interpolation, idempotent root rebuild, normals/UVs, complete packed light/overlay and bone×instance RGBA are detached at capture; indexed triangle builder has exact vertices and survives source mutation/free");
    }
    private static void entityScene(Minecraft client, List<String> checks) {
        var device = RenderSystem.getDevice();
        var skin = face(12, 8); var renderer = new TestRenderer(skin); var entity = new TestArmorStand(client); renderer.setupEntity(entity);
        entity.getSkeleton().child.previousColor.set(.4f, .8f, .2f, 1); entity.getSkeleton().child.color.set(.8f, .2f, .6f, 1);
        var extracted = renderer.createRenderState(entity, .5f);
        check(extracted.skeletalLayers.size() == 1, "Actual EntityRenderer extraction produced no immutable skeletal layer");
        entity.getSkeleton().child.position.set(1000); entity.getSkeleton().child.color.zero(); skin.free();
        var inputs = NativeFeatureScene.Inputs.capture(client); inputs.options().improvedTransparency = false;
        try (var scene = new NativeFeatureScene(client, client.getResourceManager());
             var output = GpuFramebuffer.builder("actual skeletal entity native scene", 2, 2).color(GpuFormat.RGBA8_UNORM).depth(GpuFormat.D32_FLOAT).build(device)) {
            var encoder = device.createCommandEncoder(); output.clear(encoder, new Vector4f(0), 0); encoder.submit();
            scene.render(output, new Matrix4f(), com.mojang.blaze3d.ProjectionType.PERSPECTIVE, new Matrix4f(), inputs,
                    nodes -> renderer.submit(extracted, new PoseStack(), nodes, client.gameRenderer.gameRenderState().levelRenderState.cameraRenderState));
            assertPixel(output.color(0).texture(), 1, 1, 77, 128, 51, 255);
        }
        var definitions = new ShaderPreDefinitions();
        try (var shaders = new ShaderManager(device, ShaderManager.PROGRAM_SET, definitions)) {
            var reload = new PreparableReloadListener.SharedState(client.getResourceManager());
            var barrier = new PreparableReloadListener.PreparationBarrier() { @Override public <T> java.util.concurrent.CompletableFuture<T> wait(T value) { return java.util.concurrent.CompletableFuture.completedFuture(value); } };
            shaders.reload(reload, Runnable::run, barrier, Runnable::run).join();
            var program = shaders.getShader(Identifier.parse("veil:necromancer/skinned_mesh"), RenderTypes.entityCutout(Identifier.parse("veil:textures/special/white.png")).pipeline(), net.minecraft.util.Util.backgroundExecutor());
            check(program != null && program.hasUniform("Projection") && program.hasUniform("DynamicTransforms") && !program.hasUniform("NecromancerBones"), "Migrated skin program did not use actual native entity draw-owned blocks");
        }
        checks.add("actual native EntityRenderer extract/submit state and skin layer produce exact Vulkan feature-scene pixels after entity/skeleton mutation and skin disposal; migrated skinned shader compiles on real ENTITY layout with native projection/dynamic transforms/fog/lighting ownership");
    }
    private static void assertPixel(com.mojang.renderpearl.api.textures.GpuTexture texture, int x, int y, int... expected) {
        var device = RenderSystem.getDevice();
        try (var read = device.createBuffer(() -> "native skeletal pixel", GpuBuffer.USAGE_COPY_DST | GpuBuffer.USAGE_MAP_READ, 4)) {
            var encoder = device.createCommandEncoder(); encoder.copyTextureToBuffer(texture, read, 0, () -> {}, 0, x, y, 1, 1);
            try (var fence = encoder.createFence()) { encoder.submit(); check(fence.awaitCompletion(10_000_000_000L), "Native skeletal readback timeout"); }
            try (var mapped = read.map(true, false)) { for (int c = 0; c < 4; c++) check(java.lang.Math.abs((mapped.data().get(c) & 255) - expected[c]) <= 2, "Native skeletal component " + c + " was " + (mapped.data().get(c) & 255) + ", expected " + expected[c]); }
        }
    }
    private static boolean close(float a, float b) { return java.lang.Math.abs(a - b) < .0001; }
    private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
}
