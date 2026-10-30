package mekanism.common.network.to_server.qio;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.handler.codec.DecoderException;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.resources.Identifier;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class PacketQIOFillCraftingWindowTest {

    /// Writes the header of a transferMultiple packet with one target slot and the given source count.
    private static ByteBuf header(int subSourceCount) {
        ByteBuf buffer = Unpooled.buffer();
        Identifier.STREAM_CODEC.encode(buffer, Identifier.withDefaultNamespace("crafting_table"));
        buffer.writeBoolean(true);//transferMultiple
        buffer.writeBoolean(false);//rejectToInventory
        buffer.writeByte(1);//slotCount
        buffer.writeByte(0);//targetSlot
        ByteBufCodecs.VAR_INT.encode(buffer, subSourceCount);
        return buffer;
    }

    @Test
    @DisplayName("Test decoding a source from an inventory slot")
    void decodesSource() {
        ByteBuf buffer = header(1);
        buffer.writeByte(3);//sourceSlot
        ByteBufCodecs.VAR_INT.encode(buffer, 5);//count
        PacketQIOFillCraftingWindow packet = PacketQIOFillCraftingWindow.STREAM_CODEC.decode(buffer);
        Assertions.assertEquals(1, packet.sources().get((byte) 0).size());
    }

    @Test
    @DisplayName("Test a source count the packet can't hold is rejected before allocating")
    void rejectsHugeSourceCount() {
        //Without the check this presizes an ArrayList of Integer.MAX_VALUE elements (OutOfMemoryError)
        Assertions.assertThrows(DecoderException.class, () -> PacketQIOFillCraftingWindow.STREAM_CODEC.decode(header(Integer.MAX_VALUE)));
    }
}
