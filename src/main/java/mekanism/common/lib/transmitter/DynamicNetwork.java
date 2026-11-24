package mekanism.common.lib.transmitter;

import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectMaps;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongArrayFIFOQueue;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.longs.LongIterator;
import it.unimi.dsi.fastutil.longs.LongList;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import it.unimi.dsi.fastutil.objects.ObjectIterator;
import it.unimi.dsi.fastutil.objects.ObjectOpenHashSet;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import mekanism.api.text.IHasTextComponent;
import mekanism.common.Mekanism;
import mekanism.common.content.network.transmitter.Transmitter;
import mekanism.common.lib.transmitter.acceptor.NetworkAcceptorCache;
import mekanism.common.util.EnumUtils;
import mekanism.common.util.WorldUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;
import net.neoforged.fml.util.thread.EffectiveSide;
import net.neoforged.neoforge.transfer.transaction.Transaction;
import org.jspecify.annotations.Nullable;

public abstract class DynamicNetwork<ACCEPTOR, NETWORK extends DynamicNetwork<ACCEPTOR, NETWORK, TRANSMITTER>,
      TRANSMITTER extends Transmitter<ACCEPTOR, NETWORK, TRANSMITTER>> implements INetworkDataHandler, IHasTextComponent {

    protected final Long2ObjectMap<TRANSMITTER> positionedTransmitters = new Long2ObjectOpenHashMap<>();
    protected final Set<TRANSMITTER> transmittersToAdd = new ObjectOpenHashSet<>();
    protected final NetworkAcceptorCache<ACCEPTOR> acceptorCache = new NetworkAcceptorCache<>();
    @Nullable
    protected Level world;
    private final UUID uuid;
    @Nullable
    private CompatibleTransmitterValidator<ACCEPTOR, NETWORK, TRANSMITTER> transmitterValidator;

    protected DynamicNetwork(UUID networkID) {
        this.uuid = networkID;
    }

    public UUID getUUID() {
        return uuid;
    }

    @SuppressWarnings("unchecked")
    protected NETWORK getNetwork() {
        return (NETWORK) this;
    }

    public void commit() {
        if (!transmittersToAdd.isEmpty()) {
            boolean addedValidTransmitters = false;
            List<TRANSMITTER> transmittersToUpdate = new ArrayList<>();
            for (TRANSMITTER transmitter : transmittersToAdd) {
                //Note: Transmitter should not be able to be null here, but I ran into a null pointer
                // pointing to it being null that I could not reproduce, so just added this as a safety check
                if (transmitter != null && transmitter.isValid()) {
                    addedValidTransmitters = true;
                    if (world == null) {
                        world = transmitter.getLevel();
                    }
                    for (Direction side : EnumUtils.DIRECTIONS) {
                        acceptorCache.updateTransmitterOnSide(transmitter, side);
                    }
                    if (transmitter.setTransmitterNetwork(getNetwork(), false)) {
                        transmittersToUpdate.add(transmitter);
                    }
                    addTransmitterFromCommit(transmitter);
                }
            }
            transmittersToAdd.clear();
            if (addedValidTransmitters) {
                validTransmittersAdded();
                transmittersToUpdate.forEach(Transmitter::requestsUpdate);
            }
        }
        acceptorCache.commit();
        transmitterValidator = null;
    }

    @Nullable
    public CompatibleTransmitterValidator<ACCEPTOR, NETWORK, TRANSMITTER> getTransmitterValidator() {
        return transmitterValidator;
    }

    public void addNewTransmitters(Collection<TRANSMITTER> newTransmitters, CompatibleTransmitterValidator<ACCEPTOR, NETWORK, TRANSMITTER> transmitterValidator) {
        transmittersToAdd.addAll(newTransmitters);
        //Cache the transmitter validator in the network, so that if we have a case of orphans being on either side of
        // an existing network, and the orphans are what have contents stored, that then we don't try merging them all
        // together when they may not actually be able to have both sets of orphans connect. After the network is
        // updated (committed), this cached validator will be unset
        this.transmitterValidator = transmitterValidator;
    }

    @Nullable
    public TRANSMITTER getTransmitter(long pos) {
        return positionedTransmitters.get(pos);
    }

    @Nullable
    public TRANSMITTER getTransmitter(BlockPos pos) {
        return getTransmitter(pos.asLong());
    }

    protected void addTransmitterFromCommit(TRANSMITTER transmitter) {
        positionedTransmitters.put(transmitter.getBlockPos().asLong(), transmitter);
    }

    protected void validTransmittersAdded() {
    }

    public boolean isClientSide() {
        return world == null ? EffectiveSide.get().isClient() : world.isClientSide();
    }

    public void invalidate(@Nullable TRANSMITTER triggerTransmitter) {
        if (triggerTransmitter != null && !triggerTransmitter.isValid() && !isClientSide() && removeInvalidKeepingNetwork()) {
            return;
        }
        if (transmittersSize() == 1 && triggerTransmitter != null && !triggerTransmitter.isValid()) {
            //We're destroying the last transmitter in the network
            //Note: We check it isn't valid to make sure we are destroying it and not just changing redstone sensitivity
            onLastTransmitterRemoved(triggerTransmitter);
        }
        removeInvalid(triggerTransmitter);
        //Now invalidate the transmitters
        if (!isClientSide()) {
            try (Transaction transaction = Transaction.openRoot()) {
                for (TRANSMITTER transmitter : getTransmitters()) {
                    if (transmitter.isValid()) {
                        transmitter.takeShare(transaction);
                        transmitter.setTransmitterNetwork(null);
                        TransmitterNetworkRegistry.registerOrphanTransmitter(transmitter);
                    }
                }
                transaction.commit();
            }
        }
        deregister();
    }

    protected void onLastTransmitterRemoved(TRANSMITTER triggerTransmitter) {
    }

    /// Whether this network may stay formed when some of its transmitters are removed or unloaded, instead of being invalidated and re-formed from all
    /// of its transmitters (see [#removeInvalidKeepingNetwork()]). Networks that enable this implement [#takeSharesOfLeavingTransmitters] and
    /// [#onTransmittersRemoved()].
    protected boolean keepsNetworkOnRemoval() {
        return false;
    }

    /// Removes the transmitters that are no longer valid (broken, or in a chunk that became inaccessible) without re-forming the network.
    ///
    /// Invalidating a network makes every one of its transmitters take its share, become an orphan, get walked again and join a new network (with new
    /// acceptor lookups, save shares and client updates for each). For a large network that is a lot of work every time a single transmitter at its edge
    /// unloads. Removing transmitters can only split a network, never join it with anything, so instead walk the remaining transmitters through this
    /// network's own position map (the same connection check as [TransmitterNetworkRegistry.OrphanPathFinder], but no world lookups) starting next to
    /// the removed ones. If they are still connected, the network just drops the removed transmitters. If they fell apart, the largest part stays in
    /// this network and only the transmitters of the smaller parts take their shares and become orphans, which then form their own networks as usual.
    ///
    /// @return `false` if nothing was changed and the network has to be invalidated as before.
    private boolean removeInvalidKeepingNetwork() {
        if (!keepsNetworkOnRemoval() || !transmittersToAdd.isEmpty()) {
            return false;
        }
        List<TRANSMITTER> removed = new ArrayList<>();
        for (TRANSMITTER transmitter : getTransmitters()) {
            if (!transmitter.isValid()) {
                removed.add(transmitter);
            }
        }
        int remaining = transmittersSize() - removed.size();
        if (removed.isEmpty() || remaining == 0) {
            return false;
        }
        //Walk the remaining transmitters from the ones next to the removed transmitters
        LongArrayFIFOQueue queue = new LongArrayFIFOQueue();
        LongSet visited = new LongOpenHashSet(remaining);
        List<LongList> parts = new ArrayList<>();
        for (TRANSMITTER removedTransmitter : removed) {
            long removedPos = removedTransmitter.getWorldPositionLong();
            for (Direction side : EnumUtils.DIRECTIONS) {
                long start = WorldUtils.relativePos(removedPos, side);
                if (!visited.contains(start)) {
                    TRANSMITTER transmitter = getTransmitter(start);
                    if (transmitter != null && transmitter.isValid()) {
                        parts.add(walkConnected(start, queue, visited));
                        if (visited.size() == remaining) {
                            break;
                        }
                    }
                }
            }
            if (visited.size() == remaining) {
                break;
            }
        }
        if (visited.size() != remaining) {
            //Some transmitters cannot be reached from next to the removed ones (the network was not connected the way we expect): re-form it
            return false;
        }
        for (TRANSMITTER removedTransmitter : removed) {
            positionedTransmitters.remove(removedTransmitter.getWorldPositionLong());
            acceptorCache.removeTransmitter(removedTransmitter);
            removedTransmitter.setTransmitterNetwork(null, false);
        }
        if (parts.size() > 1) {
            //Keep the largest part, the others leave and form their own networks
            LongList largest = parts.getFirst();
            for (LongList part : parts) {
                if (part.size() > largest.size()) {
                    largest = part;
                }
            }
            List<TRANSMITTER> leaving = new ArrayList<>(remaining - largest.size());
            for (LongList part : parts) {
                if (part != largest) {
                    for (LongIterator iterator = part.iterator(); iterator.hasNext(); ) {
                        leaving.add(positionedTransmitters.get(iterator.nextLong()));
                    }
                }
            }
            takeSharesOfLeavingTransmitters(leaving);
            for (TRANSMITTER transmitter : leaving) {
                positionedTransmitters.remove(transmitter.getWorldPositionLong());
                acceptorCache.removeTransmitter(transmitter);
                transmitter.setTransmitterNetwork(null);
                TransmitterNetworkRegistry.registerOrphanTransmitter(transmitter);
            }
        }
        onTransmittersRemoved();
        return true;
    }

    /// Collects the transmitters of this network connected to the one at `start` (which must be a valid transmitter of this network), marking them visited.
    private LongList walkConnected(long start, LongArrayFIFOQueue queue, LongSet visited) {
        LongList part = new LongArrayList();
        visited.add(start);
        queue.enqueue(start);
        while (!queue.isEmpty()) {
            long pos = queue.dequeueLong();
            part.add(pos);
            TRANSMITTER transmitter = positionedTransmitters.get(pos);
            for (Direction side : EnumUtils.DIRECTIONS) {
                long neighborPos = WorldUtils.relativePos(pos, side);
                if (!visited.contains(neighborPos)) {
                    TRANSMITTER neighbor = positionedTransmitters.get(neighborPos);
                    if (neighbor != null && neighbor.isValid() && transmitter.isValidTransmitterBasic(neighbor.getTransmitterTile(), side)) {
                        visited.add(neighborPos);
                        queue.enqueue(neighborPos);
                    }
                }
            }
        }
        return part;
    }

    /// Called before the given transmitters (still part of this network) leave it as orphans because removing other transmitters disconnected them from
    /// the part of the network that stays; each of them has to take its share like [#invalidate] makes them do.
    protected void takeSharesOfLeavingTransmitters(List<TRANSMITTER> leaving) {
        throw new UnsupportedOperationException();
    }

    /// Called after [#removeInvalidKeepingNetwork()] removed transmitters from this network.
    protected void onTransmittersRemoved() {
    }

    protected void removeInvalid(@Nullable TRANSMITTER triggerTransmitter) {
        //Remove invalid transmitters first for share calculations
        getTransmitters().removeIf(transmitter -> !transmitter.isValid());
    }

    public void acceptorChanged(TRANSMITTER transmitter, Direction side) {
        acceptorCache.acceptorChanged(transmitter, side);
    }

    public List<TRANSMITTER> adoptTransmittersAndAcceptorsFrom(NETWORK net) {
        positionedTransmitters.putAll(net.positionedTransmitters);
        List<TRANSMITTER> transmittersToUpdate = new ArrayList<>();
        for (ObjectIterator<Long2ObjectMap.Entry<TRANSMITTER>> iterator = Long2ObjectMaps.fastIterator(net.positionedTransmitters); iterator.hasNext(); ) {
            Long2ObjectMap.Entry<TRANSMITTER> entry = iterator.next();
            TRANSMITTER transmitter = entry.getValue();
            positionedTransmitters.put(entry.getLongKey(), transmitter);
            if (transmitter.setTransmitterNetwork(getNetwork(), false)) {
                transmittersToUpdate.add(transmitter);
            }
        }
        transmittersToAdd.addAll(net.transmittersToAdd);
        acceptorCache.adoptAcceptors(net.acceptorCache);
        return transmittersToUpdate;
    }

    protected void adoptAllAndRegister(Collection<NETWORK> networks) {
        List<TRANSMITTER> transmittersToUpdate = new ArrayList<>();
        for (NETWORK net : networks) {
            if (net != null) {
                transmittersToUpdate.addAll(adoptTransmittersAndAcceptorsFrom(net));
                net.deregister();
            }
        }
        register();
        transmittersToUpdate.forEach(Transmitter::requestsUpdate);
    }

    public void register() {
        if (isClientSide()) {
            TransmitterNetworkRegistry.addClientNetwork(getUUID(), this);
        } else {
            TransmitterNetworkRegistry.registerNetwork(this);
        }
    }

    public void deregister() {
        positionedTransmitters.clear();
        transmittersToAdd.clear();
        acceptorCache.deregister();
        transmitterValidator = null;
        if (isClientSide()) {
            TransmitterNetworkRegistry.removeClientNetwork(this);
        } else {
            TransmitterNetworkRegistry.removeNetwork(this);
        }
    }

    public boolean isEmpty() {
        return positionedTransmitters.isEmpty();
    }

    public int getAcceptorCount() {
        return acceptorCache.getAcceptorCount();
    }

    @Nullable
    public Level getWorld() {
        return world;
    }

    /// @apiNote Only called on the server
    public void onUpdate() {
    }

    public Collection<TRANSMITTER> getTransmitters() {
        return positionedTransmitters.values();
    }

    public void addTransmitter(TRANSMITTER transmitter) {
        positionedTransmitters.put(transmitter.getBlockPos().asLong(), transmitter);
    }

    public void removeTransmitter(TRANSMITTER transmitter) {
        removePositionedTransmitter(transmitter);
        if (isEmpty()) {
            deregister();
        }
    }

    private void removePositionedTransmitter(TRANSMITTER transmitter) {
        BlockPos pos = transmitter.getBlockPos();
        TRANSMITTER currentTransmitter = getTransmitter(pos);
        if (currentTransmitter != null) {
            //This shouldn't be null but if it is, don't bother attempting to remove
            if (currentTransmitter != transmitter) {
                Level world = this.world;
                if (world == null) {
                    //If the world is null, grab it from the transmitter
                    world = transmitter.getLevel();
                }
                if (world != null && world.isClientSide()) {
                    //On the client just exit instead of warning and then removing the unexpected transmitter.
                    // When the client dies at spawn in single player the order of operations is:
                    // - new tiles get added/loaded (so the positioned transmitter gets overridden with the correct one)
                    // - The old one unloads which causes this removedPositionedTransmitter call to take place
                    return;
                }
                Mekanism.logger.warn("Removed transmitter at position: {} in {} was different than expected.", pos, world == null ? null : world.dimension().identifier());
            }
            positionedTransmitters.remove(pos.asLong());
        }
    }

    public int transmittersSize() {
        return positionedTransmitters.size();
    }

    public boolean hasAcceptor(BlockPos acceptorPos) {
        return acceptorCache.hasAcceptor(acceptorPos);
    }

    @Nullable
    public ACCEPTOR getCachedAcceptor(long acceptorPos, Direction side) {
        return acceptorCache.getCachedAcceptor(acceptorPos, side);
    }

    public Set<Direction> getAcceptorDirections(long pos) {
        return acceptorCache.getAcceptorDirections(pos);
    }

    @Override
    public boolean equals(@Nullable Object o) {
        if (o == this) {
            return true;
        } else if (o instanceof DynamicNetwork<?, ?, ?> other) {
            return uuid.equals(other.uuid);
        }
        return false;
    }

    @Override
    public int hashCode() {
        return uuid.hashCode();
    }
}