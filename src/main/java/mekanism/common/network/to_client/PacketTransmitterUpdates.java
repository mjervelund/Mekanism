package mekanism.common.network.to_client;

import io.netty.buffer.ByteBuf;
import java.util.List;
import mekanism.common.Mekanism;
import mekanism.common.network.IMekanismPacket;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/// The transmitter update packets of one chunk for one tick, sent as one payload (a network forming, merging or splitting updates every transmitter in it).
/// The client handles each entry exactly like a separate [PacketUpdateTile], in order.
public record PacketTransmitterUpdates(List<PacketUpdateTile> updates) implements IMekanismPacket {

    public static final CustomPacketPayload.Type<PacketTransmitterUpdates> TYPE = new CustomPacketPayload.Type<>(Mekanism.rl("transmitter_updates"));
    public static final StreamCodec<ByteBuf, PacketTransmitterUpdates> STREAM_CODEC = PacketUpdateTile.STREAM_CODEC.apply(ByteBufCodecs.list())
          .map(PacketTransmitterUpdates::new, PacketTransmitterUpdates::updates);

    @Override
    public CustomPacketPayload.Type<PacketTransmitterUpdates> type() {
        return TYPE;
    }

    @Override
    public void handle(IPayloadContext context) {
        for (PacketUpdateTile update : updates) {
            update.handle(context);
        }
    }
}
