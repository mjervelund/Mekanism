package mekanism.common.tests.network;

import mekanism.common.capabilities.energy.BasicEnergyContainer;
import mekanism.common.content.network.EnergyNetwork;
import mekanism.common.registries.MekanismBlocks;
import mekanism.common.tests.helpers.MekGameTestHelper;
import mekanism.common.tile.transmitter.TileEntityUniversalCable;
import mekanism.generators.common.config.MekanismGeneratorsConfig;
import mekanism.generators.common.registries.GeneratorsBlocks;
import mekanism.generators.common.tile.TileEntitySolarGenerator;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.testframework.annotation.ForEachTest;
import net.neoforged.testframework.annotation.TestHolder;
import net.neoforged.testframework.gametest.EmptyTemplate;
import net.neoforged.testframework.gametest.GameTest;

/// A solar generator on a universal cable adds its energy straight into the cable network's buffer
/// ([TileEntitySolarGenerator#directNetworkInsert]). Checks that this moves exactly what the normal emit through the cable's capability moves: two single-cable
/// networks, one panel each, one panel forced onto the normal emit; both panels get the same energy, and after their emit ticks both networks and both panels
/// hold the same amounts, with room for everything and with room for only a few units.
@ForEachTest(groups = "network.solar_insert")
public class SolarNetworkInsertTest {

    private static final int DIRECT_X = 0;
    private static final int NORMAL_X = 2;

    private static TileEntitySolarGenerator panel(MekGameTestHelper helper, int x) {
        return helper.getBlockEntity(x, 1, 0, TileEntitySolarGenerator.class);
    }

    private static EnergyNetwork network(MekGameTestHelper helper, int x) {
        return helper.getBlockEntity(x, 0, 0, TileEntityUniversalCable.class).getTransmitter().getTransmitterNetworkNN();
    }

    /// Fills both panels and leaves `networkSpace` room in both networks (empties them when negative), so the next emits start from the same state.
    private static void prepare(MekGameTestHelper helper, long networkSpace) {
        for (int x : new int[]{DIRECT_X, NORMAL_X}) {
            BasicEnergyContainer container = panel(helper, x).energyContainer();
            container.setEnergy(container.getCapacityAsLong(), null);
            EnergyNetwork network = network(helper, x);
            network.energyContainer.setEnergy(networkSpace < 0 ? 0 : network.energyContainer.getCapacityAsLong() - networkSpace, null);
        }
    }

    private static void compare(MekGameTestHelper helper, String context) {
        EnergyNetwork direct = network(helper, DIRECT_X), normal = network(helper, NORMAL_X);
        if (direct.energyContainer.isEmpty()) {
            helper.fail(context + ": the direct panel sent nothing", new BlockPos(DIRECT_X, 0, 0));
        }
        helper.assertValueEqual(direct.energyContainer.getAmountAsLong(), normal.energyContainer.getAmountAsLong(), context + ": energy in the network");
        helper.assertValueEqual(panel(helper, DIRECT_X).energyContainer().getAmountAsLong(), panel(helper, NORMAL_X).energyContainer().getAmountAsLong(),
              context + ": energy left in the panel");
    }

    @GameTest(timeoutTicks = 15 * SharedConstants.TICKS_PER_SECOND)
    @EmptyTemplate
    @TestHolder(description = "A solar generator adding straight into its cable network moves the same energy as the normal emit.")
    public static void testSameAmounts(final MekGameTestHelper helper) {
        //Two emit intervals: every panel reaches its emit tick (its phase within the interval) at least once
        int wait = 2 * Math.max(1, MekanismGeneratorsConfig.generators.solarEmitInterval.get()) + 2;
        helper.startSequence()
              .thenExecute(() -> {
                  for (int x : new int[]{DIRECT_X, NORMAL_X}) {
                      helper.setBlock(x, 0, 0, MekanismBlocks.ULTIMATE_UNIVERSAL_CABLE.defaultState());
                      helper.setBlock(x, 1, 0, GeneratorsBlocks.SOLAR_GENERATOR.defaultState());
                      //Covered: the panels emit on different ticks (their phase), so any production after an emit would make the amounts differ
                      helper.setBlock(x, 2, 0, Blocks.STONE);
                  }
              })
              .thenExecuteAfter(SharedConstants.TICKS_PER_SECOND, () -> {
                  panel(helper, NORMAL_X).directNetworkInsert = false;
                  prepare(helper, -1);
              })
              .thenExecuteAfter(wait, () -> compare(helper, "room for everything"))
              .thenExecute(() -> prepare(helper, 7))
              .thenExecuteAfter(wait, () -> compare(helper, "room for 7"))
              .thenSucceed();
    }
}
