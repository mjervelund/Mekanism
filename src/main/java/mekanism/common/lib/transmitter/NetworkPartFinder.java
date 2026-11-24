package mekanism.common.lib.transmitter;

import it.unimi.dsi.fastutil.longs.Long2IntMap;
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.LongArrayFIFOQueue;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.longs.LongList;
import java.util.ArrayList;
import java.util.List;
import mekanism.common.content.network.transmitter.Transmitter;
import mekanism.common.util.EnumUtils;
import mekanism.common.util.WorldUtils;
import net.minecraft.core.Direction;

/// Finds out whether the transmitters of a network that are left after some were removed are still connected, walking as little of the network as it can.
///
/// It searches from every transmitter next to the removed ones at once, one step per search in turn, through the network's own position map. Searches that
/// meet are joined. It stops as soon as at most one search can still grow: every other search has then walked a whole part of the network that is cut off
/// from the rest. So if the network is still connected, it only walks until the searches met (around the loop that got cut, for a grid of cables); if it
/// fell apart, it walks the smaller parts (and as much of the largest part), never the rest of the largest part.
final class NetworkPartFinder<TRANSMITTER extends Transmitter<?, ?, TRANSMITTER>> {

    private final Long2ObjectMap<TRANSMITTER> transmitters;
    private final Long2IntMap owners = new Long2IntOpenHashMap();
    private final List<LongArrayFIFOQueue> queues = new ArrayList<>();
    private final List<LongList> members = new ArrayList<>();
    private int[] parents;

    /// @param transmitters The network's transmitters by position, without the removed ones.
    NetworkPartFinder(Long2ObjectMap<TRANSMITTER> transmitters) {
        this.transmitters = transmitters;
        owners.defaultReturnValue(-1);
    }

    /// @param starts The positions of the network's transmitters next to the removed ones.
    ///
    /// @return The parts that are cut off from the rest of the network, each a complete list of positions; empty if the transmitters are still connected.
    ///     If the search walked everything (no part is left growing), the largest part is not returned, it stays.
    List<LongList> findCutOffParts(LongList starts) {
        int count = starts.size();
        parents = new int[count];
        for (int i = 0; i < count; i++) {
            long start = starts.getLong(i);
            parents[i] = i;
            LongArrayFIFOQueue queue = new LongArrayFIFOQueue();
            LongList part = new LongArrayList();
            if (owners.putIfAbsent(start, i) == -1) {
                queue.enqueue(start);
                part.add(start);
            }
            queues.add(queue);
            members.add(part);
        }
        while (true) {
            int growing = 0;
            for (int i = 0; i < count; i++) {
                if (parents[i] == i && !queues.get(i).isEmpty()) {
                    growing++;
                }
            }
            if (growing <= 1) {
                break;
            }
            for (int i = 0; i < count; i++) {
                if (parents[i] == i && !queues.get(i).isEmpty()) {
                    step(i);
                }
            }
        }
        List<LongList> cutOff = new ArrayList<>();
        int largest = -1;
        boolean anyGrowing = false;
        for (int i = 0; i < count; i++) {
            if (parents[i] == i) {
                if (!queues.get(i).isEmpty()) {
                    anyGrowing = true;
                } else if (!members.get(i).isEmpty()) {
                    cutOff.add(members.get(i));
                    if (largest == -1 || members.get(i).size() > cutOff.get(largest).size()) {
                        largest = cutOff.size() - 1;
                    }
                }
            }
        }
        if (!anyGrowing && largest != -1) {
            //Everything was walked: the largest part stays (if that is the only part, the network is still connected)
            cutOff.remove(largest);
        }
        return cutOff;
    }

    private void step(int search) {
        long pos = queues.get(search).dequeueLong();
        TRANSMITTER transmitter = transmitters.get(pos);
        for (Direction side : EnumUtils.DIRECTIONS) {
            long neighborPos = WorldUtils.relativePos(pos, side);
            TRANSMITTER neighbor = transmitters.get(neighborPos);
            if (neighbor != null && neighbor.isValid() && transmitter.isValidTransmitterBasic(neighbor.getTransmitterTile(), side)) {
                int owner = owners.putIfAbsent(neighborPos, search);
                if (owner == -1) {
                    queues.get(search).enqueue(neighborPos);
                    members.get(search).add(neighborPos);
                } else {
                    int other = find(owner);
                    if (other != search) {
                        search = join(search, other);
                    }
                }
            }
        }
    }

    private int find(int search) {
        while (parents[search] != search) {
            parents[search] = parents[parents[search]];
            search = parents[search];
        }
        return search;
    }

    /// Joins two searches into the one that found more transmitters so far, and returns it.
    private int join(int a, int b) {
        if (members.get(a).size() < members.get(b).size()) {
            int temp = a;
            a = b;
            b = temp;
        }
        parents[b] = a;
        members.get(a).addAll(members.get(b));
        members.get(b).clear();
        LongArrayFIFOQueue from = queues.get(b);
        LongArrayFIFOQueue to = queues.get(a);
        while (!from.isEmpty()) {
            to.enqueue(from.dequeueLong());
        }
        return a;
    }
}
