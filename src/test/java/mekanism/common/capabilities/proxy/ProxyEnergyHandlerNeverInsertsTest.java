package mekanism.common.capabilities.proxy;

import java.util.EnumSet;
import java.util.Set;
import mekanism.api.AutomationType;
import mekanism.api.RelativeSide;
import mekanism.api.energy.IEnergyContainer;
import mekanism.common.capabilities.energy.BasicEnergyContainer;
import mekanism.common.capabilities.holder.single.BasicSingleHolder;
import mekanism.common.capabilities.holder.single.ISingleContainerHolder;
import net.minecraft.core.Direction;
import net.neoforged.neoforge.transfer.transaction.Transaction;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("ProxyEnergyHandler.neverInserts only claims handlers that refuse every insert, whatever the facing")
class ProxyEnergyHandlerNeverInsertsTest {

    private static final Set<RelativeSide> BOTTOM = EnumSet.of(RelativeSide.BOTTOM);

    private static ProxyEnergyHandler handler(BasicEnergyContainer container, Direction[] facing, Set<RelativeSide> sides, @Nullable Direction side) {
        return new ProxyEnergyHandler(side, new BasicSingleHolder<>(container, () -> facing[0], sides));
    }

    private static int insertAny(ProxyEnergyHandler handler) {
        try (Transaction transaction = Transaction.openRoot()) {
            return handler.insert(1_000, transaction);
        }
    }

    @Test
    @DisplayName("Generator container (output only): never inserts on any side or facing, and insert agrees")
    void generator() {
        Direction[] facing = {Direction.NORTH};
        BasicEnergyContainer container = BasicEnergyContainer.output(10_000, null);
        for (Direction side : Direction.values()) {
            ProxyEnergyHandler handler = handler(container, facing, BOTTOM, side);
            Assertions.assertTrue(handler.neverInserts(), side.toString());
            for (Direction face : Direction.values()) {
                facing[0] = face;
                Assertions.assertTrue(handler.cannotInsert());
                Assertions.assertEquals(0, insertAny(handler));
            }
            facing[0] = Direction.NORTH;
        }
    }

    @Test
    @DisplayName("Side without access (null side) never inserts")
    void readOnly() {
        Assertions.assertTrue(handler(BasicEnergyContainer.create(10_000, null), new Direction[]{Direction.NORTH}, BOTTOM, null).neverInserts());
    }

    @Test
    @DisplayName("Containers that accept external insertion, or whose rule may depend on state, are not claimed")
    void notClaimed() {
        Direction[] facing = {Direction.NORTH};
        Assertions.assertFalse(handler(BasicEnergyContainer.create(10_000, null), facing, BOTTOM, Direction.DOWN).neverInserts());
        Assertions.assertFalse(handler(BasicEnergyContainer.input(10_000, null), facing, BOTTOM, Direction.DOWN).neverInserts());
        //Same answer as output() today, but through a custom predicate that could change with state: must stay a per-tick check
        BasicEnergyContainer custom = BasicEnergyContainer.create(10_000, at -> true, at -> at == AutomationType.INTERNAL, null);
        Assertions.assertFalse(handler(custom, facing, BOTTOM, Direction.DOWN).neverInserts());
        //A side the holder does not expose today could be exposed after a facing change: not claimed unless the container refuses anyway
        Assertions.assertFalse(handler(BasicEnergyContainer.create(10_000, null), facing, BOTTOM, Direction.UP).neverInserts());
        //Holders other than BasicSingleHolder may switch containers
        ISingleContainerHolder<IEnergyContainer> other = side -> BasicEnergyContainer.output(10_000, null);
        Assertions.assertFalse(new ProxyEnergyHandler(Direction.DOWN, other).neverInserts());
    }
}
