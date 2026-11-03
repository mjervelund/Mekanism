package mekanism.common.tests.network;

import mekanism.common.component.containers.type.ContainerType;
import mekanism.common.item.block.ItemBlockEnergyCube;
import mekanism.common.registries.MekanismBlocks;
import mekanism.common.tests.helpers.TransmitterTestHelper;
import mekanism.common.tile.TileEntityEnergyCube;
import mekanism.common.tile.transmitter.TileEntityUniversalCable;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.testframework.DynamicTest;
import net.neoforged.testframework.annotation.ForEachTest;
import net.neoforged.testframework.annotation.TestHolder;
import net.neoforged.testframework.gametest.GameTest;
import net.neoforged.testframework.gametest.StructureTemplateBuilder;

/// Hand placement of cables and energy cubes next to idle (sleeping, see `perf/transmitter-idle-untick`) cables: connections are refreshed and energy flows.
/// Layout along +x (EAST): 0 = filled creative cube (all output), 1..3 = basic universal cables, 4 = basic energy cube.
@ForEachTest(groups = "network.transmitter")
public class CablePlacementWakeTest {

    private static final int SOURCE = 0;
    private static final int SINK = 4;

    private static void place(TransmitterTestHelper helper, int x, ItemStack stack) {
        //A player north of the line looks south at the (air, replaceable) target, so the block is placed in it. Cubes face the player (north),
        // so their east/west sides are the default energy input sides
        Player player = helper.makeMockPlayerLookingAt(x, 0, 0, Direction.SOUTH);
        helper.useOn(new BlockPos(x, 0, 0), stack, player, Direction.NORTH);
        helper.assertFalse(helper.getBlockState(new BlockPos(x, 0, 0)).isAir(), "Nothing was placed at x=" + x);
    }

    private static ItemStack creativeCube() {
        return ContainerType.ENERGY.getFilledVariant(ItemBlockEnergyCube.withCreativeSideConfig(ItemBlockEnergyCube.ALL_OUTPUT), null);
    }

    private static TileEntityUniversalCable cable(TransmitterTestHelper helper, int x) {
        return helper.getBlockEntity(x, 0, 0, TileEntityUniversalCable.class);
    }

    private static boolean connected(TransmitterTestHelper helper, int x, Direction side) {
        return (cable(helper, x).getTransmitter().getAllCurrentConnections() & (1 << side.ordinal())) != 0;
    }

    private static long sinkEnergy(TransmitterTestHelper helper) {
        return helper.getBlockEntity(SINK, 0, 0, TileEntityEnergyCube.class).energyContainer().getAmountAsLong();
    }

    private static void assertConnected(TransmitterTestHelper helper) {
        helper.assertTrue(connected(helper, 1, Direction.WEST), "Cable 1 not connected to the creative cube");
        helper.assertTrue(connected(helper, 1, Direction.EAST), "Cable 1 not connected to cable 2");
        helper.assertTrue(connected(helper, 2, Direction.WEST) && connected(helper, 2, Direction.EAST), "Cable 2 not connected on both sides");
        helper.assertTrue(connected(helper, 3, Direction.EAST), "Cable 3 not connected to the basic cube");
    }

    @GameTest(setupTicks = SharedConstants.TICKS_PER_SECOND, timeoutTicks = 10 * SharedConstants.TICKS_PER_SECOND)
    @TestHolder(description = "(1) Cables placed by hand next to existing energy cubes connect, sleep while idle and carry energy.")
    public static void cablesPlacedNextToCubes(final DynamicTest test) {
        test.registerGameTestTemplate(() -> StructureTemplateBuilder.withSize(5, 1, 1));
        test.onGameTest(TransmitterTestHelper.class, helper -> helper.startSequence()
              .thenExecute(() -> {
                  place(helper, SOURCE, creativeCube());
                  place(helper, SINK, new ItemStack(MekanismBlocks.BASIC_ENERGY_CUBE));
              })
              .thenIdle(5)
              .thenExecute(() -> {
                  for (int x = 1; x <= 3; x++) {
                      place(helper, x, new ItemStack(MekanismBlocks.BASIC_UNIVERSAL_CABLE));
                  }
              })
              .thenIdle(5)
              .thenExecute(() -> {
                  assertConnected(helper);
                  helper.assertTrue(cable(helper, 2).isTickerRemoved(), "Idle cable should be asleep (test would not cover the sleeping path)");
              })
              .thenWaitUntil(() -> helper.assertTrue(sinkEnergy(helper) > 0, "No energy reached the basic cube"))
              .thenSucceed()
        );
    }

