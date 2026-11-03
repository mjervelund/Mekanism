package mekanism.common.lib.transmitter;

import it.unimi.dsi.fastutil.longs.Long2BooleanMap;
import it.unimi.dsi.fastutil.longs.Long2BooleanMaps;
import it.unimi.dsi.fastutil.longs.Long2BooleanRBTreeMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectRBTreeMap;
import it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.objects.ObjectIterator;
import it.unimi.dsi.fastutil.objects.ObjectOpenHashSet;
import it.unimi.dsi.fastutil.objects.Reference2LongMap;
import it.unimi.dsi.fastutil.objects.Reference2LongOpenHashMap;
import it.unimi.dsi.fastutil.objects.ReferenceOpenHashSet;
import java.util.Collection;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import mekanism.api.MekanismAPI;
import mekanism.common.Mekanism;
import mekanism.common.content.network.transmitter.Transmitter;
import mekanism.common.lib.collection.LongMultimap;
import mekanism.common.tile.transmitter.TileEntityTransmitter;
import mekanism.common.util.EnumUtils;
import mekanism.common.util.WorldUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ChunkHolder;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunk;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.level.ChunkEvent;
import net.neoforged.neoforge.event.level.ChunkTicketLevelUpdatedEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import org.jspecify.annotations.Nullable;

@EventBusSubscriber(modid = Mekanism.MODID)
public class TransmitterNetworkRegistry {

    private static final Map<ResourceKey<Level>, DimData> dimensionStore = new HashMap<>();
    private static final Set<DynamicNetwork<?, ?, ?>> networks = new ObjectOpenHashSet<>();
    private static final Map<UUID, DynamicNetwork<?, ?, ?>> clientNetworks = new Object2ObjectOpenHashMap<>();
    private static Set<Transmitter<?, ?, ?>> invalidTransmitters = new ObjectOpenHashSet<>();
    private static Set<DynamicNetwork<?, ?, ?>> networksToChange = new ObjectOpenHashSet<>();
    private static Set<TileEntityTransmitter> transmittersToMarkForSave = new ReferenceOpenHashSet<>();
    private static Set<DynamicBufferedNetwork<?, ?, ?, ?>> networksWithDeferredSaveMark = new ReferenceOpenHashSet<>();
    /// Chunks with transmitters of networks that limit their save marks, which went from loaded to "unloaded", and the game time they did
    private static final Reference2LongMap<LevelChunk> unloadingChunks = new Reference2LongOpenHashMap<>();
    /// How long a chunk that became inaccessible without unloading is kept marked
    private static final int UNLOADING_CHUNK_MARK_TICKS = 5 * 60 * 20;

    public static void addClientNetwork(UUID networkID, DynamicNetwork<?, ?, ?> network) {
        if (!clientNetworks.containsKey(networkID)) {
            clientNetworks.put(networkID, network);
        }
    }

    @Nullable
    public static DynamicNetwork<?, ?, ?> getClientNetwork(UUID networkID) {
        return clientNetworks.get(networkID);
    }

    public static void removeClientNetwork(DynamicNetwork<?, ?, ?> network) {
        clientNetworks.remove(network.getUUID());
    }

    public static void clearClientNetworks() {
        clientNetworks.clear();
    }

    public static void reset() {
        transmittersToMarkForSave.clear();
        networksWithDeferredSaveMark.clear();
        unloadingChunks.clear();
        networks.clear();
        networksToChange.clear();
        invalidTransmitters.clear();
        dimensionStore.clear();
    }

    private static DimData dimData(ResourceKey<Level> dimension) {
        return dimensionStore.computeIfAbsent(dimension, _ -> new DimData());
    }

    @Nullable
    private static DimData dimDataOrNull(ResourceKey<Level> dimension) {
        return dimensionStore.get(dimension);
    }

    public static void trackTransmitter(Transmitter<?, ?, ?> transmitter) {
        dimData(transmitter.getDimension()).trackTransmitter(transmitter);
    }

