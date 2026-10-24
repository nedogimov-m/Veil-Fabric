package foundry.veil.api.client.util;

import com.mojang.renderpearl.api.commands.RenderPass;
import net.minecraft.client.gui.navigation.ScreenRectangle;
import org.jetbrains.annotations.Nullable;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Objects;

/** Owns top-left native pass scissors; input positions are GUI coordinates, stored bounds are framebuffer pixels. */
public final class ScissorStack implements AutoCloseable {
    private final Deque<ScreenRectangle> regions = new ArrayDeque<>();
    private final float guiScale;
    private final int width;
    private final int height;
    private final @Nullable RenderPass pass;

    public ScissorStack(float guiScale, int framebufferWidth, int framebufferHeight) {
        this(null, guiScale, framebufferWidth, framebufferHeight);
    }
    public ScissorStack(@Nullable RenderPass pass, float guiScale, int framebufferWidth, int framebufferHeight) {
        if (!Float.isFinite(guiScale) || guiScale <= 0 || framebufferWidth <= 0 || framebufferHeight <= 0)
            throw new IllegalArgumentException("Positive native GUI scale and framebuffer dimensions required");
        this.pass = pass; this.guiScale = guiScale; this.width = framebufferWidth; this.height = framebufferHeight;
    }
    public void push(int x, int y, int width, int height) { push((float) x, y, width, height); }
    public void push(float x, float y, float regionWidth, float regionHeight) {
        if (!Float.isFinite(x) || !Float.isFinite(y) || !Float.isFinite(regionWidth) || !Float.isFinite(regionHeight) || regionWidth < 0 || regionHeight < 0)
            throw new IllegalArgumentException("Finite scissor coordinates and nonnegative dimensions required");
        int left = clamp((int) Math.floor((double) x * guiScale), width);
        int top = clamp((int) Math.floor((double) y * guiScale), height);
        int right = clamp((int) Math.ceil(((double) x + regionWidth) * guiScale), width);
        int bottom = clamp((int) Math.ceil(((double) y + regionHeight) * guiScale), height);
        ScreenRectangle parent = regions.peek();
        if (parent != null) { left = Math.max(left, parent.left()); top = Math.max(top, parent.top()); right = Math.min(right, parent.right()); bottom = Math.min(bottom, parent.bottom()); }
        regions.push(new ScreenRectangle(left, top, Math.max(0, right - left), Math.max(0, bottom - top)));
        applyOwned();
    }
    private static int clamp(int value, int limit) { return Math.clamp(value, 0, limit); }
    /** Returns false for an empty intersection; native frontend passes require a positive scissor. */
    public boolean apply(RenderPass target) {
        Objects.requireNonNull(target); ScreenRectangle region = regions.peek();
        if (region == null) target.disableScissor();
        else {
            if (region.width() == 0 || region.height() == 0) return false;
            target.enableScissor(region.left(), region.top(), region.width(), region.height());
        }
        return true;
    }
    public boolean isVisible() { ScreenRectangle region = regions.peek(); return region == null || region.width() > 0 && region.height() > 0; }
    /** All draws owned by this stack use this guard, including empty intersections which cannot be set on a native pass. */
    public void draw(Runnable commands) { Objects.requireNonNull(commands); if (isVisible()) commands.run(); }
    private void applyOwned() { if (pass != null) apply(pass); }
    public void pop() { regions.pop(); applyOwned(); }
    public @Nullable ScreenRectangle getTop() { return regions.peek(); }
    public boolean containsPoint(float x, float y) { return regions.isEmpty() || regions.peek().containsPoint((int) Math.floor(x * guiScale), (int) Math.floor(y * guiScale)); }
    public int size() { return regions.size(); }
    public boolean isEmpty() { return regions.isEmpty(); }
    public void clear() { regions.clear(); applyOwned(); }
    @Override public void close() { clear(); }
}
