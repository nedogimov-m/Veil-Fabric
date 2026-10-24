package foundry.veil.api.quasar.data.module.init;

import com.mojang.serialization.MapCodec;
import foundry.veil.api.client.editor.EditorAttributeProvider;
import foundry.veil.api.client.render.VeilRenderSystem;
import foundry.veil.api.quasar.data.ParticleModuleTypeRegistry;
import foundry.veil.api.quasar.data.QuasarParticles;
import foundry.veil.api.quasar.data.module.ModuleType;
import foundry.veil.api.quasar.data.module.ParticleModuleData;
import foundry.veil.api.quasar.emitters.module.InitParticleModule;
import foundry.veil.api.quasar.particle.ParticleEmitter;
import foundry.veil.api.quasar.particle.ParticleModuleSet;
import foundry.veil.api.quasar.particle.ParticleSystemManager;
import imgui.ImGui;
import imgui.type.ImString;
import net.minecraft.resources.Identifier;

public final class InitSubEmitterModuleData implements ParticleModuleData, EditorAttributeProvider {

    public static final MapCodec<InitSubEmitterModuleData> CODEC = Identifier.CODEC.fieldOf("subemitter").xmap(InitSubEmitterModuleData::new, InitSubEmitterModuleData::subEmitter);
    private Identifier subEmitter;

    public InitSubEmitterModuleData(Identifier subEmitter) {
        this.subEmitter = subEmitter;
    }

    @Override
    public void addModules(ParticleModuleSet.Builder builder) {
        builder.addModule((InitParticleModule) (particle -> {
            ParticleSystemManager particleManager = VeilRenderSystem.renderer().getParticleManager();
            ParticleEmitter instance = particleManager.createEmitter(this.subEmitter);
            if (instance == null) {
                return;
            }

            instance.setPosition(particle.getPosition());
            particleManager.getScheduler().execute(() -> particleManager.addParticleSystem(instance));
        }));
    }

    @Override
    public ModuleType<?> getType() {
        return ParticleModuleTypeRegistry.INIT_SUB_EMITTER;
    }

    @Override
    public void renderImGuiAttributes() {
        ImString textInput = new ImString(this.subEmitter.toString());

        if (ImGui.inputTextWithHint("subemitter", "namespace:path", textInput)) {
            Identifier location = Identifier.parse(textInput.get());
            QuasarParticles.registryAccess().lookup(QuasarParticles.EMITTER).map(registry -> registry.getValue(location))
                    .ifPresent(data -> this.subEmitter = location);
        }
    }

    public Identifier subEmitter() {
        return subEmitter;
    }

}
