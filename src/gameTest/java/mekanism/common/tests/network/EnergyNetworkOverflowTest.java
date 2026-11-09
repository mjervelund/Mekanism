package mekanism.common.tests.network;

import mekanism.common.content.network.EnergyNetwork;
import mekanism.common.content.network.transmitter.UniversalCable;
import mekanism.common.registries.MekanismBlocks;
import mekanism.common.tests.helpers.TransmitterTestHelper;
import mekanism.common.tile.transmitter.TileEntityUniversalCable;
import net.minecraft.SharedConstants;
import net.neoforged.testframework.DynamicTest;
import net.neoforged.testframework.annotation.ForEachTest;
import net.neoforged.testframework.annotation.TestHolder;
import net.neoforged.testframework.gametest.GameTest;
import net.neoforged.testframework.gametest.StructureTemplateBuilder;

@ForEachTest(groups = "network.transmitter.energy_overflow")
public class EnergyNetworkOverflowTest {

    /// https://github.com/mekanism/Mekanism/issues/8604: absorbing a cable's buffer into a network that already stores close to Long.MAX_VALUE
    /// overflowed to a negative amount, which setEnergy rejects with an exception.
    @GameTest(setupTicks = SharedConstants.TICKS_PER_SECOND)
    @TestHolder(description = "Tests that absorbing a cable buffer into a nearly full energy network clamps instead of overflowing.")
    public static void absorbBufferClamps(final DynamicTest test) {
        test.registerGameTestTemplate(() -> StructureTemplateBuilder.withSize(1, 1, 1)
              .set(0, 0, 0, MekanismBlocks.BASIC_UNIVERSAL_CABLE.defaultState())
        );
        test.onGameTest(TransmitterTestHelper.class, helper -> helper.startSequence()
              .thenMap(() -> helper.getBlockEntity(0, 0, 0, TileEntityUniversalCable.class).getTransmitter())
              .thenExecute(cable -> {
                  EnergyNetwork network = cable.getTransmitterNetwork();
                  if (network == null) {
                      helper.fail("Cable has no network");
                      return;
                  }
                  long before = network.energyContainer.getAmountAsLong();
                  try {
                      network.energyContainer.setEnergy(Long.MAX_VALUE - 10, null);
                      cable.buffer.setEnergy(1_000, null);
                      network.absorbBuffer(cable, null);
                      helper.assertValueEqual(network.energyContainer.getAmountAsLong(), Long.MAX_VALUE, "network energy after absorbing the cable buffer");
                  } catch (IllegalArgumentException e) {
                      helper.fail("Absorbing the cable buffer overflowed: " + e.getMessage());
                  } finally {
                      network.energyContainer.setEnergy(before, null);
                  }
              })
              .thenSucceed()
        );
    }
}
