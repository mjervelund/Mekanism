package mekanism.common.capabilities.proxy;

import mekanism.api.AutomationType;
import mekanism.api.energy.IEnergyContainer;
import mekanism.common.capabilities.energy.BasicEnergyContainer;
import mekanism.common.capabilities.holder.single.BasicSingleHolder;
import mekanism.common.capabilities.holder.single.ISingleContainerHolder;
import net.minecraft.core.Direction;
import net.neoforged.neoforge.transfer.energy.EnergyHandler;
import net.neoforged.neoforge.transfer.transaction.TransactionContext;
import org.jspecify.annotations.Nullable;
import org.jetbrains.annotations.Range;

public class ProxyEnergyHandler extends ProxyHandler<ISingleContainerHolder<IEnergyContainer>> implements EnergyHandler {

    public ProxyEnergyHandler(@Nullable Direction side, ISingleContainerHolder<IEnergyContainer> holder) {
        super(side, holder);
    }

    @Nullable
    private IEnergyContainer getContainer() {
        return holder.getContainer(side);
    }

    @Override
    @Range(from = 0, to = Long.MAX_VALUE)
    public long getAmountAsLong() {
        IEnergyContainer container = getContainer();
        return container == null ? 0 : container.getAmountAsLong();
    }

    @Override
    @Range(from = 0, to = Long.MAX_VALUE)
    public long getCapacityAsLong() {
        IEnergyContainer container = getContainer();
        return container == null ? 0 : container.getCapacityAsLong();
    }

    /// {@return `true` if [#insert(int, TransactionContext)] would currently insert nothing, whatever the amount}
    ///
    /// This lets callers that offer energy to many handlers every tick (like energy networks) skip handlers that never accept energy from this side, such
    /// as generators, without opening a transaction for them.
    public boolean cannotInsert() {
        if (readOnlyInsert()) {
            return true;
        }
        IEnergyContainer container = getContainer();
        return container == null || !container.isValidForInsertion(AutomationType.handler(side));
    }

    /// {@return `true` if [#insert(int, TransactionContext)] inserts nothing now and will keep doing so for as long as this handler is exposed}
    ///
    /// Holds for a side without access, and for a holder with a single container (like a generator's) whose container never accepts insertion from
    /// this side's automation type: then the facing and the side do not matter, as the only container that could be returned refuses the insert. Side
    /// configuration changes invalidate the capability, so a holder whose answer depends on them is not covered here.
    public boolean neverInserts() {
        if (readOnly) {
            return true;
        }
        if (holder != null && holder.getClass() == BasicSingleHolder.class) {
            IEnergyContainer container = ((BasicSingleHolder<IEnergyContainer>) holder).getOnlyContainer();
            return container instanceof BasicEnergyContainer basic && basic.neverValidForInsertion(AutomationType.handler(side));
        }
        return false;
    }

    @Override
    @Range(from = 0, to = Integer.MAX_VALUE)
    public int insert(@Range(from = 0, to = Integer.MAX_VALUE) int amount, TransactionContext transaction) {
        if (readOnlyInsert()) {
            return 0;
        }
        IEnergyContainer container = getContainer();
        return container == null ? 0 : container.insert(amount, transaction, AutomationType.handler(side));
    }

    @Override
    @Range(from = 0, to = Integer.MAX_VALUE)
    public int extract(@Range(from = 0, to = Integer.MAX_VALUE) int amount, TransactionContext transaction) {
        if (readOnlyExtract()) {
            return 0;
        }
        IEnergyContainer container = getContainer();
        return container == null ? 0 : container.extract(amount, transaction, AutomationType.handler(side));
    }
}