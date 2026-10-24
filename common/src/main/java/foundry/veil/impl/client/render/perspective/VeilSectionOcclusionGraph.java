package foundry.veil.impl.client.render.perspective;

import foundry.veil.mixin.perspective.accessor.NativePerspectiveViewAreaAccessor;
import net.minecraft.client.renderer.ViewArea;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher;
import net.minecraft.client.renderer.culling.Frustum;
import java.util.ArrayList;
import java.util.List;

/** Frustum selection over the actual primary view's resident native terrain sections. */
public final class VeilSectionOcclusionGraph {
    private final List<SectionRenderDispatcher.RenderSection> selected = new ArrayList<>();
    public void update(ViewArea area, boolean smartCull, LevelPerspectiveCamera camera, Frustum frustum,
            List<SectionRenderDispatcher.RenderSection> output) {
        this.selected.clear();
        double distance = Math.min(area.getViewDistance(), camera.getRenderDistance()) * 16.0;
        for (var section : ((NativePerspectiveViewAreaAccessor) area).veil$perspectiveSections()) {
            var box = section.getBoundingBox();
            if (box.getCenter().distanceToSqr(camera.position()) <= distance * distance && frustum.isVisible(box)) this.selected.add(section);
        }
        output.addAll(this.selected);
    }
    public void reset() { this.selected.clear(); }
}
