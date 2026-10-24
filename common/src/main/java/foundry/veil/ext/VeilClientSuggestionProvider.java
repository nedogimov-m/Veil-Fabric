package foundry.veil.ext;

import net.minecraft.resources.Identifier;

import java.util.stream.Stream;

public interface VeilClientSuggestionProvider {

    Stream<Identifier> veil$getPostPipelineNames();
}
