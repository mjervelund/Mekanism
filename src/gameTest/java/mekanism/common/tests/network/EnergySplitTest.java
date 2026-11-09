package mekanism.common.tests.network;

import mekanism.common.content.network.EnergyNetwork;
import mekanism.common.registries.MekanismBlocks;
import mekanism.common.tests.helpers.TransmitterTestHelper;
import mekanism.common.tile.transmitter.TileEntityUniversalCable;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.neoforged.testframework.DynamicTest;
import net.neoforged.testframework.annotation.ForEachTest;
import net.neoforged.testframework.annotation.TestHolder;
import net.neoforged.testframework.gametest.GameTest;
import net.neoforged.testframework.gametest.StructureTemplateBuilder;

/// Splitting an energy network (breaking a cable in the middle of a line) keeps the network's energy: the two new networks hold all of it. Each cable takes
/// its save share out of the old network (see `DynamicNetwork#invalidate`): an equal share per cable, capped at the cable's capacity.
///
/// Layout along +x: 0..9 basic universal cables, 10 = the cable that is broken, 11..20 advanced universal cables (larger capacity, so the shares differ).
@ForEachTest(groups = "network.transmitter")
public class EnergySplitTest {

    private static final int BREAK = 10;
    private static final int LENGTH = 21;

    private static EnergyNetwork network(TransmitterTestHelper helper, int x) {
        return helper.getBlockEntity(x, 0, 0, TileEntityUniversalCable.class).getTransmitter().getTransmitterNetwork();
    }

    private static long capacity(TransmitterTestHelper helper, int fromX, int toX) {
        long sum = 0;
        for (int x = fromX; x <= toX; x++) {
            sum += helper.getBlockEntity(x, 0, 0, TileEntityUniversalCable.class).getTransmitter().getCapacity();
        }
        return sum;
    }

    @GameTest(setupTicks = SharedConstants.TICKS_PER_SECOND, timeoutTicks = 10 * SharedConstants.TICKS_PER_SECOND)
    @TestHolder(description = "Breaking a cable in the middle of a charged line splits the network's energy between the two halves (equal shares per cable, capped"
                              + " at each cable's capacity), losing none.")
    public static void splitKeepsEnergy(final DynamicTest test) {
        test.registerGameTestTemplate(() -> StructureTemplateBuilder.withSize(LENGTH, 1, 1)
              .fill(0, 0, 0, BREAK, 0, 0, MekanismBlocks.BASIC_UNIVERSAL_CABLE.defaultState())
              .fill(BREAK + 1, 0, 0, LENGTH - 1, 0, 0, MekanismBlocks.ADVANCED_UNIVERSAL_CABLE.defaultState())
        );
        long[] expected = new long[3];
        test.onGameTest(TransmitterTestHelper.class, helper -> helper.startSequence()
              .thenExecute(() -> {
                  EnergyNetwork network = network(helper, 0);
                  helper.assertTrue(network != null && network == network(helper, LENGTH - 1), "The line is not one network");
                  helper.assertValueEqual(network.transmittersSize(), LENGTH, "network size");
                  long left = capacity(helper, 0, BREAK - 1);
                  long right = capacity(helper, BREAK + 1, LENGTH - 1);
                  //An amount that does not divide evenly, below what the cables left after the break can hold
                  long energy = (left + right) * 3 / 4 + 7;
                  network.energyContainer.setEnergy(energy, null);
                  expected[0] = energy;
                  expected[1] = left;
                  expected[2] = right;
                  helper.destroyBlock(new BlockPos(BREAK, 0, 0));
              })
              .thenIdle(3)
              .thenExecute(() -> {
                  EnergyNetwork left = network(helper, 0);
                  EnergyNetwork right = network(helper, LENGTH - 1);
                  helper.assertTrue(left != null && right != null && left != right, "The line did not split into two networks");
                  long leftEnergy = left.energyContainer.getAmountAsLong();
                  long rightEnergy = right.energyContainer.getAmountAsLong();
                  helper.assertValueEqual(leftEnergy + rightEnergy, expected[0], "energy of both halves");
                  //Equal shares per cable, capped at capacity: the 10 basic cables fill up first, the rest goes to the 10 advanced ones
                  long basic = expected[1] / BREAK;
                  long leftExpected = Math.min(expected[1], Math.min(expected[0] / (LENGTH - 1), basic) * BREAK);
                  helper.assertTrue(Math.abs(leftEnergy - leftExpected) <= LENGTH, "Left half holds " + leftEnergy + ", expected about " + leftExpected);
                  helper.assertTrue(left.energyContainer.getAmountAsLong() <= expected[1] && rightEnergy <= expected[2], "A half holds more than its capacity");
              })
              .thenSucceed()
        );
    }
}
