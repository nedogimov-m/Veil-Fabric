package foundry.veil.mixin.network;

import foundry.veil.api.network.VeilPacketManager;
import net.minecraft.network.protocol.Packet;
import net.minecraft.server.level.ServerLevel;
import org.spongepowered.asm.mixin.Mixin;

@Mixin(ServerLevel.class)
public abstract class NetworkServerLevelMixin implements VeilPacketManager.PacketSink {

    @Override
    public void sendPacket(Packet<?> packet) {
        ServerLevel level = (ServerLevel) (Object) this;
        level.getServer().getPlayerList().broadcastAll(packet, level.dimension());
    }
}
