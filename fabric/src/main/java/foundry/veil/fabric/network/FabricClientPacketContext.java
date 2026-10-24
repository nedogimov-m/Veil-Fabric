package foundry.veil.fabric.network;

import foundry.veil.api.network.handler.ClientPacketContext;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import io.netty.channel.ChannelFutureListener;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.Nullable;

@ApiStatus.Internal
public record FabricClientPacketContext(ClientPlayNetworking.Context context) implements ClientPacketContext {

    @Override
    public Minecraft client() {
        return this.context.client();
    }

    @Override
    public LocalPlayer player() {
        return this.context.player();
    }

    @Override
    public Packet<?> createPacket(CustomPacketPayload payload) {
        return this.context.responseSender().createPacket(payload);
    }

    @Override
    public void sendPacket(Packet<?> packet, @Nullable ChannelFutureListener callback) {
        this.context.responseSender().sendPacket(packet, callback);
    }

    @Override
    public void disconnect(Component disconnectReason) {
        this.context.responseSender().disconnect(disconnectReason);
    }
}
