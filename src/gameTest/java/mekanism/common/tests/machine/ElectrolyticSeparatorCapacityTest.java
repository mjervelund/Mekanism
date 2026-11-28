package mekanism.common.tests.machine;

import mekanism.api.upgrade.Upgrade;
import mekanism.common.capabilities.energy.ElectroSeparatorEnergyContainer;
import mekanism.common.registries.MekanismBlocks;
import mekanism.common.tests.helpers.MekGameTestHelper;
import mekanism.common.tile.machine.TileEntityElectrolyticSeparator;
import net.minecraft.SharedConstants;
import net.minecraft.core.HolderGetter;
import net.minecraft.world.level.material.Fluids;
import net.neoforged.neoforge.transfer.fluid.FluidResource;
import net.neoforged.testframework.DynamicTest;
import net.neoforged.testframework.annotation.ForEachTest;
import net.neoforged.testframework.annotation.TestHolder;
import net.neoforged.testframework.gametest.GameTest;
import net.neoforged.testframework.gametest.StructureTemplateBuilder;

@ForEachTest(groups = "machine.electrolytic_separator")
public class ElectrolyticSeparatorCapacityTest {

    /// ElectroSeparatorEnergyContainer.updateMaxEnergy scaled getCapacityAsLong(), which is at least the stored amount. With a full buffer every
    /// recipe cache change multiplied the capacity again, so a separator making hydrogen kept growing its buffer and absorbing energy without bound.
    @GameTest(setupTicks = SharedConstants.TICKS_PER_SECOND)
    @TestHolder(description = "Tests that updating an electrolytic separator's max energy with a full buffer does not grow its capacity.")
    public static void capacityDoesNotCompound(final DynamicTest test) {
        test.registerGameTestTemplate(() -> StructureTemplateBuilder.withSize(1, 1, 1)
              .set(0, 0, 0, MekanismBlocks.ELECTROLYTIC_SEPARATOR.defaultState())
        );
        test.onGameTest(MekGameTestHelper.class, helper -> helper.startSequence()
              .thenMap(() -> helper.getBlockEntity(0, 0, 0, TileEntityElectrolyticSeparator.class))
              .thenExecute(tile -> tile.fluidTank.setContents(FluidResource.of(Fluids.WATER), 1_000, null))
              .thenWaitUntil(tile -> helper.assertTrue(tile.isMakingHydrogen(), "Separator did not pick up the water electrolysis recipe"))
              .thenExecute(tile -> {
                  ElectroSeparatorEnergyContainer container = tile.energyContainer();
                  HolderGetter<Upgrade> upgrades = tile.getUpgradeLookup(helper.getLevel().registryAccess());
                  container.updateMaxEnergy(upgrades);
                  long capacity = container.getCapacityAsLong();
                  helper.assertTrue(capacity > 0, "Separator has no energy capacity");
                  //Fill the buffer, then recompute twice: the capacity must stay the same
                  container.setEnergy(capacity, null);
                  container.updateMaxEnergy(upgrades);
                  helper.assertValueEqual(container.getCapacityAsLong(), capacity, "capacity after the first update with a full buffer");
                  container.updateMaxEnergy(upgrades);
                  helper.assertValueEqual(container.getCapacityAsLong(), capacity, "capacity after the second update with a full buffer");
              })
              .thenSucceed()
        );
    }
}
