package mekanism.common.tests.network;

import java.util.function.Consumer;
import java.util.function.Supplier;
import mekanism.common.Mekanism;
import mekanism.api.RelativeSide;
import mekanism.common.content.network.EnergyNetwork;
import mekanism.common.content.network.transmitter.UniversalCable;
import mekanism.common.registries.MekanismBlocks;
import mekanism.common.tests.MekanismTests;
import mekanism.common.lib.transmitter.TransmissionType;
import mekanism.common.tests.helpers.MekGameTestHelper;
import mekanism.common.tile.TileEntityEnergyCube;
import mekanism.common.tile.component.config.ConfigInfo;
import mekanism.common.tile.component.config.DataType;
import mekanism.common.tile.transmitter.TileEntityUniversalCable;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.testframework.DynamicTest;
import net.neoforged.testframework.annotation.ForEachTest;
import net.neoforged.testframework.annotation.RegisterStructureTemplate;
import net.neoforged.testframework.annotation.TestHolder;
import net.neoforged.testframework.gametest.GameTest;
import net.neoforged.testframework.gametest.StructureTemplateBuilder;
import org.jspecify.annotations.Nullable;

/// Removing transmitters from an energy network (breaking them, or their chunk becoming inaccessible) splits the network where it falls apart and keeps it
/// where it does not, without losing or creating energy, and the parts join again when the transmitters come back.
@ForEachTest(groups = "network.transmitter")
public class NetworkSplitTest {

    private static final int LINE = 3 * 16;
    private static final String LINE_CABLE = MekanismTests.MODID + ":split_line_cable";
    @RegisterStructureTemplate(LINE_CABLE)
    public static final Supplier<StructureTemplate> LINE_CABLE_TEMPLATE = StructureTemplateBuilder.lazy(LINE, 1, 1,
          builder -> builder.fill(0, 0, 0, LINE - 1, 0, 0, MekanismBlocks.ADVANCED_UNIVERSAL_CABLE.defaultState())
    );

    @Nullable
    private static EnergyNetwork network(MekGameTestHelper helper, int x, int y, int z) {
        return helper.getBlockEntity(new BlockPos(x, y, z), TileEntityUniversalCable.class).getTransmitter().getTransmitterNetwork();
    }

    private static UniversalCable cable(MekGameTestHelper helper, int x, int y, int z) {
        return helper.getBlockEntity(new BlockPos(x, y, z), TileEntityUniversalCable.class).getTransmitter();
    }

    private static long energy(@Nullable EnergyNetwork network) {
        return network == null ? 0 : network.energyContainer.getAmountAsLong();
    }

