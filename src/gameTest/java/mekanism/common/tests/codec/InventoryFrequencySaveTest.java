package mekanism.common.tests.codec;

import java.util.UUID;
import mekanism.api.AutomationType;
import mekanism.api.chemical.ChemicalIds;
import mekanism.api.chemical.ChemicalResource;
import mekanism.api.security.SecurityMode;
import mekanism.common.content.entangloporter.InventoryFrequency;
import mekanism.common.lib.frequency.Frequency.FrequencyIdentity;
import mekanism.common.lib.frequency.FrequencyLookup;
import mekanism.common.lib.frequency.FrequencyTypes;
import mekanism.common.tests.helpers.MekGameTestHelper;
import mekanism.common.util.ChemicalUtils;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.RegistryOps;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.material.Fluids;
import net.neoforged.neoforge.transfer.fluid.FluidResource;
import net.neoforged.neoforge.transfer.item.ItemResource;
import net.neoforged.neoforge.transfer.transaction.Transaction;
import net.neoforged.testframework.annotation.ForEachTest;
import net.neoforged.testframework.annotation.TestHolder;
import net.neoforged.testframework.gametest.EmptyTemplate;
import net.neoforged.testframework.gametest.GameTest;

@ForEachTest(groups = "codec.frequency")
public class InventoryFrequencySaveTest {

    private static final UUID OWNER = UUID.fromString("a5c6e1b0-7957-4611-8611-000000008611");

    /// Part of [Issue 8611](https://github.com/mekanism/Mekanism/issues/8611): makes sure that the contents of a quantum entangloporter frequency survive the
    /// codec it is saved with, including amounts above [Integer#MAX_VALUE].
    @GameTest
    @EmptyTemplate
    @TestHolder(description = "Tests that inventory frequency contents (energy, fluid and chemical amounts above Integer.MAX_VALUE, items, heat) survive a save and load.")
    public static void contentsRoundTrip(final MekGameTestHelper helper) {
        helper.succeedIf(() -> {
            long energy = 5_000_000_000L, fluid = 3_000_000_000L, chemical = 4_000_000_000L;
            ChemicalResource hydrogen = ChemicalUtils.getResource(helper.getLevel().registryAccess(), ChemicalIds.HYDROGEN);
            InventoryFrequency frequency = new InventoryFrequency("roundtrip", OWNER, SecurityMode.PUBLIC);
            frequency.getEnergyContainer().setEnergy(energy, null);
            frequency.getFluidTanks().getFirst().setContents(FluidResource.of(Fluids.WATER), fluid, null);
            frequency.getChemicalTanks().getFirst().setContents(hydrogen, chemical, null);
            frequency.getInventorySlots().getFirst().setContents(ItemResource.of(Items.STONE), 17, null);
            frequency.getHeatCapacitor().setHeat(12_345, null);

            RegistryOps<Tag> ops = helper.getLevel().registryAccess().createSerializationContext(NbtOps.INSTANCE);
            Tag saved = InventoryFrequency.CODEC.encodeStart(ops, frequency).getOrThrow(helper::assertionException);
            InventoryFrequency loaded = InventoryFrequency.CODEC.parse(ops, saved).getOrThrow(helper::assertionException);

            helper.assertValueEqual(loaded.getName(), "roundtrip", "name");
            helper.assertValueEqual(loaded.getOwner(), OWNER, "owner");
            helper.assertValueEqual(loaded.getEnergyContainer().getAmountAsLong(), energy, "energy");
            helper.assertValueEqual(loaded.getFluidTanks().getFirst().resource(), FluidResource.of(Fluids.WATER), "fluid");
            helper.assertValueEqual(loaded.getFluidTanks().getFirst().amountAsLong(), fluid, "fluid amount");
            helper.assertValueEqual(loaded.getChemicalTanks().getFirst().resource(), hydrogen, "chemical");
            helper.assertValueEqual(loaded.getChemicalTanks().getFirst().amountAsLong(), chemical, "chemical amount");
            helper.assertValueEqual(loaded.getInventorySlots().getFirst().resource(), ItemResource.of(Items.STONE), "item");
            helper.assertValueEqual(loaded.getInventorySlots().getFirst().amountAsLong(), 17L, "item amount");
            helper.assertValueEqual(loaded.getHeatCapacitor().getHeat(), 12_345D, "heat");
        });
    }

    /// Represents [Issue 8611](https://github.com/mekanism/Mekanism/issues/8611): the contents of a quantum entangloporter were lost after a restart. A
    /// frequency marked itself dirty when its contents changed, but nothing read that flag any more, so the saved data holding the frequencies was only
    /// written when a frequency was added or removed, and every change to the contents since then was lost on a restart.
    @GameTest
    @EmptyTemplate
    @TestHolder(description = "Tests that changing the contents of an inventory frequency marks its saved data as needing to be saved.")
    public static void contentsChangeMarksSavedDataDirty(final MekGameTestHelper helper) {
        FrequencyLookup<InventoryFrequency> lookup = FrequencyTypes.INVENTORY.getLookup(null, SecurityMode.PUBLIC);
        helper.assertTrue(lookup != null, "Expected a public inventory frequency lookup");
        String name = "dirty_" + helper.getLevel().getGameTime();
        InventoryFrequency frequency = lookup.getOrCreateFrequency(new FrequencyIdentity(name, SecurityMode.PUBLIC, OWNER), OWNER);
        try {
            //As after the saved data has been saved
            lookup.setDirty(false);
            helper.assertFalse(lookup.isDirty(), "Expected the lookup to not be dirty right after a save");

            //What a QE does when something inserts into it: a transaction that is committed
            try (Transaction transaction = Transaction.openRoot()) {
                helper.assertValueEqual(frequency.getFluidTanks().getFirst().insert(FluidResource.of(Fluids.WATER), 1_000, transaction, AutomationType.EXTERNAL),
                      1_000, "inserted fluid");
                transaction.commit();
            }
            helper.assertTrue(lookup.isDirty(), "Expected a change to the frequency's fluid to mark the saved data dirty");

            lookup.setDirty(false);
            helper.assertFalse(lookup.isDirty(), "Expected the lookup to not be dirty after the next save");
            try (Transaction transaction = Transaction.openRoot()) {
                helper.assertValueEqual(frequency.getEnergyContainer().insert(1_000, transaction, AutomationType.EXTERNAL), 1_000, "inserted energy");
                transaction.commit();
            }
            helper.assertTrue(lookup.isDirty(), "Expected a change to the frequency's energy to mark the saved data dirty");
        } finally {
            lookup.remove(name, OWNER);
        }
        helper.succeed();
    }
}
