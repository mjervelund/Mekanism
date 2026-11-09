package mekanism.common.tests.machine;

import java.util.Objects;
import mekanism.api.RelativeSide;
import mekanism.api.inventory.IInventorySlot;
import mekanism.common.lib.transmitter.TransmissionType;
import mekanism.common.registries.MekanismBlocks;
import mekanism.common.tests.helpers.MekGameTestHelper;
import mekanism.common.tile.component.config.ConfigInfo;
import mekanism.common.tile.component.config.DataType;
import mekanism.common.tile.machine.TileEntityNutritionalLiquifier;
import net.minecraft.SharedConstants;
import net.minecraft.core.Direction;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.neoforge.transfer.item.ItemResource;
import net.neoforged.testframework.DynamicTest;
import net.neoforged.testframework.annotation.ForEachTest;
import net.neoforged.testframework.annotation.TestHolder;
import net.neoforged.testframework.gametest.GameTest;
import net.neoforged.testframework.gametest.StructureTemplateBuilder;

@ForEachTest(groups = "machine.eject")
public class MachineEjectTest {

    @GameTest(timeoutTicks = 5 * SharedConstants.TICKS_PER_SECOND)
    @TestHolder(description = "Tests that auto-eject on an item output side moves the nutritional liquifier's output item (e.g. a returned bowl) out (#8492).")
    public static void liquifierEjectsOutputItem(final DynamicTest test) {
        test.registerGameTestTemplate(() -> StructureTemplateBuilder.withSize(2, 1, 1)
              .set(0, 0, 0, MekanismBlocks.NUTRITIONAL_LIQUIFIER.defaultState())
              .set(1, 0, 0, Blocks.CHEST.defaultBlockState())
        );
        test.onGameTest(MekGameTestHelper.class, helper -> helper.startSequence()
              .thenExecute(() -> {
                  TileEntityNutritionalLiquifier liquifier = helper.getBlockEntity(0, 0, 0, TileEntityNutritionalLiquifier.class);
                  RelativeSide side = RelativeSide.fromDirections(liquifier.getDirection(), Direction.EAST);
                  ConfigInfo itemConfig = Objects.requireNonNull(liquifier.getConfig().getConfig(TransmissionType.ITEM), "Liquifier has no item config");
                  itemConfig.setDataType(DataType.OUTPUT, side);
                  liquifier.getConfig().sideChanged(TransmissionType.ITEM, side);
                  if (itemConfig.getDataType(side) != DataType.OUTPUT) {
                      helper.fail("Unable to set " + side + " to output");
                  }
                  itemConfig.setEjecting(true);
                  //Slot 1 is the recipe's item output slot (where a returned bowl ends up)
                  IInventorySlot outputSlot = liquifier.getInventorySlots().get(1);
                  outputSlot.setContents(ItemResource.of(Items.BOWL), 1, null);
                  if (!outputSlot.resource().is(Items.BOWL)) {
                      helper.fail("Unable to put a bowl in the output slot");
                  }
              })
              .thenExecuteAfter(2 * SharedConstants.TICKS_PER_SECOND, () -> helper.assertContainerContains(1, 0, 0, Items.BOWL, 1))
              .thenSucceed()
        );
    }
}