    @GameTest(setupTicks = SharedConstants.TICKS_PER_SECOND, timeoutTicks = 10 * SharedConstants.TICKS_PER_SECOND)
    @TestHolder(description = "Breaking a cable in the middle of a charged line splits the network's energy between the two halves (equal shares per cable, capped"
                              + " at each cable's capacity), losing none.")
    public static void splitKeepsEnergy(final DynamicTest test) {
        int brk = 10;
        int length = 21;
        test.registerGameTestTemplate(() -> StructureTemplateBuilder.withSize(length, 1, 1)
              .fill(0, 0, 0, brk, 0, 0, MekanismBlocks.BASIC_UNIVERSAL_CABLE.defaultState())
              .fill(brk + 1, 0, 0, length - 1, 0, 0, MekanismBlocks.ADVANCED_UNIVERSAL_CABLE.defaultState())
        );
        long[] expected = new long[3];
        test.onGameTest(MekGameTestHelper.class, helper -> helper.startSequence()
              .thenExecute(() -> {
                  EnergyNetwork network = network(helper, 0, 0, 0);
                  helper.assertTrue(network != null && network == network(helper, length - 1, 0, 0), "The line is not one network");
                  helper.assertValueEqual(network.transmittersSize(), length, "network size");
                  long left = 0;
                  long right = 0;
                  for (int x = 0; x < brk; x++) {
                      left += cable(helper, x, 0, 0).getCapacity();
                  }
                  for (int x = brk + 1; x < length; x++) {
                      right += cable(helper, x, 0, 0).getCapacity();
                  }
                  //An amount that does not divide evenly, below what the cables left after the break can hold
                  long energy = (left + right) * 3 / 4 + 7;
                  network.energyContainer.setEnergy(energy, null);
                  expected[0] = energy;
                  expected[1] = left;
                  expected[2] = right;
                  helper.destroyBlock(new BlockPos(brk, 0, 0));
              })
              .thenIdle(3)
              .thenExecute(() -> {
                  EnergyNetwork left = network(helper, 0, 0, 0);
                  EnergyNetwork right = network(helper, length - 1, 0, 0);
                  helper.assertTrue(left != null && right != null && left != right, "The line did not split into two networks");
                  helper.assertValueEqual(left.transmittersSize(), brk, "left size");
                  helper.assertValueEqual(right.transmittersSize(), length - brk - 1, "right size");
                  long leftEnergy = energy(left);
                  long rightEnergy = energy(right);
                  helper.assertValueEqual(leftEnergy + rightEnergy, expected[0], "energy of both halves");
                  //Equal shares per cable, capped at capacity: the 10 basic cables fill up first, the rest goes to the 10 advanced ones
                  long basic = expected[1] / brk;
                  long leftExpected = Math.min(expected[1], Math.min(expected[0] / (length - 1), basic) * brk);
                  helper.assertTrue(Math.abs(leftEnergy - leftExpected) <= length, "Left half holds " + leftEnergy + ", expected about " + leftExpected);
                  helper.assertTrue(leftEnergy <= expected[1] && rightEnergy <= expected[2], "A half holds more than its capacity");
              })
              .thenSucceed()
        );
    }

    /// Places an energy cube; one placed like this has no side config (no energy on any side), so open the side towards the cable for energy input.
    private static void placeCube(MekGameTestHelper helper, BlockPos pos, Direction towardsCable) {
        helper.setBlock(pos, MekanismBlocks.BASIC_ENERGY_CUBE.defaultState());
        TileEntityEnergyCube cube = helper.getBlockEntity(pos, TileEntityEnergyCube.class);
        RelativeSide side = RelativeSide.fromDirections(cube.getDirection(), towardsCable);
        ConfigInfo config = cube.getConfig().getConfig(TransmissionType.ENERGY);
        helper.assertTrue(config != null && config.setDataType(DataType.INPUT, side), "Could not open the cube's side towards the cable");
        cube.getConfig().sideChanged(TransmissionType.ENERGY, side);
    }

    private static long cubeEnergy(MekGameTestHelper helper) {
        return helper.getBlockEntity(new BlockPos(3, 0, 1), TileEntityEnergyCube.class).energyContainer().getAmountAsLong()
               + helper.getBlockEntity(new BlockPos(3, 0, 5), TileEntityEnergyCube.class).energyContainer().getAmountAsLong();
    }

