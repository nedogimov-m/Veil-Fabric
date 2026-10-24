package foundry.veil.mixin.registry.accessor;

import net.minecraft.resources.RegistryDataLoader;
import net.minecraft.resources.RegistryLoadTask;
import net.minecraft.resources.RegistryOps;
import net.minecraft.core.HolderLookup;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

import java.util.List;

@Mixin(RegistryDataLoader.class)
public interface RegistryDataAccessor {

    @Invoker("createContext")
    static RegistryOps.RegistryInfoLookup veil$createContext(List<HolderLookup.RegistryLookup<?>> lookups, List<RegistryLoadTask<?>> tasks) {
        throw new AssertionError();
    }
}
