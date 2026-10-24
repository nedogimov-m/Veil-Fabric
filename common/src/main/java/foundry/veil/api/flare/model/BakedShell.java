package foundry.veil.api.flare.model;

import foundry.veil.api.client.render.vertex.VertexArray;
import foundry.veil.api.flare.data.model.FlareBakedQuad;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.renderpearl.api.commands.CommandEncoder;
import com.mojang.renderpearl.api.vertex.VertexFormat;
import org.lwjgl.system.NativeResource;

import java.util.Collection;

/**
 * Baked Shell, contains a list of baked quads.
 * Shells are models stripped of their texture, though they may still contain texture coordinates.
 *
 * @author GuyApooye
 * @since 2.5.0
 */
public interface BakedShell extends NativeResource {

    Collection<FlareBakedQuad> getQuads();

    default VertexArray getVertexArray() { return this.getVertexArray(DefaultVertexFormat.BLOCK); }

    /** Convenience upload for a standalone owner. Draw recording should use the explicit encoder overload. */
    default VertexArray getVertexArray(VertexFormat format) {
        var encoder = RenderSystem.getDevice().createCommandEncoder();
        VertexArray mesh = this.getVertexArray(encoder, format);
        encoder.submit();
        return mesh;
    }

    /** The caller submits uploads before drawing and retains its Draw until submission completes. */
    VertexArray getVertexArray(CommandEncoder encoder, VertexFormat format);
}