    public static void untrackTransmitter(Transmitter<?, ?, ?> transmitter) {
        DimData dimData = dimDataOrNull(transmitter.getDimension());
        if (dimData != null) {
            dimData.untrackTransmitter(transmitter);
        }
    }

    public static void invalidateTransmitter(Transmitter<?, ?, ?> transmitter) {
        invalidTransmitters.add(transmitter);
        DimData dimData = dimDataOrNull(transmitter.getDimension());
        if (dimData != null) {
            dimData.invalidateTransmitter(transmitter);
        }
    }

    public static void registerOrphanTransmitter(Transmitter<?, ?, ?> transmitter) {
        if (!invalidTransmitters.remove(transmitter)) {
            //If we weren't an invalid transmitter, then we need to add it as a new orphan, otherwise removing it is good enough
            // as if it was an orphan before it still will be one, and if it wasn't then it still will be part of the network it
            // was in.
            dimData(transmitter.getDimension()).registerOrphanTransmitter(transmitter);
        }
    }

    /// Marks the transmitter's chunk as needing a save at the end of the current server tick, instead of right away.
    ///
    /// Use this for transmitters whose saved data changed as a side effect of saving *another* transmitter (network save shares): that runs while vanilla
    /// saves chunks, for example from inside `ChunkMap#saveChunksEagerly`, which iterates the set of chunks waiting to be saved. Marking a chunk unsaved
    /// there adds to that set during its iteration, which can corrupt it (and later crash with an `ArrayIndexOutOfBoundsException` in
    /// `LongLinkedOpenHashSet.rehash`). The end of the server tick runs after every level has ticked, outside any chunk saving.
    public static void markForSaveAtEndOfTick(TileEntityTransmitter tile) {
        if (tile.getLevel() == null || tile.getLevel().isClientSide()) {
            tile.markForSave();
        } else {
            transmittersToMarkForSave.add(tile);
        }
    }

    private static void markTransmittersForSave() {
        if (!transmittersToMarkForSave.isEmpty()) {
            Set<TileEntityTransmitter> toMark = transmittersToMarkForSave;
            transmittersToMarkForSave = new ReferenceOpenHashSet<>();
            for (TileEntityTransmitter tile : toMark) {
                if (!tile.isRemoved()) {
                    tile.markForSave();
                }
            }
        }
    }

    /// See [DynamicBufferedNetwork#deferMarkForSave()].
    static void deferMarkForSave(DynamicBufferedNetwork<?, ?, ?, ?> network) {
        networksWithDeferredSaveMark.add(network);
    }

    private static void handleDeferredSaveMarks() {
        if (!networksWithDeferredSaveMark.isEmpty()) {
            networksWithDeferredSaveMark.removeIf(network -> !network.handleDeferredMarkForSave());
        }
        if (!unloadingChunks.isEmpty()) {
            //Keep unloading chunks marked until they unload: something (an eager chunk save) may have saved them since they were marked
            for (ObjectIterator<Reference2LongMap.Entry<LevelChunk>> iterator = unloadingChunks.reference2LongEntrySet().iterator(); iterator.hasNext(); ) {
                Reference2LongMap.Entry<LevelChunk> entry = iterator.next();
                LevelChunk chunk = entry.getKey();
                if (chunk.getLevel().getGameTime() - entry.getLongValue() > UNLOADING_CHUNK_MARK_TICKS) {
                    iterator.remove();
                } else {
                    chunk.markUnsaved();
                }
            }
        }
    }

    @SubscribeEvent
    public static void onChunkUnload(ChunkEvent.Unload event) {
        //Note: This fires after the chunk was saved for the last time
        if (!unloadingChunks.isEmpty() && event.getChunk() instanceof LevelChunk chunk) {
            unloadingChunks.removeLong(chunk);
        }
    }

