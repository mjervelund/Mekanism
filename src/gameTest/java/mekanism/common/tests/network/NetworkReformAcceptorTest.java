package mekanism.common.tests.network;

import mekanism.api.RelativeSide;
import mekanism.common.component.containers.type.ContainerType;
import mekanism.common.content.network.EnergyNetwork;
import mekanism.common.item.block.ItemBlockEnergyCube;
import mekanism.common.lib.transmitter.TransmissionType;
import mekanism.common.registries.MekanismBlocks;
import mekanism.common.tests.helpers.TransmitterTestHelper;
import mekanism.common.tile.TileEntityEnergyCube;
import mekanism.common.tile.component.config.ConfigInfo;
import mekanism.common.tile.component.config.DataType;
import mekanism.common.tile.transmitter.TileEntityUniversalCable;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.neoforged.testframework.DynamicTest;
import net.neoforged.testframework.annotation.ForEachTest;
import net.neoforged.testframework.annotation.TestHolder;
import net.neoforged.testframework.gametest.GameTest;
import net.neoforged.testframework.gametest.StructureTemplateBuilder;

/// A network that re-forms (one of its cables is removed, so all of its transmitters join a new network) keeps its acceptors without looking up every
/// transmitter's neighbours again, and still sees an acceptor change that happened in the same tick as the removal.
///
/// Layout (z = 0, along +x): 0 = filled creative cube (all output), 1..5 = basic universal cables, 6 = basic energy cube. A stub cable at (2, 0, 1) is broken
/// to make the network re-form; (4, 0, 1) is an energy cube placed with `/setblock`, which has no side config (no energy on any side) until the test gives
/// it one.
@ForEachTest(groups = "network.transmitter")
public class NetworkReformAcceptorTest {

    private static final int SOURCE = 0;
    private static final int SINK = 6;
    private static final BlockPos STUB = new BlockPos(2, 0, 1);
    private static final BlockPos SIDE_CUBE = new BlockPos(4, 0, 1);

    private static StructureTemplateBuilder template() {
        return StructureTemplateBuilder.withSize(7, 1, 2)
              .fill(1, 0, 0, 5, 0, 0, MekanismBlocks.BASIC_UNIVERSAL_CABLE.defaultState())
              .set(STUB.getX(), STUB.getY(), STUB.getZ(), MekanismBlocks.BASIC_UNIVERSAL_CABLE.defaultState())
              .set(SIDE_CUBE.getX(), SIDE_CUBE.getY(), SIDE_CUBE.getZ(), MekanismBlocks.BASIC_ENERGY_CUBE.defaultState());
    }

    private static void place(TransmitterTestHelper helper, int x, ItemStack stack) {
        //A player north of the line looks south at the target; cubes face the player (north), so their east/west sides take energy in
        Player player = helper.makeMockPlayerLookingAt(x, 0, 0, Direction.SOUTH);
        helper.useOn(new BlockPos(x, 0, 0), stack, player, Direction.NORTH);
        helper.assertFalse(helper.getBlockState(new BlockPos(x, 0, 0)).isAir(), "Nothing was placed at x=" + x);
    }

    private static void placeCubes(TransmitterTestHelper helper) {
        place(helper, SOURCE, ContainerType.ENERGY.getFilledVariant(ItemBlockEnergyCube.withCreativeSideConfig(ItemBlockEnergyCube.ALL_OUTPUT), null));
        place(helper, SINK, new ItemStack(MekanismBlocks.BASIC_ENERGY_CUBE));
    }

    private static TileEntityUniversalCable cable(TransmitterTestHelper helper, int x) {
        return helper.getBlockEntity(x, 0, 0, TileEntityUniversalCable.class);
    }

    private static EnergyNetwork network(TransmitterTestHelper helper, int x) {
        return cable(helper, x).getTransmitter().getTransmitterNetwork();
    }

    private static boolean connected(TransmitterTestHelper helper, int x, Direction side) {
        return (cable(helper, x).getTransmitter().getAllCurrentConnections() & (1 << side.ordinal())) != 0;
    }

    private static long sinkEnergy(TransmitterTestHelper helper) {
        return helper.getBlockEntity(SINK, 0, 0, TileEntityEnergyCube.class).energyContainer().getAmountAsLong();
    }

    private static void assertOneNetwork(TransmitterTestHelper helper, int transmitters, String phase) {
        EnergyNetwork network = network(helper, 1);
        helper.assertTrue(network != null, "Cable 1 has no network " + phase);
        for (int x = 2; x <= 5; x++) {
            helper.assertTrue(network(helper, x) == network, "Cable " + x + " is not in cable 1's network " + phase);
        }
        helper.assertValueEqual(network.transmittersSize(), transmitters, "network size " + phase);
    }

