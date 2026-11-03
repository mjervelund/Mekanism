package mekanism.common.lib.transmitter;

import it.unimi.dsi.fastutil.longs.LongIterator;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import mekanism.api.IContentsListener;
import mekanism.api.math.MathUtils;
import mekanism.common.content.network.transmitter.BufferedTransmitter;
import mekanism.common.lib.math.Range3D;
import mekanism.common.util.MekanismUtils;
import mekanism.common.util.WorldUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.LevelChunk;
import net.neoforged.bus.api.Event;
import net.neoforged.neoforge.transfer.transaction.Transaction;
import net.neoforged.neoforge.transfer.transaction.TransactionContext;
import org.jspecify.annotations.Nullable;

public abstract class DynamicBufferedNetwork<ACCEPTOR, NETWORK extends DynamicBufferedNetwork<ACCEPTOR, NETWORK, BUFFER, TRANSMITTER>, BUFFER,
      TRANSMITTER extends BufferedTransmitter<ACCEPTOR, NETWORK, BUFFER, TRANSMITTER>> extends DynamicNetwork<ACCEPTOR, NETWORK, TRANSMITTER> implements IContentsListener {

    protected final LongSet chunks = new LongOpenHashSet();
    @Nullable
    private Range3D packetRange;
    protected long capacity;
    /// Set on the client when transmitters join this network; the capacity is recomputed once, on the next read, instead
    /// of once per joining transmitter (a network forming on the client sends one update per transmitter).
    private boolean capacityDirty;
    protected boolean needsUpdate;
    private boolean forceScaleUpdate;
    private long lastSaveShareWriteTime;
    private long lastMarkDirtyTime;
    public float currentScale;
    //Save dirty limiting (see #limitsSaveDirtying): a new network (any split or merge creates one) always marks its chunks once
    private boolean saveMarkForced = true;
    private boolean saveMarkDeferred;
    private long lastSaveMarkTime;
    private long amountAtSaveMark;
    private boolean saveShareRecomputeForced;

    protected DynamicBufferedNetwork(UUID networkID) {
        super(networkID);
    }

    protected abstract float computeContentScale();

    @Override
    public void onUpdate() {
        super.onUpdate();
        float scale = computeContentScale();
        if (MekanismUtils.scaleChanged(currentScale, scale)) {
            currentScale = scale;
            needsUpdate = true;
        }
    }

    @Override
    public void addNewTransmitters(Collection<TRANSMITTER> newTransmitters, CompatibleTransmitterValidator<ACCEPTOR, NETWORK, TRANSMITTER> transmitterValidator) {
        super.addNewTransmitters(newTransmitters, transmitterValidator);
        if (!forceScaleUpdate) {
            //If we currently have no transmitters, mark that we want to force our scale to update to the target after the initial adding
            forceScaleUpdate = isEmpty();
        }
    }

    @Override
    protected void addTransmitterFromCommit(TRANSMITTER transmitter) {
        super.addTransmitterFromCommit(transmitter);
        chunks.add(ChunkPos.pack(transmitter.getBlockPos()));
        //Update the capacity here, to make sure that we can actually absorb the buffer properly
        capacity = MathUtils.addClamped(capacity, transmitter.getCapacity());
        try (Transaction transaction = Transaction.openRoot()) {
            absorbBuffer(transmitter, transaction);
            transaction.commit();
        }
    }

    @Override
    protected void validTransmittersAdded() {
        super.validTransmittersAdded();
        if (saveMarkForced && limitsSaveDirtying()) {
            //Make sure a new network marks its chunks at the end of the tick even if its contents don't change
            deferMarkForSave();
        }
        clampBuffer();
        if (forceScaleUpdate) {
            forceScaleUpdate = false;
            forceScaleUpdate();
        }
        needsUpdate = true;
        //Flush the cached packet range. Eventually we may want to improve how it is cached some
        packetRange = null;
    }

    @Override
    public List<TRANSMITTER> adoptTransmittersAndAcceptorsFrom(NETWORK net) {
        List<TRANSMITTER> transmittersToUpdate = super.adoptTransmittersAndAcceptorsFrom(net);
        chunks.addAll(net.chunks);
        //Update the capacity
        updateCapacity();
        return transmittersToUpdate;
    }

    @Override
    protected void removeInvalid(@Nullable TRANSMITTER triggerTransmitter) {
        super.removeInvalid(triggerTransmitter);
        //Clamp the new buffer
        clampBuffer();
        //Update all shares
        try (Transaction transaction = Transaction.openRoot()) {
            updateSaveShares(triggerTransmitter, transaction);
            transaction.commit();
        }
    }

    @Override
    public void onContentsChanged() {
        markDirty();
    }

    @Override
    public void deregister() {
        super.deregister();
        chunks.clear();
        packetRange = null;
    }

    protected abstract void forceScaleUpdate();

    public abstract BUFFER getBuffer();

    public abstract void absorbBuffer(TRANSMITTER transmitter, TransactionContext transaction);

    public abstract void clampBuffer();

    public boolean isCompatibleWith(NETWORK other) {
        return true;
    }

    /// Marks the capacity as stale so that it gets recomputed by the next call to [#getCapacity()].
    public void markCapacityDirty() {
        capacityDirty = true;
    }

    public synchronized void updateCapacity() {
        capacityDirty = false;
        long sum = 0;
        for (TRANSMITTER transmitter : getTransmitters()) {
            long transmitterCapacity = transmitter.getCapacity();
            if (transmitterCapacity > Long.MAX_VALUE - capacity) {
                //Ensure we don't overflow
                sum = Long.MAX_VALUE;
                break;
            } else {
                sum += transmitterCapacity;
            }
        }
        if (capacity != sum) {
            capacity = sum;
        }
    }

    public long getCapacity() {
        if (capacityDirty) {
            updateCapacity();
        }
        return capacity;
    }

    @Override
    public Long getNetworkReaderCapacity() {
        return getCapacity();
    }

    protected void updateSaveShares(@Nullable TRANSMITTER triggerTransmitter, TransactionContext transaction) {
    }

    /// Same result as [#updateSaveShares] in a committed root transaction, for networks that can compute it directly (this runs once per
    /// network per tick whenever one of its transmitters is saved, so it is on the chunk-save path).
    ///
    /// @return `false` if this network has no direct implementation, then the transactional one is used.
    protected boolean updateSaveSharesDirect(@Nullable TRANSMITTER triggerTransmitter) {
        return false;
    }

    public final void validateSaveShares(TRANSMITTER triggerTransmitter, @Nullable TransactionContext transaction) {
        if (world == null) {
            //If the world is null, try falling back to the trigger transmitter's world.
            // Note: This also in theory could be null, so we double-check it is not before grabbing the game time
            world = triggerTransmitter.getLevel();
        }
        if (world != null && world.getGameTime() != lastSaveShareWriteTime) {
            if (transaction == null && !saveShareRecomputeForced && limitsSaveDirtying() && !mayMarkForSave()
                && !TransmitterNetworkRegistry.isInUnloadingChunk(triggerTransmitter)) {
                //Keep the shares of the last computation: the chunks of this network are not marked for saving right now, so a transmitter saved now
                // is saved because something else in its chunk changed, and the shares it would write are not needed yet (see #limitsSaveDirtying).
                // Each such save would otherwise recompute the shares of the whole network. The last computed shares still add up to what the network
                // stored back then, and whenever the chunks get marked (or one unloads, or the server stops) the shares are computed again first.
                return;
            }
            saveShareRecomputeForced = false;
            lastSaveShareWriteTime = world.getGameTime();
            if (transaction == null && updateSaveSharesDirect(triggerTransmitter)) {
                //Nothing outer can roll the shares back, so they were computed without a transaction
                return;
            }
            try (Transaction subTransaction = Transaction.open(transaction)) {
                updateSaveShares(triggerTransmitter, subTransaction);
                subTransaction.commit();
            }
        }
    }

    public void markDirty() {
        if (world != null && !world.isClientSide() && world.getGameTime() != lastMarkDirtyTime) {
            if (!mayMarkForSave()) {
                deferMarkForSave();
                return;
            }
            lastMarkDirtyTime = world.getGameTime();
            markChunksForSave();
        }
    }

    private void markChunksForSave() {
        if (limitsSaveDirtying()) {
            //Note: Only mark chunks that are there right now. WorldUtils#markChunkDirty would wait for (and so load in the middle of our tick) a chunk
            // of the network that is currently being loaded again, as this also runs at the end of the server tick and not only from content changes
            for (LongIterator iterator = chunks.iterator(); iterator.hasNext(); ) {
                long chunk = iterator.nextLong();
                LevelChunk levelChunk = world.getChunkSource().getChunkNow(ChunkPos.getX(chunk), ChunkPos.getZ(chunk));
                if (levelChunk != null) {
                    levelChunk.markUnsaved();
                }
            }
            saveMarkForced = false;
            saveMarkDeferred = false;
            lastSaveMarkTime = world.getGameTime();
            amountAtSaveMark = getSaveMarkAmount();
        } else {
            for (LongIterator iterator = chunks.iterator(); iterator.hasNext(); ) {
                long chunk = iterator.nextLong();
                WorldUtils.markChunkDirty(world, ChunkPos.getX(chunk), ChunkPos.getZ(chunk));
            }
        }
    }

    /// Whether changes to this network's contents and save shares mark its chunks unsaved only now and then instead of every tick.
    ///
    /// Every content change of a busy network (each tick) used to mark all of its chunks unsaved, and so did every save share that changed when one of
    /// its transmitters was saved. A large network's chunks then never stay saved: vanilla saves them again and again (eager chunk saves while the tick
    /// has time left, and every autosave), and every such save recomputes the save shares of the whole network. With this enabled, the chunks are
    /// marked at most once per [#SAVE_MARK_INTERVAL] ticks, or after [#SAVE_MARK_MIN_INTERVAL] ticks once the stored amount moved by more than a
    /// quarter of the capacity, and always when the network is created (split or merge) and before the server stops (see
    /// [TransmitterNetworkRegistry#prepareFinalSave()]). A chunk whose transmitters leave the network because it is unloaded (or becomes
    /// inaccessible) is marked when its ticket level drops and kept marked until it unloads (see [TransmitterNetworkRegistry#onTicketLevelChange]),
    /// so the shares those transmitters take with them are exactly the ones its last save stores.
    ///
    /// While the chunks are not due to be marked, saving a transmitter (because something else in its chunk changed) also keeps the save shares of the
    /// last computation instead of recomputing them for the whole network (see [#validateSaveShares]).
    ///
    /// The amount a chunk stores on disk may then lag the network by up to that interval, which only matters if the server crashes (vanilla's own
    /// chunks lag the same way between autosaves).
    public boolean limitsSaveDirtying() {
        return false;
    }

    /// The stored amount compared against [#getCapacity()] to decide when a change is large enough to mark the chunks early.
    protected long getSaveMarkAmount() {
        return 0;
    }

    /// Ticks between two save marks of a network whose contents keep changing (vanilla's autosave interval at 20 TPS).
    public static final int SAVE_MARK_INTERVAL = 5 * 60 * 20;
    /// Minimum ticks between two save marks caused by a large change of the stored amount.
    public static final int SAVE_MARK_MIN_INTERVAL = 30 * 20;

    /// @return `true` if a change to this network's saved state may mark its chunks (or a transmitter's chunk) unsaved now.
    public boolean mayMarkForSave() {
        if (!limitsSaveDirtying() || saveMarkForced || world == null) {
            return true;
        }
        long sinceLastMark = world.getGameTime() - lastSaveMarkTime;
        if (sinceLastMark >= SAVE_MARK_INTERVAL || sinceLastMark < 0) {
            return true;
        }
        return sinceLastMark >= SAVE_MARK_MIN_INTERVAL && Math.abs(getSaveMarkAmount() - amountAtSaveMark) > getCapacity() / 4;
    }

    /// Remembers that the network's saved state changed without marking its chunks, so that they get marked at the end of a later tick.
    public void deferMarkForSave() {
        if (!saveMarkDeferred) {
            saveMarkDeferred = true;
            TransmitterNetworkRegistry.deferMarkForSave(this);
        }
    }

    /// Called at the end of the server tick for networks with a deferred mark.
    ///
    /// @return `true` if the mark is still deferred.
    boolean handleDeferredMarkForSave() {
        if (!saveMarkDeferred) {
            return false;
        } else if (isEmpty() || world == null) {
            //Deregistered or not formed yet: nothing to mark (a new network gets marked once it formed)
            saveMarkDeferred = false;
            return false;
        } else if (mayMarkForSave()) {
            lastMarkDirtyTime = world.getGameTime();
            markChunksForSave();
            return false;
        }
        return true;
    }

    /// Before the final save of a stopping server: mark every chunk of the network and make the next save recompute the shares from the current
    /// contents, so that all chunks store shares of the same final amount.
    void prepareFinalSave() {
        if (world != null && !isEmpty()) {
            lastSaveShareWriteTime = Long.MIN_VALUE;
            saveShareRecomputeForced = true;
            markChunksForSave();
        }
    }

    @Nullable
    public Range3D getPacketRange() {
        if (packetRange == null) {
            packetRange = genPacketRange();
        }
        return packetRange;
    }

    @Nullable
    private Range3D genPacketRange() {
        if (isEmpty()) {
            deregister();
            return null;
        }
        boolean initialized = false;
        int minX = 0;
        int minZ = 0;
        int maxX = 0;
        int maxZ = 0;
        for (TRANSMITTER transmitter : getTransmitters()) {
            BlockPos pos = transmitter.getBlockPos();
            if (initialized) {
                if (pos.getX() < minX) {
                    minX = pos.getX();
                } else if (pos.getX() > maxX) {
                    maxX = pos.getX();
                }
                if (pos.getZ() < minZ) {
                    minZ = pos.getZ();
                } else if (pos.getZ() > maxZ) {
                    maxZ = pos.getZ();
                }
            } else {
                minX = pos.getX();
                minZ = pos.getZ();
                maxX = minX;
                maxZ = minZ;
                initialized = true;
            }
        }
        return new Range3D(minX, minZ, maxX, maxZ, world.dimension());
    }

    public static class TransferEvent<NETWORK extends DynamicBufferedNetwork<?, NETWORK, ?, ?>> extends Event {

        public final NETWORK network;

        public TransferEvent(NETWORK network) {
            this.network = network;
        }
    }
}