    /// Marks the chunks of every network that limits its save marks and makes their next save recompute the shares, so that the final save of a
    /// stopping server stores the current contents. Runs before the server saves and unloads its chunks for the last time.
    public static void prepareFinalSave() {
        for (DynamicNetwork<?, ?, ?> network : networks) {
            if (network instanceof DynamicBufferedNetwork<?, ?, ?, ?> bufferedNetwork && bufferedNetwork.limitsSaveDirtying()) {
                bufferedNetwork.prepareFinalSave();
            }
        }
    }

    @SubscribeEvent
    public static void onServerStopping(ServerStoppingEvent event) {
        prepareFinalSave();
    }

    public static void registerChangedNetwork(DynamicNetwork<?, ?, ?> network) {
        networksToChange.add(network);
    }

    public static void registerNetwork(DynamicNetwork<?, ?, ?> network) {
        networks.add(network);
    }

    public static void removeNetwork(DynamicNetwork<?, ?, ?> network) {
        networks.remove(network);
        networksToChange.remove(network);
    }

    @SubscribeEvent
    public static void onTick(ServerTickEvent.Post event) {
        markTransmittersForSave();
        handleChangedChunks();
        removeInvalidTransmitters();
        assignOrphans();
        commitChanges();
        if (event.getServer().tickRateManager().runsNormally()) {
            for (DynamicNetwork<?, ?, ?> net : networks) {
                net.onUpdate();
            }
        }
        handleDeferredSaveMarks();
    }

    @SubscribeEvent
    public static void onTicketLevelChange(ChunkTicketLevelUpdatedEvent event) {
        int newTicketLevel = event.getNewTicketLevel();
        int oldTicketLevel = event.getOldTicketLevel();
        boolean loaded;
        if (oldTicketLevel > ChunkMap.MAX_VIEW_DISTANCE && newTicketLevel <= ChunkMap.MAX_VIEW_DISTANCE) {
            //Went from "unloaded" to loaded
            loaded = true;
        } else if (newTicketLevel > ChunkMap.MAX_VIEW_DISTANCE && oldTicketLevel <= ChunkMap.MAX_VIEW_DISTANCE) {
            //Went from loaded to "unloaded"
            loaded = false;
        } else {
            //Load type stayed the same, just exit
            return;
        }
        DimData dimData = dimDataOrNull(event.getLevel().dimension());
        if (dimData != null) {
            dimData.onTicketLevelChange(event.getChunkPos(), loaded);
            ChunkHolder holder = event.getChunkHolder();
            if (holder != null && holder.getLatestChunk() instanceof LevelChunk chunk) {
                if (loaded) {
                    unloadingChunks.removeLong(chunk);
                } else if (dimData.hasSaveLimitedTransmitter(event.getChunkPos())) {
                    //The transmitters of this chunk are about to leave their network and take their save shares with them (at the end of this
                    // tick, or when the chunk unloads, if that comes first). Networks that limit their save marks may have left this chunk saved
                    // with older shares, so mark it now and keep it marked until it unloads (see #handleDeferredSaveMarks): then its last save
                    // stores the shares its transmitters take, like it did when these networks marked their chunks every tick. Mark the chunk
                    // itself, as it may already be inaccessible, where WorldUtils#markChunkDirty can no longer find it.
                    chunk.markUnsaved();
                    unloadingChunks.put(chunk, event.getLevel().getGameTime());
                }
            }
        }
    }

    private static void handleChangedChunks() {
        dimensionStore.values().forEach(DimData::handleChangedChunks);
    }

    private static void removeInvalidTransmitters() {
        if (!invalidTransmitters.isEmpty()) {
            //Ensure we copy the invalid transmitters, so that when we iterate and remove invalid ones
            // and add still valid ones as orphans, we actually add them as orphans rather than try
            // removing them as invalid and find out they are invalid
            Set<Transmitter<?, ?, ?>> toInvalidate = invalidTransmitters;
            invalidTransmitters = new ObjectOpenHashSet<>();
            if (MekanismAPI.debug) {
                Mekanism.logger.info("Dealing with {} invalid Transmitters", toInvalidate.size());
            }
            for (Transmitter<?, ?, ?> invalid : toInvalidate) {
                removeInvalidTransmitter(invalid);
            }
        }
    }