    @GameTest(setupTicks = SharedConstants.TICKS_PER_SECOND, timeoutTicks = 10 * SharedConstants.TICKS_PER_SECOND)
    @TestHolder(description = "Breaking a cable of a loop keeps the network (same network, one transmitter less, same energy, the broken cable's acceptor"
                              + " gone) and putting it back adds it to that network again.")
    public static void loopKeepsNetwork(final DynamicTest test) {
        //A 7x7 ring of cables, an energy cube inside it next to the cable that is broken and one next to a cable on the other side
        test.registerGameTestTemplate(() -> StructureTemplateBuilder.withSize(7, 1, 7)
              .fill(0, 0, 0, 6, 0, 0, MekanismBlocks.BASIC_UNIVERSAL_CABLE.defaultState())
              .fill(0, 0, 6, 6, 0, 6, MekanismBlocks.BASIC_UNIVERSAL_CABLE.defaultState())
              .fill(0, 0, 1, 0, 0, 5, MekanismBlocks.BASIC_UNIVERSAL_CABLE.defaultState())
              .fill(6, 0, 1, 6, 0, 5, MekanismBlocks.BASIC_UNIVERSAL_CABLE.defaultState())
        );
        BlockPos broken = new BlockPos(3, 0, 0);
        Object[] before = new Object[3];
        test.onGameTest(MekGameTestHelper.class, helper -> helper.startSequence()
              //Placed after the template so that the cables get neighbour updates for them
              .thenExecute(() -> {
                  placeCube(helper, new BlockPos(3, 0, 1), Direction.NORTH);
                  placeCube(helper, new BlockPos(3, 0, 5), Direction.SOUTH);
              })
              .thenIdle(2)
              .thenExecute(() -> {
                  EnergyNetwork network = network(helper, 0, 0, 0);
                  helper.assertTrue(network != null && network == network(helper, 6, 0, 6), "The ring is not one network");
                  helper.assertValueEqual(network.transmittersSize(), 24, "ring size");
                  helper.assertValueEqual(network.getAcceptorCount(), 2, "acceptors (the two energy cubes)");
                  //Less than the cables still there can hold, so none is clamped away
                  long energy = network.getCapacity() / 2 + 3;
                  network.energyContainer.setEnergy(energy, null);
                  before[0] = network;
                  before[1] = energy + cubeEnergy(helper);
                  helper.setBlock(broken, Blocks.AIR.defaultBlockState());
              })
              .thenIdle(2)
              .thenExecute(() -> {
                  EnergyNetwork network = network(helper, 0, 0, 0);
                  for (BlockPos pos : BlockPos.betweenClosed(0, 0, 0, 6, 0, 6)) {
                      if (!pos.equals(broken) && helper.getLevel().getBlockEntity(helper.absolutePos(pos)) instanceof TileEntityUniversalCable tile) {
                          helper.assertTrue(tile.getTransmitter().getTransmitterNetwork() == network, "Cable at " + pos + " is not in the ring's network");
                      }
                  }
                  helper.assertTrue(network != null, "The ring has no network");
                  helper.assertValueEqual(network.transmittersSize(), 23, "ring size after the break");
                  helper.assertValueEqual(network.getAcceptorCount(), 1, "acceptors after the break");
                  helper.assertValueEqual(energy(network) + cubeEnergy(helper), (long) before[1], "energy in the ring and the cubes after the break");
                  helper.assertTrue(network == before[0], "Breaking one cable of a loop re-formed the network");
                  helper.assertValueEqual(network.getCapacity(), 23 * cable(helper, 0, 0, 0).getCapacity(), "capacity after the break");
                  helper.setBlock(broken, MekanismBlocks.BASIC_UNIVERSAL_CABLE.defaultState());
              })
              .thenIdle(2)
              .thenExecute(() -> {
                  EnergyNetwork network = network(helper, 3, 0, 0);
                  helper.assertTrue(network == before[0], "The replaced cable did not join the ring's network");
                  helper.assertValueEqual(network.transmittersSize(), 24, "ring size after replacing");
                  helper.assertValueEqual(network.getAcceptorCount(), 2, "acceptors after replacing");
                  helper.assertValueEqual(energy(network) + cubeEnergy(helper), (long) before[1], "energy in the ring and the cubes after replacing");
              })
              .thenSucceed()
        );
    }

