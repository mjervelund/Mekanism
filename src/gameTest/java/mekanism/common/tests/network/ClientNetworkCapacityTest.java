package mekanism.common.tests.network;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;
import mekanism.common.Mekanism;
import mekanism.common.content.network.EnergyNetwork;
import mekanism.common.content.network.transmitter.UniversalCable;
import mekanism.common.registries.MekanismBlocks;
import mekanism.common.tests.MekanismTests;
import mekanism.common.tests.helpers.MekGameTestHelper;
import mekanism.common.tile.transmitter.TileEntityUniversalCable;
import net.minecraft.SharedConstants;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import net.neoforged.testframework.DynamicTest;
import net.neoforged.testframework.annotation.ForEachTest;
import net.neoforged.testframework.annotation.RegisterStructureTemplate;
import net.neoforged.testframework.annotation.TestHolder;
import net.neoforged.testframework.gametest.GameTest;
import net.neoforged.testframework.gametest.StructureTemplateBuilder;

@ForEachTest(groups = "network.transmitter")
public class ClientNetworkCapacityTest {

    private static final int SIZE_X = 100;
    private static final int SIZE_Z = 50;
    private static final String CABLE_SHEET = MekanismTests.MODID + ":cable_sheet";
    @RegisterStructureTemplate(CABLE_SHEET)
    public static final Supplier<StructureTemplate> CABLE_SHEET_TEMPLATE = StructureTemplateBuilder.lazy(SIZE_X, 1, SIZE_Z,
          builder -> builder.fill(0, 0, 0, SIZE_X - 1, 0, SIZE_Z - 1, MekanismBlocks.BASIC_UNIVERSAL_CABLE.defaultState())
    );

    @GameTest(template = CABLE_SHEET, setupTicks = SharedConstants.TICKS_PER_SECOND, timeoutTicks = 30 * SharedConstants.TICKS_PER_SECOND)
    @TestHolder(description = "A network formed one transmitter at a time the way the client does it (one update per cable) ends with the same"
                              + " capacity as a full rescan, and the capacity follows later joins and explicit rescans.")
    public static void clientJoinCapacity(final DynamicTest test) {
        test.onGameTest(MekGameTestHelper.class, helper -> helper.startSequence()
              .thenExecute(() -> {
                  List<UniversalCable> cables = new ArrayList<>(SIZE_X * SIZE_Z);
                  for (int x = 0; x < SIZE_X; x++) {
                      for (int z = 0; z < SIZE_Z; z++) {
                          cables.add(helper.getBlockEntity(x, 0, z, TileEntityUniversalCable.class).getTransmitter());
                      }
                  }
                  long perCable = cables.getFirst().getCapacity();
                  helper.assertTrue(perCable > 0, "cable capacity");

                  //Upstream client path: a full rescan after every joining cable (O(N^2))
                  EnergyNetwork rescanned = new EnergyNetwork(UUID.randomUUID());
                  long start = System.nanoTime();
                  for (UniversalCable cable : cables) {
                      rescanned.addTransmitter(cable);
                      rescanned.updateCapacity();
                  }
                  long rescanNanos = System.nanoTime() - start;

                  //Client path now: mark dirty per joining cable, recompute once on read
                  EnergyNetwork lazy = new EnergyNetwork(UUID.randomUUID());
                  start = System.nanoTime();
                  for (UniversalCable cable : cables) {
                      lazy.addTransmitter(cable);
                      lazy.markCapacityDirty();
                  }
                  long lazyCapacity = lazy.getCapacity();
                  long lazyNanos = System.nanoTime() - start;

                  helper.assertValueEqual(lazyCapacity, rescanned.getCapacity(), "capacity after join");
                  helper.assertValueEqual(lazyCapacity, perCable * cables.size(), "capacity is the sum");
                  Mekanism.logger.info("ClientNetworkCapacityTest: {} cables, per-cable rescan {} ms, lazy {} ms", cables.size(),
                        rescanNanos / 1_000_000.0, lazyNanos / 1_000_000.0);

                  //Reads without changes do not rescan and stay the same
                  helper.assertValueEqual(lazy.getCapacity(), lazyCapacity, "repeated read");
                  //A removal followed by a join is reflected on the next read, as a full rescan would be
                  lazy.removeTransmitter(cables.getLast());
                  lazy.markCapacityDirty();
                  helper.assertValueEqual(lazy.getCapacity(), perCable * (cables.size() - 1), "capacity after removal + update");
                  lazy.addTransmitter(cables.getLast());
                  lazy.markCapacityDirty();
                  helper.assertValueEqual(lazy.getCapacity(), lazyCapacity, "capacity after rejoin");
                  //An explicit rescan clears the dirty flag and gives the same value
                  lazy.markCapacityDirty();
                  lazy.updateCapacity();
                  helper.assertValueEqual(lazy.getCapacity(), lazyCapacity, "capacity after explicit rescan");
              })
              .thenSucceed()
        );
    }
}