    private static <NETWORK extends DynamicNetwork<?, NETWORK, TRANSMITTER>, TRANSMITTER extends Transmitter<?, NETWORK, TRANSMITTER>>
    void removeInvalidTransmitter(Transmitter<?, NETWORK, TRANSMITTER> invalid) {
        if (!invalid.isOrphan() || !invalid.isValid()) {
            NETWORK n = invalid.getTransmitterNetwork();
            if (n != null) {
                n.invalidate((TRANSMITTER) invalid);
                if (!invalid.isValid()) {
                    //If the transmitter isn't valid, then we need to make sure we clear the network from it
                    // after invalidating the network, so that we can make sure that if this transmitter somehow
                    // gets revived, then it will be able to be properly handled as an orphan.
                    invalid.setTransmitterNetwork(null, false);
                }
            }
        }
    }

    private static void assignOrphans() {
        dimensionStore.values().forEach(DimData::assignOrphans);
    }

    private static void commitChanges() {
        if (!networksToChange.isEmpty()) {
            Set<DynamicNetwork<?, ?, ?>> networks = networksToChange;
            networksToChange = new ObjectOpenHashSet<>();
            for (DynamicNetwork<?, ?, ?> network : networks) {
                network.commit();
            }
        }
    }

    @Override
    public String toString() {
        return "Network Registry:\n" + networks;
    }

    public static Component[] toComponents() {
        Component[] components = new Component[networks.size()];
        int i = 0;
        for (DynamicNetwork<?, ?, ?> network : networks) {
            components[i++] = network.getTextComponent();
        }
        return components;
    }

    public static class OrphanPathFinder<ACCEPTOR, NETWORK extends DynamicNetwork<ACCEPTOR, NETWORK, TRANSMITTER>,
          TRANSMITTER extends Transmitter<ACCEPTOR, NETWORK, TRANSMITTER>> {

        private final CompatibleTransmitterValidator<ACCEPTOR, NETWORK, TRANSMITTER> transmitterValidator;
        private final Set<TRANSMITTER> connectedTransmitters = new ObjectOpenHashSet<>();
        private final Long2ObjectMap<ChunkAccess> chunkMap = new Long2ObjectOpenHashMap<>();
        private final Set<NETWORK> networksFound = new ObjectOpenHashSet<>();
        private final Set<BlockPos> iterated = new ObjectOpenHashSet<>();
        private final Deque<BlockPos> queue = new LinkedList<>();
        private final TRANSMITTER startPoint;
        @Nullable
        private final Level world;

        OrphanPathFinder(Transmitter<ACCEPTOR, NETWORK, TRANSMITTER> start) {
            startPoint = (TRANSMITTER) start;
            world = startPoint.getLevel();
            transmitterValidator = startPoint.getNewOrphanValidator();
        }

        NETWORK getNetworkFromOrphan(Long2ObjectMap<Transmitter<?, ?, ?>> orphanTransmitters) {
            //Calculate the network
            if (queue.peek() != null) {
                Mekanism.logger.error("OrphanPathFinder queue was not empty?!");
                queue.clear();
            }
            queue.push(startPoint.getBlockPos());
            while (queue.peek() != null) {
                iterate(orphanTransmitters, queue.removeFirst());
            }
            //Create the network or grab the found ones
            NETWORK network;
            if (networksFound.size() == 1) {
                if (MekanismAPI.debug) {
                    Mekanism.logger.info("Adding {} transmitters to single found network", connectedTransmitters.size());
                }
                network = networksFound.iterator().next();
            } else {
                if (MekanismAPI.debug) {
                    if (networksFound.isEmpty()) {
                        Mekanism.logger.info("No networks found. Creating new network for {} transmitters", connectedTransmitters.size());
                    } else {
                        Mekanism.logger.info("Merging {} networks with {} new transmitters", networksFound.size(), connectedTransmitters.size());
                    }
                }
                //TODO: Should we take one of the existing network's uuids if there is one?
                network = startPoint.createNetworkByMerging(networksFound);
            }
            network.addNewTransmitters(connectedTransmitters, transmitterValidator);
            return network;
        }

        private void iterate(Long2ObjectMap<Transmitter<?, ?, ?>> orphanTransmitters, BlockPos from) {
            if (iterated.add(from)) {
                Transmitter<?, ?, ?> transmitter = orphanTransmitters.get(from.asLong());
                if (transmitter != null) {
                    if (transmitter.isValid() && transmitter.isOrphan() && startPoint.supportsTransmissionType(transmitter) &&
                        transmitterValidator.isTransmitterCompatible(transmitter)) {
                        connectedTransmitters.add((TRANSMITTER) transmitter);
                        transmitter.setOrphan(false);
                        BlockPos.MutableBlockPos directionPos = new BlockPos.MutableBlockPos();
                        for (Direction direction : EnumUtils.DIRECTIONS) {
                            directionPos.setWithOffset(from, direction);
                            if (!iterated.contains(directionPos)) {
                                TileEntityTransmitter tile = WorldUtils.getTileEntity(TileEntityTransmitter.class, world, chunkMap, directionPos);
                                if (tile != null && transmitter.isValidTransmitterBasic(tile, direction)) {
                                    queue.addLast(directionPos.immutable());
                                }
                            }
                        }
                    }
                } else {
                    TileEntityTransmitter tile = WorldUtils.getTileEntity(TileEntityTransmitter.class, world, chunkMap, from);
                    if (tile != null && startPoint.supportsTransmissionType(tile)) {
                        NETWORK net = (NETWORK) tile.getTransmitter().getTransmitterNetwork();
                        //Make sure that there is an external network
                        if (net != null && transmitterValidator.isNetworkCompatible(net)) {
                            networksFound.add(net);
                        }
                    }
                }
            }
        }
    }