    @GameTest(template = LINE_CABLE, setupTicks = SharedConstants.TICKS_PER_SECOND, timeoutTicks = 10 * SharedConstants.TICKS_PER_SECOND, batch = "split-chunk")
    @TestHolder(description = "When the middle chunk of a charged cable line becomes inaccessible, the line splits into two networks and the cables of that chunk"
                              + " take their shares; when it comes back, it is one network again with all of the energy.")
    public static void inaccessibleChunkKeepsEnergy(final MekGameTestHelper helper) {
        long[] state = new long[1];
        helper.startSequence()
              .thenExecute(() -> {
                  EnergyNetwork network = network(helper, 0, 0, 0);
                  helper.assertTrue(network != null && network == network(helper, LINE - 1, 0, 0), "The line is not one network");
                  long energy = network.getCapacity() / 3 + 11;
                  network.energyContainer.setEnergy(energy, null);
                  state[0] = energy;
              })
              //What TransmitterNetworkRegistry does for the transmitters of a chunk that becomes inaccessible (x 16..31 stand for the middle chunk)
              .thenExecute(() -> setAccessible(helper, 16, 31, 0, 0, 0, 0, false))
              .thenWaitUntil(() -> {
                  EnergyNetwork left = network(helper, 0, 0, 0);
                  EnergyNetwork right = network(helper, LINE - 1, 0, 0);
                  helper.assertTrue(left != null && right != null && left != right, "The line did not split into two networks");
              })
              .thenIdle(2)
              .thenExecute(() -> {
                  EnergyNetwork left = network(helper, 0, 0, 0);
                  EnergyNetwork right = network(helper, LINE - 1, 0, 0);
                  long inMiddle = 0;
                  int middleCables = 0;
                  for (int x = 0; x < LINE; x++) {
                      UniversalCable cable = cable(helper, x, 0, 0);
                      if (x >= 16 && x < 32) {
                          helper.assertTrue(!cable.hasTransmitterNetwork(), "Cable " + x + " of the inaccessible chunk is still in a network");
                          inMiddle += cable.getShare();
                          middleCables++;
                      } else {
                          helper.assertTrue(cable.getTransmitterNetwork() == left || cable.getTransmitterNetwork() == right, "Cable " + x + " is in neither half");
                      }
                  }
                  helper.assertValueEqual(middleCables, 16, "cables in the inaccessible chunk");
                  helper.assertValueEqual(left.transmittersSize() + right.transmittersSize(), LINE - 16, "cables of both halves");
                  helper.assertValueEqual(energy(left) + energy(right) + inMiddle, state[0], "energy of both halves and the inaccessible cables");
                  //Equal shares per cable: each half holds energy in proportion to its cables
                  long perCable = (energy(left) + energy(right)) / (LINE - 16);
                  helper.assertTrue(Math.abs(energy(left) - perCable * left.transmittersSize()) <= LINE, "The left half holds " + energy(left)
                                                                                                         + " for " + left.transmittersSize() + " cables");
              })
              .thenExecute(() -> setAccessible(helper, 16, 31, 0, 0, 0, 0, true))
              .thenWaitUntil(() -> {
                  EnergyNetwork network = network(helper, 0, 0, 0);
                  helper.assertTrue(network != null && network == network(helper, LINE - 1, 0, 0), "The line is not one network again");
              })
              .thenIdle(2)
              .thenExecute(() -> {
                  EnergyNetwork network = network(helper, 0, 0, 0);
                  helper.assertValueEqual(network.transmittersSize(), LINE, "network size");
                  helper.assertValueEqual(energy(network), state[0], "energy after the chunk came back");
              })
              .thenSucceed();
    }

    private static void setAccessible(MekGameTestHelper helper, int x1, int x2, int y1, int y2, int z1, int z2, boolean accessible) {
        for (BlockPos pos : BlockPos.betweenClosed(x1, y1, z1, x2, y2, z2)) {
            if (helper.getLevel().getBlockEntity(helper.absolutePos(pos)) instanceof TileEntityUniversalCable tile) {
                tile.chunkAccessibilityChange(accessible);
            }
        }
    }