    @GameTest(setupTicks = SharedConstants.TICKS_PER_SECOND, timeoutTicks = 15 * SharedConstants.TICKS_PER_SECOND)
    @TestHolder(description = "A network that re-forms after one of its cables is broken keeps its acceptors and keeps carrying energy.")
    public static void reformKeepsAcceptors(final DynamicTest test) {
        test.registerGameTestTemplate(NetworkReformAcceptorTest::template);
        EnergyNetwork[] before = new EnergyNetwork[1];
        int[] acceptors = new int[1];
        long[] energy = new long[1];
        test.onGameTest(TransmitterTestHelper.class, helper -> helper.startSequence()
              .thenExecute(() -> placeCubes(helper))
              .thenWaitUntil(() -> helper.assertTrue(sinkEnergy(helper) > 0, "No energy reached the basic cube"))
              .thenExecute(() -> {
                  assertOneNetwork(helper, 6, "before");
                  before[0] = network(helper, 1);
                  acceptors[0] = before[0].getAcceptorCount();
                  helper.assertTrue(acceptors[0] >= 2, "Expected both cubes as acceptors, got " + acceptors[0]);
                  helper.destroyBlock(STUB);
              })
              .thenIdle(3)
              .thenExecute(() -> {
                  assertOneNetwork(helper, 5, "after the re-form");
                  helper.assertTrue(network(helper, 1) != before[0], "The network did not re-form (same network object)");
                  helper.assertValueEqual(network(helper, 1).getAcceptorCount(), acceptors[0], "acceptors after the re-form");
                  helper.assertTrue(connected(helper, 1, Direction.WEST) && connected(helper, 5, Direction.EAST), "Lost a cube connection in the re-form");
                  helper.assertFalse(connected(helper, 2, Direction.SOUTH), "Cable 2 still connected to the broken stub");
                  energy[0] = sinkEnergy(helper);
              })
              .thenWaitUntil(() -> helper.assertTrue(sinkEnergy(helper) > energy[0], "Energy stopped flowing after the re-form"))
              .thenSucceed()
        );
    }

    @GameTest(setupTicks = SharedConstants.TICKS_PER_SECOND, timeoutTicks = 15 * SharedConstants.TICKS_PER_SECOND)
    @TestHolder(description = "An acceptor that changes in the same tick as the network re-forms (capability invalidation, no block update) is seen by the new network.")
    public static void acceptorChangeDuringReform(final DynamicTest test) {
        test.registerGameTestTemplate(NetworkReformAcceptorTest::template);
        int[] acceptors = new int[1];
        test.onGameTest(TransmitterTestHelper.class, helper -> helper.startSequence()
              .thenExecute(() -> placeCubes(helper))
              .thenWaitUntil(() -> helper.assertTrue(sinkEnergy(helper) > 0, "No energy reached the basic cube"))
              .thenExecute(() -> {
                  helper.assertFalse(connected(helper, 4, Direction.SOUTH), "The /setblock cube should expose no energy side yet");
                  acceptors[0] = network(helper, 1).getAcceptorCount();
                  //Same tick: break the stub (the network re-forms at the end of the tick) and open the side cube's north side for energy. The side change
                  // only invalidates the cube's capability, which reaches cable 4 as an acceptor change on the network that is about to be invalidated.
                  helper.destroyBlock(STUB);
                  TileEntityEnergyCube cube = helper.getBlockEntity(SIDE_CUBE, TileEntityEnergyCube.class);
                  RelativeSide side = RelativeSide.fromDirections(cube.getDirection(), Direction.NORTH);
                  ConfigInfo config = cube.getConfig().getConfig(TransmissionType.ENERGY);
                  helper.assertTrue(config != null && config.setDataType(DataType.INPUT, side), "Could not open the side cube's north side");
                  cube.getConfig().sideChanged(TransmissionType.ENERGY, side);
              })
              .thenIdle(3)
              .thenExecute(() -> {
                  assertOneNetwork(helper, 5, "after the re-form");
                  helper.assertTrue(connected(helper, 4, Direction.SOUTH), "Cable 4 did not see the side cube's new energy side");
                  helper.assertValueEqual(network(helper, 1).getAcceptorCount(), acceptors[0] + 1, "acceptors after the re-form");
              })
              .thenWaitUntil(() -> helper.assertTrue(helper.getBlockEntity(SIDE_CUBE, TileEntityEnergyCube.class).energyContainer().getAmountAsLong() > 0,
                    "No energy reached the side cube"))
              .thenSucceed()
        );
    }
}
