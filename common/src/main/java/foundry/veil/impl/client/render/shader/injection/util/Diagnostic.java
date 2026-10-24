package foundry.veil.impl.client.render.shader.injection.util;

import net.minecraft.resources.Identifier;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.Nullable;

@ApiStatus.Internal
public record Diagnostic(
        @Nullable String resourcePath,
        @Nullable Identifier injectionId,
        @Nullable Identifier target,
        @Nullable String field,
        String message,
        Severity severity
) {

    @Override
    public String toString() {
        return this.severity + ": " + this.message +
                (this.resourcePath != null ? " (" + this.resourcePath + ")" : "") +
                (this.field != null ? " field=" + this.field : "") +
                (this.injectionId != null ? " id=" + this.injectionId : "") +
                (this.target != null ? " target=" + this.target : "");
    }
}