    //Benches: large energy networks (~80k cables) of 4 layers joined by one riser each, so breaking the top riser splits a layer (~20k cables) off.
    // Comb: rows 2 apart joined at both ends (long loops, the worst case for finding out that breaking a row cable keeps the network connected).
    // Grid: lines every 8 blocks in both directions, like a cable along every road (short loops).
    private static final int BENCH_WIDTH = 200;
    private static final int BENCH_LAYERS = 4;
    private static final int BENCH_ROUNDS = 4;
    private static final int GRID_WIDTH = 288;
    private static final int GRID_SPACING = 8;
    private static final String BENCH_COMB = MekanismTests.MODID + ":split_bench_comb";
    private static final String BENCH_GRID = MekanismTests.MODID + ":split_bench_grid";
    @RegisterStructureTemplate(BENCH_COMB)
    public static final Supplier<StructureTemplate> BENCH_COMB_TEMPLATE = StructureTemplateBuilder.lazy(BENCH_WIDTH, 2 * BENCH_LAYERS - 1, BENCH_WIDTH, builder -> {
        BlockState cable = MekanismBlocks.BASIC_UNIVERSAL_CABLE.defaultState();
        for (int layer = 0; layer < BENCH_LAYERS; layer++) {
            int y = 2 * layer;
            for (int z = 0; z < BENCH_WIDTH; z += 2) {
                builder.fill(0, y, z, BENCH_WIDTH - 1, y, z, cable);
            }
            builder.fill(0, y, 0, 0, y, BENCH_WIDTH - 2, cable);
            builder.fill(BENCH_WIDTH - 1, y, 0, BENCH_WIDTH - 1, y, BENCH_WIDTH - 2, cable);
            if (layer > 0) {
                builder.set(BENCH_WIDTH / 2, y - 1, BENCH_WIDTH / 2, cable);
            }
        }
        return builder;
    });
    @RegisterStructureTemplate(BENCH_GRID)
    public static final Supplier<StructureTemplate> BENCH_GRID_TEMPLATE = StructureTemplateBuilder.lazy(GRID_WIDTH + 1, 2 * BENCH_LAYERS - 1, GRID_WIDTH + 1, builder -> {
        BlockState cable = MekanismBlocks.BASIC_UNIVERSAL_CABLE.defaultState();
        for (int layer = 0; layer < BENCH_LAYERS; layer++) {
            int y = 2 * layer;
            for (int line = 0; line <= GRID_WIDTH; line += GRID_SPACING) {
                builder.fill(0, y, line, GRID_WIDTH, y, line, cable);
                builder.fill(line, y, 0, line, y, GRID_WIDTH, cable);
            }
            if (layer > 0) {
                builder.set(GRID_WIDTH / 2, y - 1, GRID_WIDTH / 2, cable);
            }
        }
        return builder;
    });

    private static final class TickTimer {

        private long tickStart;
        private long maxNanos;
        private final Consumer<ServerTickEvent.Post> start = event -> tickStart = System.nanoTime();
        private final Consumer<ServerTickEvent.Post> end = event -> maxNanos = Math.max(maxNanos, System.nanoTime() - tickStart);

        private void register() {
            NeoForge.EVENT_BUS.addListener(EventPriority.HIGHEST, false, ServerTickEvent.Post.class, start);
            NeoForge.EVENT_BUS.addListener(EventPriority.LOWEST, false, ServerTickEvent.Post.class, end);
        }

        private void unregister() {
            NeoForge.EVENT_BUS.unregister(start);
            NeoForge.EVENT_BUS.unregister(end);
        }

        private void report(String bench, String what, @Nullable EnergyNetwork network) {
            Mekanism.logger.info("NetworkSplitTest bench {} {}: max post tick handlers {} ms; network size {}", bench, what, maxNanos / 1_000_000.0,
                  network == null ? 0 : network.transmittersSize());
            maxNanos = 0;
        }
    }

    @GameTest(template = BENCH_COMB, setupTicks = 5 * SharedConstants.TICKS_PER_SECOND, timeoutTicks = 300 * SharedConstants.TICKS_PER_SECOND, batch = "split-bench-comb")
    @TestHolder(description = "Bench (comb of ~80k cables): removes and restores a row cable, a chunk's cables (inaccessible) and a riser that splits a layer off,"
                              + " logs the post tick handler time of each, and checks it is one network again after each.")
    public static void splitBenchComb(final MekGameTestHelper helper) {
        runBench(helper, "comb", BENCH_WIDTH - 1, BENCH_WIDTH - 2, new BlockPos(BENCH_WIDTH / 2 + 1, 0, BENCH_WIDTH / 2),
              new BlockPos(BENCH_WIDTH / 2, 2 * BENCH_LAYERS - 3, BENCH_WIDTH / 2), 96, 128);
    }

