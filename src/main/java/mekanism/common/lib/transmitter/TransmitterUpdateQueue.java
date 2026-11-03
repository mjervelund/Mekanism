package mekanism.common.lib.transmitter;

import it.unimi.dsi.fastutil.longs.Long2ObjectLinkedOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.objects.Reference2ObjectLinkedOpenHashMap;
import it.unimi.dsi.fastutil.objects.ReferenceLinkedOpenHashSet;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import mekanism.common.Mekanism;
import mekanism.common.network.to_client.PacketTransmitterUpdates;
import mekanism.common.network.to_client.PacketUpdateTile;
import mekanism.common.tile.transmitter.TileEntityTransmitter;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.storage.TagValueOutput;
import net.neoforged.neoforge.network.PacketDistributor;

/// Collects the update packets transmitters request during a server tick and sends them at the end of the tick: once per transmitter (with its state at
/// that point), and one [PacketTransmitterUpdates] per chunk instead of one [PacketUpdateTile] per transmitter. When a network forms, merges or splits,
/// every transmitter whose network changed requests an update, which for a large network was tens of thousands of packets in one tick.
public final class TransmitterUpdateQueue {

    /// Keeps a payload well below the client-bound custom payload limit (each entry is a block pos and a small tag).
    static final int MAX_UPDATES_PER_PACKET = 512;

    private static Set<TileEntityTransmitter> queued = new ReferenceLinkedOpenHashSet<>();

    private TransmitterUpdateQueue() {
    }

    /// Queues an update packet for the transmitter, to be sent at the end of the current server tick.
    public static void queue(TileEntityTransmitter tile) {
        if (tile.getLevel() instanceof ServerLevel) {
            queued.add(tile);
        } else {
            //Not on a server level: let the tile log the same warnings it always did
            tile.sendUpdatePacket();
        }
    }

    public static boolean isQueued(TileEntityTransmitter tile) {
        return queued.contains(tile);
    }

    static void reset() {
        queued.clear();
    }

    static void sendQueued() {
        if (queued.isEmpty()) {
            return;
        }
        Set<TileEntityTransmitter> toSend = queued;
        queued = new ReferenceLinkedOpenHashSet<>();
        //Group by level and chunk, keeping the order in which the updates were first requested
        Map<ServerLevel, Long2ObjectLinkedOpenHashMap<List<TileEntityTransmitter>>> byChunk = new Reference2ObjectLinkedOpenHashMap<>();
        for (TileEntityTransmitter tile : toSend) {
            //Note: Tiles that were removed since they requested the update have nothing to update anymore on the client
            if (!tile.isRemoved() && tile.getLevel() instanceof ServerLevel level) {
                byChunk.computeIfAbsent(level, l -> new Long2ObjectLinkedOpenHashMap<>())
                      .computeIfAbsent(ChunkPos.pack(tile.getBlockPos()), pos -> new ArrayList<>())
                      .add(tile);
            }
        }
        for (Map.Entry<ServerLevel, Long2ObjectLinkedOpenHashMap<List<TileEntityTransmitter>>> levelEntry : byChunk.entrySet()) {
            ServerLevel level = levelEntry.getKey();
            for (Long2ObjectMap.Entry<List<TileEntityTransmitter>> chunkEntry : levelEntry.getValue().long2ObjectEntrySet()) {
                ChunkPos chunkPos = ChunkPos.unpack(chunkEntry.getLongKey());
                if (level.getChunkSource().chunkMap.getPlayers(chunkPos, false).isEmpty()) {
                    //Same as TileEntityUpdateable#sendUpdatePacket: nobody tracks the chunk, so there is nothing to send
                    continue;
                }
                List<TileEntityTransmitter> tiles = chunkEntry.getValue();
                List<PacketUpdateTile> updates = new ArrayList<>(Math.min(tiles.size(), MAX_UPDATES_PER_PACKET));
                for (TileEntityTransmitter tile : tiles) {
                    updates.add(createUpdate(level, tile));
                    if (updates.size() == MAX_UPDATES_PER_PACKET) {
                        send(level, chunkPos, updates);
                        updates = new ArrayList<>(MAX_UPDATES_PER_PACKET);
                    }
                }
                if (!updates.isEmpty()) {
                    send(level, chunkPos, updates);
                }
            }
        }
    }

    private static PacketUpdateTile createUpdate(ServerLevel level, TileEntityTransmitter tile) {
        //Same tag as TileEntityUpdateable#sendUpdatePacket writes
        try (ProblemReporter.ScopedCollector reporter = new ProblemReporter.ScopedCollector(tile.problemPath(), Mekanism.logger)) {
            TagValueOutput output = TagValueOutput.createWithContext(reporter, level.registryAccess());
            tile.writeReducedUpdatedTag(output);
            return new PacketUpdateTile(tile.getBlockPos(), output.buildResult());
        }
    }

    private static void send(ServerLevel level, ChunkPos chunkPos, List<PacketUpdateTile> updates) {
        if (updates.size() == 1) {
            PacketDistributor.sendToPlayersTrackingChunk(level, chunkPos, updates.getFirst());
        } else {
            PacketDistributor.sendToPlayersTrackingChunk(level, chunkPos, new PacketTransmitterUpdates(updates));
        }
    }
}
