package foundry.veil.fabric;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import foundry.veil.Veil;
import foundry.veil.VeilClient;
import foundry.veil.api.client.render.VeilRenderSystem;
import foundry.veil.api.client.render.dynamicbuffer.DynamicBufferType;
import foundry.veil.api.quasar.data.QuasarParticles;
import foundry.veil.api.quasar.particle.ParticleEmitter;
import foundry.veil.api.quasar.particle.ParticleSystemManager;
import foundry.veil.impl.ClientCoordinates;
import foundry.veil.impl.ClientEnumArgument;
import foundry.veil.impl.VeilReloadListeners;
import foundry.veil.impl.client.imgui.VeilImGuiCompat;
import foundry.veil.impl.network.VeilClientServerFlags;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.resource.v1.ResourceLoader;
import net.minecraft.commands.arguments.IdentifierArgument;
import net.minecraft.commands.arguments.coordinates.Vec3Argument;
import net.minecraft.commands.arguments.coordinates.Coordinates;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.ApiStatus;

import java.util.Locale;

@ApiStatus.Internal
public class VeilFabricClient implements ClientModInitializer {

    @Override
    public void onInitializeClient() {
        FabricRenderTypeStageHandler.registerEvents();
        VeilClient.init();
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> client.execute(() -> {
            VeilRenderSystem.renderer().getLightRenderer().free();
            VeilClientServerFlags.onDisconnect();
        }));

        if (Veil.IMGUIMC) {
            KeyMappingHelper.registerKeyMapping(VeilImGuiCompat.EDITOR_KEY);
        }

        VeilReloadListeners.registerListeners((type, id, listener) -> ResourceLoader.get(type).registerReloadListener(Veil.veilPath(id), listener));
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, dedicated) -> {
            LiteralArgumentBuilder<FabricClientCommandSource> builder = LiteralArgumentBuilder.literal("quasar");
            builder.then(ClientCommands.argument("emitter", IdentifierArgument.id()).suggests(QuasarParticles.emitterSuggestionProvider()).then(ClientCommands.argument("position", Vec3Argument.vec3()).executes(ctx -> {
                Identifier id = ctx.getArgument("emitter", Identifier.class);

                FabricClientCommandSource source = ctx.getSource();
                ParticleSystemManager particleManager = VeilRenderSystem.renderer().getParticleManager();
                ParticleEmitter instance = particleManager.createEmitter(id);
                if (instance == null) {
                    source.sendError(Component.literal("Unknown emitter: " + id));
                    return 0;
                }

                Coordinates coordinates = ctx.getArgument("position", Coordinates.class);
                Vec3 pos = ClientCoordinates.resolve(coordinates, source.getPosition(), source.getRotation());
                instance.setPosition(pos.x, pos.y, pos.z);
                particleManager.addParticleSystem(instance);
                source.sendFeedback(Component.literal("Spawned " + id));
                return 1;
            })));
            dispatcher.register(builder);

            if (Veil.platform().isDevelopmentEnvironment()) {
                Identifier bufferId = Veil.veilPath("forced");
                LiteralArgumentBuilder<FabricClientCommandSource> debugBuilder = LiteralArgumentBuilder.literal("veilc");
                debugBuilder.then(ClientCommands.literal("buffers")
                        .then(ClientCommands.literal("enable")
                                .then(ClientCommands.argument("buffer", ClientEnumArgument.enumArgument(DynamicBufferType.class)).executes(ctx -> {
                                    DynamicBufferType value = ctx.getArgument("buffer", DynamicBufferType.class);
                                    VeilRenderSystem.renderer().enableBuffers(bufferId, value);
                                    ctx.getSource().sendFeedback(Component.translatable("commands.veil.buffers.enable", value.name().toLowerCase(Locale.ROOT)));
                                    return Command.SINGLE_SUCCESS;
                                }))
                                .then(ClientCommands.literal("all").executes(ctx -> {
                                    DynamicBufferType[] values = DynamicBufferType.values();
                                    VeilRenderSystem.renderer().enableBuffers(bufferId, values);
                                    ctx.getSource().sendFeedback(Component.translatable("commands.veil.buffers.enable.all"));
                                    return values.length;
                                }))
                        )
                        .then(ClientCommands.literal("disable")
                                .then(ClientCommands.argument("buffer", ClientEnumArgument.enumArgument(DynamicBufferType.class)).executes(ctx -> {
                                    DynamicBufferType value = ctx.getArgument("buffer", DynamicBufferType.class);
                                    VeilRenderSystem.renderer().disableBuffers(bufferId, value);
                                    ctx.getSource().sendFeedback(Component.translatable("commands.veil.buffers.disable", value.name().toLowerCase(Locale.ROOT)));
                                    return Command.SINGLE_SUCCESS;
                                }))
                                .then(ClientCommands.literal("all").executes(ctx -> {
                                    DynamicBufferType[] values = DynamicBufferType.values();
                                    VeilRenderSystem.renderer().disableBuffers(bufferId, values);
                                    ctx.getSource().sendFeedback(Component.translatable("commands.veil.buffers.disable.all"));
                                    return values.length;
                                }))
                        ));
                dispatcher.register(debugBuilder);
            }
        });
    }
}
