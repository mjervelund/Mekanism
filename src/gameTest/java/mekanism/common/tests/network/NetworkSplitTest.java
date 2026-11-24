package mekanism.common.tests.network;

import java.util.function.Consumer;
import java.util.function.Supplier;
import mekanism.common.Mekanism;
import mekanism.common.content.network.EnergyNetwork;
import mekanism.common.content.network.transmitter.UniversalCable;
import mekanism.common.registries.MekanismBlocks;
import mekanism.common.tests.MekanismTests;
import mekanism.common.tests.helpers.MekGameTestHelper;
import mekanism.common.tile.transmitter.TileEntityUniversalCable;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ChunkPos;
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
                  helper.setBlock(new BlockPos(3, 0, 1), MekanismBlocks.BASIC_ENERGY_CUBE.defaultState());
                  helper.setBlock(new BlockPos(3, 0, 5), MekanismBlocks.BASIC_ENERGY_CUBE.defaultState());
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
                  before[1] = energy;
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
                  //The energy can only stay the same if the network did not re-form; a re-form would give it back the same amount though, so also check identity
                  helper.assertValueEqual(energy(network), (long) before[1], "energy after the break");
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
                  helper.assertValueEqual(energy(network), (long) before[1], "energy after replacing");
              })
              .thenSucceed()
        );
    }

    @GameTest(template = LINE_CABLE, setupTicks = SharedConstants.TICKS_PER_SECOND, timeoutTicks = 10 * SharedConstants.TICKS_PER_SECOND, batch = "split-chunk")
    @TestHolder(description = "When the middle chunk of a charged cable line becomes inaccessible, the line splits into two networks and the cables of that chunk"
                              + " take their shares; when it comes back, it is one network again with all of the energy.")
    public static void inaccessibleChunkKeepsEnergy(final MekGameTestHelper helper) {
        ChunkPos middle = new ChunkPos(1, 0);
        long[] state = new long[2];
        helper.startSequence()
              .thenExecute(() -> {
                  EnergyNetwork network = network(helper, 0, 0, 0);
                  helper.assertTrue(network != null && network == network(helper, LINE - 1, 0, 0), "The line is not one network");
                  long energy = network.getCapacity() / 3 + 11;
                  network.energyContainer.setEnergy(energy, null);
                  state[0] = energy;
              })
              .thenExecute(() -> state[1] = helper.setChunkLoadLevel(middle, MekGameTestHelper.INACCESSIBLE_LEVEL))
              .thenWaitUntil(() -> {
                  EnergyNetwork left = network(helper, 0, 0, 0);
                  EnergyNetwork right = network(helper, LINE - 1, 0, 0);
                  helper.assertTrue(left != null && right != null && left != right, "The line did not split into two networks");
              })
              .thenIdle(2)
              .thenExecute(() -> {
                  EnergyNetwork left = network(helper, 0, 0, 0);
                  EnergyNetwork right = network(helper, LINE - 1, 0, 0);
                  ChunkPos middleChunk = helper.absolutePos(middle);
                  long inMiddle = 0;
                  int middleCables = 0;
                  for (int x = 0; x < LINE; x++) {
                      UniversalCable cable = cable(helper, x, 0, 0);
                      if (ChunkPos.containing(helper.absolutePos(new BlockPos(x, 0, 0))).equals(middleChunk)) {
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
              .thenExecute(() -> helper.setChunkLoadLevel(middle, (int) state[1]))
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

    //Bench: a large energy network (~80k cables) in layers of combs whose rows are joined at both ends, so removing one row cable keeps it connected
    private static final int BENCH_WIDTH = 200;
    private static final int BENCH_LAYERS = 4;
    private static final int BENCH_ROUNDS = 4;
    private static final String BENCH = MekanismTests.MODID + ":split_bench";
    @RegisterStructureTemplate(BENCH)
    public static final Supplier<StructureTemplate> BENCH_TEMPLATE = StructureTemplateBuilder.lazy(BENCH_WIDTH, 2 * BENCH_LAYERS - 1, BENCH_WIDTH, builder -> {
        BlockState cable = MekanismBlocks.BASIC_UNIVERSAL_CABLE.defaultState();
        for (int layer = 0; layer < BENCH_LAYERS; layer++) {
            int y = 2 * layer;
            for (int z = 0; z < BENCH_WIDTH; z += 2) {
                builder.fill(0, y, z, BENCH_WIDTH - 1, y, z, cable);
            }
            builder.fill(0, y, 0, 0, y, BENCH_WIDTH - 2, cable);
            builder.fill(BENCH_WIDTH - 1, y, 0, BENCH_WIDTH - 1, y, BENCH_WIDTH - 2, cable);
            if (layer > 0) {
                //One riser from the layer below: breaking it splits the layers above off
                builder.set(BENCH_WIDTH / 2, y - 1, BENCH_WIDTH / 2, cable);
            }
        }
        return builder;
    });
    private static final BlockPos BENCH_ROW_CABLE = new BlockPos(BENCH_WIDTH / 2 + 1, 0, BENCH_WIDTH / 2);
    private static final BlockPos BENCH_RISER = new BlockPos(BENCH_WIDTH / 2, 2 * BENCH_LAYERS - 3, BENCH_WIDTH / 2);
    private static final ChunkPos BENCH_CHUNK = new ChunkPos(BENCH_WIDTH / 32, BENCH_WIDTH / 32 + 2);

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

        private void report(String what, @Nullable EnergyNetwork network) {
            Mekanism.logger.info("NetworkSplitTest bench {}: max post tick handlers {} ms; network size {}", what, maxNanos / 1_000_000.0,
                  network == null ? 0 : network.transmittersSize());
            maxNanos = 0;
        }
    }

    @GameTest(template = BENCH, setupTicks = 5 * SharedConstants.TICKS_PER_SECOND, timeoutTicks = 300 * SharedConstants.TICKS_PER_SECOND, batch = "split-bench")
    @TestHolder(description = "Bench: removes and restores cables of an ~80k cable energy network (one row cable, a chunk becoming inaccessible, a riser that"
                              + " splits a layer off) and logs the post tick handler time of each; checks it is one network again after each.")
    public static void splitBench(final MekGameTestHelper helper) {
        TickTimer timer = new TickTimer();
        int[] state = new int[2];
        var sequence = helper.startSequence()
              .thenExecute(() -> {
                  EnergyNetwork network = network(helper, 0, 0, 0);
                  helper.assertTrue(network != null && network == network(helper, BENCH_WIDTH - 1, 2 * BENCH_LAYERS - 2, BENCH_WIDTH - 2), "The bench is not one network");
                  state[0] = network.transmittersSize();
                  network.energyContainer.setEnergy(network.getCapacity() / 2, null);
                  Mekanism.logger.info("NetworkSplitTest bench: {} cables", state[0]);
                  timer.register();
              })
              .thenIdle(20)
              .thenExecute(() -> timer.report("idle", network(helper, 0, 0, 0)));
        for (int round = 1; round <= BENCH_ROUNDS; round++) {
            String r = " #" + round;
            sequence = sequence
                  .thenExecute(() -> helper.setBlock(BENCH_ROW_CABLE, Blocks.AIR.defaultBlockState()))
                  .thenIdle(5)
                  .thenExecute(() -> timer.report("break row cable" + r, network(helper, 0, 0, 0)))
                  .thenExecute(() -> helper.setBlock(BENCH_ROW_CABLE, MekanismBlocks.BASIC_UNIVERSAL_CABLE.defaultState()))
                  .thenIdle(5)
                  .thenExecute(() -> {
                      timer.report("replace row cable" + r, network(helper, 0, 0, 0));
                      assertBenchNetwork(helper, state[0]);
                  })
                  .thenExecute(() -> state[1] = helper.setChunkLoadLevel(BENCH_CHUNK, MekGameTestHelper.INACCESSIBLE_LEVEL))
                  .thenIdle(5)
                  .thenExecute(() -> timer.report("chunk inaccessible" + r, network(helper, 0, 0, 0)))
                  .thenExecute(() -> helper.setChunkLoadLevel(BENCH_CHUNK, state[1]))
                  .thenIdle(5)
                  .thenExecute(() -> {
                      timer.report("chunk accessible" + r, network(helper, 0, 0, 0));
                      assertBenchNetwork(helper, state[0]);
                  })
                  .thenExecute(() -> helper.setBlock(BENCH_RISER, Blocks.AIR.defaultBlockState()))
                  .thenIdle(5)
                  .thenExecute(() -> timer.report("break riser (split)" + r, network(helper, 0, 0, 0)))
                  .thenExecute(() -> helper.setBlock(BENCH_RISER, MekanismBlocks.BASIC_UNIVERSAL_CABLE.defaultState()))
                  .thenIdle(5)
                  .thenExecute(() -> {
                      timer.report("replace riser (merge)" + r, network(helper, 0, 0, 0));
                      assertBenchNetwork(helper, state[0]);
                  });
        }
        sequence.thenExecute(timer::unregister).thenSucceed();
    }

    private static void assertBenchNetwork(MekGameTestHelper helper, int size) {
        EnergyNetwork network = network(helper, 0, 0, 0);
        helper.assertTrue(network != null && network == network(helper, BENCH_WIDTH - 1, 2 * BENCH_LAYERS - 2, BENCH_WIDTH - 2), "The bench is not one network");
        helper.assertValueEqual(network.transmittersSize(), size, "bench network size");
    }
}
