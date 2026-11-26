package mekanism.common.tests.network;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import mekanism.api.RelativeSide;
import mekanism.api.security.SecurityMode;
import mekanism.common.capabilities.Capabilities;
import mekanism.common.content.entangloporter.InventoryFrequency;
import mekanism.common.lib.frequency.Frequency.FrequencyIdentity;
import mekanism.common.lib.frequency.FrequencyLookup;
import mekanism.common.lib.frequency.FrequencyTypes;
import mekanism.common.lib.transmitter.TransmissionType;
import mekanism.common.registries.MekanismBlocks;
import mekanism.common.tests.helpers.MekGameTestHelper;
import mekanism.common.tile.TileEntityQuantumEntangloporter;
import mekanism.common.tile.component.TileComponentConfig;
import mekanism.common.tile.component.config.ConfigInfo;
import mekanism.common.tile.component.config.DataType;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.neoforged.neoforge.transfer.energy.EnergyHandler;
import net.neoforged.neoforge.transfer.transaction.Transaction;
import net.neoforged.testframework.annotation.ForEachTest;
import net.neoforged.testframework.annotation.TestHolder;
import net.neoforged.testframework.gametest.EmptyTemplate;
import net.neoforged.testframework.gametest.GameTest;

@ForEachTest(groups = "network.entangloporter")
public class EntangloporterFrequencyIsolationTest {

    private static final UUID OWNER = UUID.fromString("a5c6e1b0-8207-4611-8207-000000008207");
    private static final int ENERGY_A = 1_000, ENERGY_B = 3_000;

    /// Represents [Issue 8207](https://github.com/mekanism/Mekanism/issues/8207): two quantum entangloporter pairs on different frequencies whose ends touch
    /// were reported to share their energy. Layout (all sides of each QE the same, auto eject on):
    /// - input ends `A_in`, `B_in` (all sides input) touching each other; energy is inserted through their free sides
    /// - output ends `A_out`, `B_out` (all sides output) touching each other; each also touches its own sink, `C` and `D`, which are QEs on two more
    ///   frequencies with all sides input
    ///
    /// Each frequency's energy must only reach its own sink.
    @GameTest
    @EmptyTemplate
    @TestHolder(description = "Tests that touching quantum entangloporters on different frequencies do not move energy between their frequencies.")
    public static void touchingFrequenciesStaySeparate(final MekGameTestHelper helper) {
        BlockPos aIn = new BlockPos(0, 2, 2), bIn = new BlockPos(0, 2, 1);
        BlockPos aOut = new BlockPos(1, 1, 0), bOut = new BlockPos(1, 1, 1);
        BlockPos sinkC = new BlockPos(0, 1, 0), sinkD = new BlockPos(1, 1, 2);
        String suffix = "_" + helper.getLevel().getGameTime();
        String a = "iso_a" + suffix, b = "iso_b" + suffix, c = "iso_c" + suffix, d = "iso_d" + suffix;
        List<String> names = List.of(a, b, c, d);
        List<InventoryFrequency> frequencies = new ArrayList<>();
        helper.startSequence()
              .thenExecute(() -> {
                  place(helper, aIn, a, DataType.INPUT);
                  place(helper, bIn, b, DataType.INPUT);
                  place(helper, aOut, a, DataType.OUTPUT);
                  place(helper, bOut, b, DataType.OUTPUT);
                  place(helper, sinkC, c, DataType.INPUT);
                  place(helper, sinkD, d, DataType.INPUT);
              })
              .thenExecuteAfter(2, () -> {
                  for (BlockPos pos : List.of(aIn, bIn, sinkC, sinkD)) {
                      InventoryFrequency freq = helper.getBlockEntity(pos, TileEntityQuantumEntangloporter.class).getFreq();
                      helper.assertTrue(freq != null, "Expected a frequency on the QE at " + pos);
                      frequencies.add(freq);
                  }
                  //Insert through the free top side of each input end, as a matrix or cable would
                  insert(helper, aIn, ENERGY_A);
                  insert(helper, bIn, ENERGY_B);
              })
              .thenExecuteAfter(10, () -> {
                  try {
                      helper.assertValueEqual(frequencies.get(0).getEnergyContainer().getAmountAsLong(), 0L, "energy left on frequency A");
                      helper.assertValueEqual(frequencies.get(1).getEnergyContainer().getAmountAsLong(), 0L, "energy left on frequency B");
                      helper.assertValueEqual(frequencies.get(2).getEnergyContainer().getAmountAsLong(), (long) ENERGY_A, "energy in sink C (fed by A)");
                      helper.assertValueEqual(frequencies.get(3).getEnergyContainer().getAmountAsLong(), (long) ENERGY_B, "energy in sink D (fed by B)");
                  } finally {
                      FrequencyLookup<InventoryFrequency> lookup = FrequencyTypes.INVENTORY.getLookup(null, SecurityMode.PUBLIC);
                      for (String name : names) {
                          lookup.remove(name, OWNER);
                      }
                  }
              })
              .thenSucceed();
    }

    private static void place(MekGameTestHelper helper, BlockPos pos, String frequency, DataType dataType) {
        helper.setBlock(pos, MekanismBlocks.QUANTUM_ENTANGLOPORTER.defaultState());
        TileEntityQuantumEntangloporter qe = helper.getBlockEntity(pos, TileEntityQuantumEntangloporter.class);
        qe.getFrequencyComponent().setFrequencyFromData(FrequencyTypes.INVENTORY, new FrequencyIdentity(frequency, SecurityMode.PUBLIC, OWNER), OWNER);
        TileComponentConfig config = qe.getConfig();
        ConfigInfo energy = config.getConfig(TransmissionType.ENERGY);
        helper.assertTrue(energy != null, "Expected an energy config");
        for (RelativeSide side : RelativeSide.VALUES) {
            energy.setDataType(dataType, side);
            config.sideChanged(TransmissionType.ENERGY, side);
        }
        energy.setEjecting(true);
    }

    private static void insert(MekGameTestHelper helper, BlockPos pos, int amount) {
        EnergyHandler handler = helper.getCapability(Capabilities.ENERGY.block(), pos, Direction.UP);
        helper.assertTrue(handler != null, "Expected an energy handler on the top of the QE at " + pos);
        try (Transaction transaction = Transaction.openRoot()) {
            helper.assertValueEqual(handler.insert(amount, transaction), amount, "energy inserted at " + pos);
            transaction.commit();
        }
    }
}
