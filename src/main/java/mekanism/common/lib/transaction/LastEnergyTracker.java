package mekanism.common.lib.transaction;

import java.util.function.LongSupplier;
import net.neoforged.neoforge.transfer.transaction.TransactionContext;

public class LastEnergyTracker extends GameTimeBasedJournal<Long> {

    private long lastEnergyReceived;
    private long currentEnergyReceived;

    public LastEnergyTracker(LongSupplier gameTimeSupplier) {
        super(gameTimeSupplier);
    }

    @Override
    protected void tickChanged(long gameTime) {
        super.tickChanged(gameTime);
        lastEnergyReceived = currentEnergyReceived;
        currentEnergyReceived = 0;
    }

    public void received(long amount, TransactionContext transaction) {
        if (amount == 0) {
            //Nothing to record. Energy networks offer energy to every acceptor every tick, and most of those offers are refused (full machines, output-only
            // sides), so skip joining the transaction: a snapshot here adds this journal to the transaction's close list for nothing.
            // The tick rollover does not depend on this call: TileEntityMekanism ticks checkTickChanged every tick.
            return;
        }
        updateSnapshots(transaction);
        currentEnergyReceived += amount;
    }

    public long getLastEnergyReceived() {
        return lastEnergyReceived;
    }

    /// @apiNote For use in syncing to client
    public void setLastEnergyReceived(long lastEnergyReceived) {
        this.lastEnergyReceived = lastEnergyReceived;
    }

    @Override
    protected Long createSnapshot() {
        return currentEnergyReceived;
    }

    @Override
    protected void revertToSnapshot(Long snapshot) {
        currentEnergyReceived = snapshot;
    }
}