    private TransmitterNetworkRegistry() {
    }

    private static class DimData {

        private final LongMultimap<Transmitter<?, ?, ?>> transmitters = new LongMultimap<>();
        private /*chunkpos*/ Long2BooleanMap changedTicketChunks = newL2BMap();
        private /*blockpos*/ Long2ObjectMap<Transmitter<?, ?, ?>> newOrphanTransmitters = newL2OMap();

        private static Long2ObjectRBTreeMap<Transmitter<?, ?, ?>> newL2OMap() {
            return new Long2ObjectRBTreeMap<>();
        }

        private static Long2BooleanMap newL2BMap() {
            return new Long2BooleanRBTreeMap();
        }

        void trackTransmitter(Transmitter<?, ?, ?> transmitter) {
            transmitters.put(ChunkPos.pack(transmitter.getBlockPos()), transmitter);
        }

        void untrackTransmitter(Transmitter<?, ?, ?> transmitter) {
            transmitters.remove(ChunkPos.pack(transmitter.getBlockPos()), transmitter);
        }

        boolean hasSaveLimitedTransmitter(long chunkPos) {
            Collection<Transmitter<?, ?, ?>> chunkTransmitters = transmitters.get(chunkPos);
            if (chunkTransmitters != null) {
                for (Transmitter<?, ?, ?> transmitter : chunkTransmitters) {
                    if (transmitter.getTransmitterNetwork() instanceof DynamicBufferedNetwork<?, ?, ?, ?> network && network.limitsSaveDirtying()) {
                        return true;
                    }
                }
            }
            return false;
        }