    @GameTest(template = BENCH_GRID, setupTicks = 5 * SharedConstants.TICKS_PER_SECOND, timeoutTicks = 300 * SharedConstants.TICKS_PER_SECOND, batch = "split-bench-grid")
    @TestHolder(description = "Bench (grid of ~80k cables, a line every 8 blocks): removes and restores a line cable, a chunk's cables (inaccessible) and a riser"
                              + " that splits a layer off, logs the post tick handler time of each, and checks it is one network again after each.")
    public static void splitBenchGrid(final MekGameTestHelper helper) {
        runBench(helper, "grid", GRID_WIDTH, GRID_WIDTH, new BlockPos(GRID_WIDTH / 2 + 3, 0, GRID_WIDTH / 2),
              new BlockPos(GRID_WIDTH / 2, 2 * BENCH_LAYERS - 3, GRID_WIDTH / 2), 96, 96);
    }

    private static void runBench(MekGameTestHelper helper, String bench, int farX, int farZ, BlockPos lineCable, BlockPos riser, int chunkX, int chunkZ) {
        TickTimer timer = new TickTimer();
        int[] size = new int[1];
        int top = 2 * BENCH_LAYERS - 2;
        var sequence = helper.startSequence()
              .thenExecute(() -> {
                  EnergyNetwork network = network(helper, 0, 0, 0);
                  helper.assertTrue(network != null && network == network(helper, farX, top, farZ), "The bench is not one network");
                  size[0] = network.transmittersSize();
                  network.energyContainer.setEnergy(network.getCapacity() / 2, null);
                  Mekanism.logger.info("NetworkSplitTest bench {}: {} cables", bench, size[0]);
                  timer.register();
              })
              .thenIdle(20)
              .thenExecute(() -> timer.report(bench, "idle", network(helper, 0, 0, 0)));
        for (int round = 1; round <= BENCH_ROUNDS; round++) {
            String r = " #" + round;
            sequence = sequence
                  .thenExecute(() -> helper.setBlock(lineCable, Blocks.AIR.defaultBlockState()))
                  .thenIdle(5)
                  .thenExecute(() -> timer.report(bench, "break line cable" + r, network(helper, 0, 0, 0)))
                  .thenExecute(() -> helper.setBlock(lineCable, MekanismBlocks.BASIC_UNIVERSAL_CABLE.defaultState()))
                  .thenIdle(5)
                  .thenExecute(() -> {
                      timer.report(bench, "replace line cable" + r, network(helper, 0, 0, 0));
                      assertBenchNetwork(helper, farX, top, farZ, size[0]);
                  })
                  .thenExecute(() -> setAccessible(helper, chunkX, chunkX + 15, 0, top, chunkZ, chunkZ + 15, false))
                  .thenIdle(5)
                  .thenExecute(() -> timer.report(bench, "chunk inaccessible" + r, network(helper, 0, 0, 0)))
                  .thenExecute(() -> setAccessible(helper, chunkX, chunkX + 15, 0, top, chunkZ, chunkZ + 15, true))
                  .thenIdle(5)
                  .thenExecute(() -> {
                      timer.report(bench, "chunk accessible" + r, network(helper, 0, 0, 0));
                      assertBenchNetwork(helper, farX, top, farZ, size[0]);
                  })
                  .thenExecute(() -> helper.setBlock(riser, Blocks.AIR.defaultBlockState()))
                  .thenIdle(5)
                  .thenExecute(() -> timer.report(bench, "break riser (split)" + r, network(helper, 0, 0, 0)))
                  .thenExecute(() -> helper.setBlock(riser, MekanismBlocks.BASIC_UNIVERSAL_CABLE.defaultState()))
                  .thenIdle(5)
                  .thenExecute(() -> {
                      timer.report(bench, "replace riser (merge)" + r, network(helper, 0, 0, 0));
                      assertBenchNetwork(helper, farX, top, farZ, size[0]);
                  });
        }
        sequence.thenExecute(timer::unregister).thenSucceed();
    }

    private static void assertBenchNetwork(MekGameTestHelper helper, int farX, int farY, int farZ, int size) {
        EnergyNetwork network = network(helper, 0, 0, 0);
        helper.assertTrue(network != null && network == network(helper, farX, farY, farZ), "The bench is not one network");
        helper.assertValueEqual(network.transmittersSize(), size, "bench network size");
    }
}
