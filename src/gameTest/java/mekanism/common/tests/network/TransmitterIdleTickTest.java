package mekanism.common.tests.network;

import mekanism.common.lib.transmitter.ConnectionType;
import mekanism.common.registries.MekanismBlocks;
import mekanism.common.tests.helpers.TransmitterTestHelper;
import mekanism.common.tile.transmitter.TileEntityLogisticalTransporter;
import mekanism.common.tile.transmitter.TileEntityUniversalCable;
import net.minecraft.SharedConstants;
import net.minecraft.core.Direction;
import net.neoforged.testframework.DynamicTest;
import net.neoforged.testframework.annotation.ForEachTest;
import net.neoforged.testframework.annotation.TestHolder;
import net.neoforged.testframework.gametest.GameTest;
import net.neoforged.testframework.gametest.StructureTemplateBuilder;

@ForEachTest(groups = "network.transmitter")
public class TransmitterIdleTickTest {

    @GameTest(setupTicks = SharedConstants.TICKS_PER_SECOND)
    @TestHolder(description = "Idle cables leave the block entity ticker list, come back when a side is set to pull and leave again when it is not; transporters keep ticking.")
    public static void idleCableSleepsAndWakes(final DynamicTest test) {
        test.registerGameTestTemplate(() -> StructureTemplateBuilder.withSize(3, 1, 1)
              .set(0, 0, 0, MekanismBlocks.BASIC_UNIVERSAL_CABLE.defaultState())
              .set(1, 0, 0, MekanismBlocks.BASIC_UNIVERSAL_CABLE.defaultState())
              .set(2, 0, 0, MekanismBlocks.BASIC_LOGISTICAL_TRANSPORTER.defaultState())
        );
        test.onGameTest(TransmitterTestHelper.class, helper -> helper.startSequence()
              .thenIdle(5)
              .thenExecute(() -> {
                  helper.assertTrue(helper.getBlockEntity(0, 0, 0, TileEntityUniversalCable.class).isTickerRemoved(), "Idle cable should not be ticking");
                  helper.assertFalse(helper.getBlockEntity(2, 0, 0, TileEntityLogisticalTransporter.class).isTickerRemoved(), "Transporter should keep ticking");
                  //Normal -> push -> pull on the open side of the first cable
                  helper.getBlockEntity(0, 0, 0, TileEntityUniversalCable.class).getTransmitter().setConnectionTypeRaw(Direction.UP, ConnectionType.PULL);
                  helper.assertFalse(helper.getBlockEntity(0, 0, 0, TileEntityUniversalCable.class).isTickerRemoved(), "Pull side should wake the cable");
              })
              .thenIdle(5)
              .thenExecute(() -> {
                  TileEntityUniversalCable cable = helper.getBlockEntity(0, 0, 0, TileEntityUniversalCable.class);
                  helper.assertFalse(cable.isTickerRemoved(), "Cable with a pull side should keep ticking");
                  helper.assertTrue(helper.getBlockEntity(1, 0, 0, TileEntityUniversalCable.class).isTickerRemoved(), "Neighbour without pull stays asleep");
                  cable.getTransmitter().setConnectionTypeRaw(Direction.UP, ConnectionType.NORMAL);
                  //A forced connection refresh must also wake a sleeping cable and be processed
                  helper.getBlockEntity(1, 0, 0, TileEntityUniversalCable.class).setForceUpdate();
                  helper.assertFalse(helper.getBlockEntity(1, 0, 0, TileEntityUniversalCable.class).isTickerRemoved(), "Force update should wake the cable");
              })
              .thenIdle(5)
              .thenExecute(() -> {
                  helper.assertTrue(helper.getBlockEntity(0, 0, 0, TileEntityUniversalCable.class).isTickerRemoved(), "Cable without pull should sleep again");
                  helper.assertTrue(helper.getBlockEntity(1, 0, 0, TileEntityUniversalCable.class).isTickerRemoved(), "Cable should sleep after its refresh");
              })
              .thenSucceed()
        );
    }
}