        void onTicketLevelChange(long chunkPos, boolean loaded) {
            if (transmitters.containsKey(chunkPos)) {
                //Only track it if we have any transmitters in that chunk
                if (changedTicketChunks.getOrDefault(chunkPos, loaded) != loaded) {
                    //If we are watching the chunk and the loaded state isn't what we already had it as,
                    // then remove it as it didn't actually change. In theory in all cases this is equivalent
                    // to just checking if changeTicketChunks contains chunk, but is slightly more accurate
                    // in case for some reason we get two load or unload notifications in a row
                    changedTicketChunks.remove(chunkPos);
                } else {
                    // Otherwise, make sure the map is aware of the change
                    changedTicketChunks.put(chunkPos, loaded);
                }
            }
        }

        void handleChangedChunks() {
            if (!changedTicketChunks.isEmpty()) {
                Long2BooleanMap changed = changedTicketChunks;
                changedTicketChunks = newL2BMap();
                if (MekanismAPI.debug) {
                    Mekanism.logger.info("Dealing with {} changed chunks", changed.size());
                }
                for (ObjectIterator<Long2BooleanMap.Entry> iterator = Long2BooleanMaps.fastIterator(changed); iterator.hasNext(); ) {
                    Long2BooleanMap.Entry entry = iterator.next();
                    long chunk = entry.getLongKey();
                    boolean loaded = entry.getBooleanValue();
                    Collection<Transmitter<?, ?, ?>> chunkTransmitters = transmitters.get(chunk);
                    int transmitterCount;
                    //Note: Theoretically could be null, due to the unchecked get call, but FastUtils doesn't define jspecify annotations currently
                    if (chunkTransmitters != null) {
                        transmitterCount = chunkTransmitters.size();
                        for (Transmitter<?, ?, ?> transmitter : chunkTransmitters) {
                            transmitter.getTransmitterTile().chunkAccessibilityChange(loaded);
                        }
                    } else {
                        transmitterCount = 0;
                    }
                    if (MekanismAPI.debug) {
                        Mekanism.logger.info("{} {} transmitters in chunk: {}, {}", loaded ? "Loaded" : "Unloaded", transmitterCount, ChunkPos.getX(chunk), ChunkPos.getZ(chunk));
                    }
                }
            }
        }

        void invalidateTransmitter(Transmitter<?, ?, ?> transmitter) {
            long coord = transmitter.getWorldPositionLong();
            Transmitter<?, ?, ?> removed = newOrphanTransmitters.remove(coord);
            if (removed != null && removed != transmitter) {
                Mekanism.logger.error("Different orphan transmitter was registered at location during removal! {}", blockToStr(coord));
                newOrphanTransmitters.put(coord, transmitter);//put it back? TODO: work out if this is correct, probably continues the error
            }
        }

        void registerOrphanTransmitter(Transmitter<?, ?, ?> transmitter) {
            long pos = transmitter.getWorldPositionLong();
            Transmitter<?, ?, ?> previous = newOrphanTransmitters.put(pos, transmitter);
            if (previous != null && previous != transmitter && previous.isValid()) {
                Mekanism.logger.error("Different orphan transmitter was already registered at location! {}", blockToStr(pos));
            }
        }

        private void assignOrphans() {
            if (!newOrphanTransmitters.isEmpty()) {
                Long2ObjectMap<Transmitter<?, ?, ?>> orphanTransmitters = newOrphanTransmitters;
                newOrphanTransmitters = newL2OMap();
                if (MekanismAPI.debug) {
                    Mekanism.logger.info("Dealing with {} orphan Transmitters", orphanTransmitters.size());
                }

                for (Transmitter<?, ?, ?> orphanTransmitter : orphanTransmitters.values()) {
                    if (orphanTransmitter.isValid() && orphanTransmitter.isOrphan()) {
                        OrphanPathFinder<?, ?, ?> finder = new OrphanPathFinder<>(orphanTransmitter);
                        networksToChange.add(finder.getNetworkFromOrphan(orphanTransmitters));
                    }
                }
            }
        }

        static String blockToStr(long blockPos) {
            return String.format("x=%d, y=%d, z=%d", BlockPos.getX(blockPos), BlockPos.getY(blockPos), BlockPos.getZ(blockPos));
        }
    }
}