    @GameTest(setupTicks = SharedConstants.TICKS_PER_SECOND, timeoutTicks = 10 * SharedConstants.TICKS_PER_SECOND)
    @TestHolder(description = "(2) Energy cubes placed by hand next to sleeping cables refresh the cables' connections and receive/provide energy.")
    public static void cubesPlacedNextToSleepingCables(final DynamicTest test) {
        test.registerGameTestTemplate(() -> StructureTemplateBuilder.withSize(5, 1, 1)
              .fill(1, 0, 0, 3, 0, 0, MekanismBlocks.BASIC_UNIVERSAL_CABLE.defaultState())
        );
        test.onGameTest(TransmitterTestHelper.class, helper -> helper.startSequence()
              .thenIdle(5)
              .thenExecute(() -> {
                  for (int x = 1; x <= 3; x++) {
                      helper.assertTrue(cable(helper, x).isTickerRemoved(), "Cable " + x + " should be asleep before the cubes are placed");
                  }
                  place(helper, SOURCE, creativeCube());
                  place(helper, SINK, new ItemStack(MekanismBlocks.BASIC_ENERGY_CUBE));
                  //Placement must refresh synchronously (neighbor update), not wait for the cable's own tick
                  helper.assertTrue(connected(helper, 1, Direction.WEST), "Sleeping cable 1 did not see the new creative cube");
                  helper.assertTrue(connected(helper, 3, Direction.EAST), "Sleeping cable 3 did not see the new basic cube");
              })
              .thenIdle(5)
              .thenExecute(() -> assertConnected(helper))
              .thenWaitUntil(() -> helper.assertTrue(sinkEnergy(helper) > 0, "No energy reached the basic cube"))
              .thenSucceed()
        );
    }

    @GameTest(setupTicks = SharedConstants.TICKS_PER_SECOND, timeoutTicks = 15 * SharedConstants.TICKS_PER_SECOND)
    @TestHolder(description = "(3) Breaking and re-placing a cable in a running line reconnects and energy flows again.")
    public static void breakAndReplaceCable(final DynamicTest test) {
        test.registerGameTestTemplate(() -> StructureTemplateBuilder.withSize(5, 1, 1)
              .fill(1, 0, 0, 3, 0, 0, MekanismBlocks.BASIC_UNIVERSAL_CABLE.defaultState())
        );
        long[] before = new long[1];
        test.onGameTest(TransmitterTestHelper.class, helper -> helper.startSequence()
              .thenExecute(() -> {
                  place(helper, SOURCE, creativeCube());
                  place(helper, SINK, new ItemStack(MekanismBlocks.BASIC_ENERGY_CUBE));
              })
              .thenWaitUntil(() -> helper.assertTrue(sinkEnergy(helper) > 0, "No energy reached the basic cube"))
              .thenExecute(() -> helper.destroyBlock(new BlockPos(2, 0, 0)))
              .thenIdle(5)
              .thenExecute(() -> {
                  helper.assertTrue(helper.getBlockState(new BlockPos(2, 0, 0)).is(Blocks.AIR), "Cable 2 was not broken");
                  helper.assertFalse(connected(helper, 1, Direction.EAST), "Cable 1 still connected to the broken cable");
                  before[0] = sinkEnergy(helper);
              })
              .thenIdle(10)
              .thenExecute(() -> {
                  helper.assertValueEqual(sinkEnergy(helper), before[0], "Energy still flowing through a broken line");
                  helper.assertTrue(cable(helper, 1).isTickerRemoved() && cable(helper, 3).isTickerRemoved(), "Remaining cables should be asleep before the re-place");
                  place(helper, 2, new ItemStack(MekanismBlocks.BASIC_UNIVERSAL_CABLE));
              })
              .thenIdle(5)
              .thenExecute(() -> assertConnected(helper))
              .thenWaitUntil(() -> helper.assertTrue(sinkEnergy(helper) > before[0], "Energy did not flow again after re-placing the cable"))
              .thenSucceed()
        );
    }

    @GameTest(setupTicks = SharedConstants.TICKS_PER_SECOND)
    @TestHolder(description = "Diagnostic: a /setblock energy cube has no side config (all sides NONE), so cables do not connect to it.")
    public static void setblockCubeHasNoSides(final DynamicTest test) {
        test.registerGameTestTemplate(() -> StructureTemplateBuilder.withSize(2, 1, 1)
              .set(0, 0, 0, MekanismBlocks.CREATIVE_ENERGY_CUBE.defaultState())
        );
        test.onGameTest(TransmitterTestHelper.class, helper -> helper.startSequence()
              .thenExecute(() -> place(helper, 1, new ItemStack(MekanismBlocks.BASIC_UNIVERSAL_CABLE)))
              .thenIdle(5)
              .thenExecute(() -> {
                  helper.assertFalse(connected(helper, 1, Direction.WEST), "Expected no connection to a cube without side config");
                  helper.assertValueEqual(helper.getBlockEntity(0, 0, 0, TileEntityEnergyCube.class).energyContainer().getAmountAsLong(), 0L, "setblock creative cube energy");
              })
              .thenSucceed()
        );
    }
}
