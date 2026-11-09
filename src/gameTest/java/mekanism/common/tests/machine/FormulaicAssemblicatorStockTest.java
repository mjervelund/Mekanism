package mekanism.common.tests.machine;

import java.util.List;
import mekanism.api.AutomationType;
import mekanism.api.inventory.IInventorySlot;
import mekanism.common.registries.MekanismBlocks;
import mekanism.common.registries.MekanismItems;
import mekanism.common.tests.helpers.MekGameTestHelper;
import mekanism.common.tile.machine.TileEntityFormulaicAssemblicator;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.neoforged.neoforge.transfer.item.ItemResource;
import net.neoforged.neoforge.transfer.transaction.Transaction;
import net.neoforged.testframework.DynamicTest;
import net.neoforged.testframework.annotation.ForEachTest;
import net.neoforged.testframework.annotation.TestHolder;
import net.neoforged.testframework.gametest.GameTest;
import net.neoforged.testframework.gametest.StructureTemplateBuilder;

@ForEachTest(groups = "machine.formulaic_assemblicator")
public class FormulaicAssemblicatorStockTest {

    //Slot layout of the assemblicator's inventory: formula slot, then 18 input slots, then the 9 crafting grid slots
    private static final int FIRST_INPUT = 1;
    private static final int FIRST_GRID = FIRST_INPUT + 18;

    @GameTest
    @TestHolder(description = "Tests that stock control accepts items whose components differ but that the formula's ingredient accepts (mekanism/Mekanism#8041).")
    public static void testStockControlAcceptsDamagedItems(final DynamicTest test) {
        test.registerGameTestTemplate(() -> StructureTemplateBuilder.withSize(1, 1, 1).set(0, 0, 0, MekanismBlocks.FORMULAIC_ASSEMBLICATOR.defaultState()));
        test.onGameTest(MekGameTestHelper.class, helper -> helper.startSequence()
              .thenMap(() -> helper.getBlockEntity(0, 0, 0, TileEntityFormulaicAssemblicator.class))
              .thenExecute(tile -> {
                  //Dispenser: cobblestone ring, bow in the centre, redstone below it
                  List<IInventorySlot> slots = tile.getInventorySlots();
                  ItemResource cobble = ItemResource.of(Items.COBBLESTONE);
                  for (int i = 0; i < 9; i++) {
                      ItemResource type = i == 4 ? ItemResource.of(Items.BOW) : i == 7 ? ItemResource.of(Items.REDSTONE) : cobble;
                      slots.get(FIRST_GRID + i).setContents(type, 1, null);
                  }
                  tile.getFormulaSlot().setContents(ItemResource.of(MekanismItems.CRAFTING_FORMULA.asItem()), 1, null);
                  tile.encodeFormula();
              })
              .thenExecuteAfter(1, tile -> {
                  if (tile.formula.isEmpty() || !tile.formula.valid()) {
                      throw helper.assertionException("Formula was not loaded");
                  }
                  //Clear the grid so the test only exercises the input slots
                  List<IInventorySlot> slots = tile.getInventorySlots();
                  for (int i = 0; i < 9; i++) {
                      slots.get(FIRST_GRID + i).setContents(ItemResource.EMPTY, 0, null);
                  }
                  tile.toggleStockControl(helper.getLevel());
                  if (!tile.getStockControl()) {
                      throw helper.assertionException("Stock control did not enable");
                  }
                  //Insert something into the inputs so that the stock control map gets built on the next tick
                  slots.get(FIRST_INPUT).setContents(ItemResource.of(Items.COBBLESTONE), 1, null);
              })
              .thenExecuteAfter(1, tile -> {
                  List<IInventorySlot> slots = tile.getInventorySlots();
                  //Stock map: cobblestone (7 uses) -> inputs 0-13, bow -> 14-15, redstone -> 16-17
                  IInventorySlot bowSlot = slots.get(FIRST_INPUT + 14);
                  IInventorySlot cobbleSlot = slots.get(FIRST_INPUT + 2);
                  ItemStack damaged = new ItemStack(Items.BOW);
                  damaged.set(DataComponents.DAMAGE, 100);
                  ItemResource damagedBow = ItemResource.of(damaged);
                  assertInsert(helper, bowSlot, ItemResource.of(Items.BOW), true, "undamaged bow into bow stock slot");
                  assertInsert(helper, bowSlot, damagedBow, true, "damaged bow into bow stock slot");
                  assertInsert(helper, cobbleSlot, damagedBow, false, "damaged bow into cobblestone stock slot");
                  //Actually insert it; the reorganization on the next tick must keep it in a bow stock slot
                  try (Transaction transaction = Transaction.openRoot()) {
                      if (slots.get(FIRST_INPUT + 15).insert(damagedBow, 1, transaction, AutomationType.EXTERNAL) != 1) {
                          throw helper.assertionException("Failed to insert damaged bow");
                      }
                      transaction.commit();
                  }
              })
              .thenExecuteAfter(1, tile -> {
                  List<IInventorySlot> slots = tile.getInventorySlots();
                  for (int i = 0; i < 18; i++) {
                      IInventorySlot slot = slots.get(FIRST_INPUT + i);
                      if (slot.resource().getItem() == Items.BOW && i != 14 && i != 15) {
                          throw helper.assertionException("Stock organization moved the damaged bow out of the bow stock slots into input " + i);
                      }
                  }
                  if (slots.get(FIRST_INPUT + 14).resource().getItem() != Items.BOW && slots.get(FIRST_INPUT + 15).resource().getItem() != Items.BOW) {
                      throw helper.assertionException("Damaged bow is missing from the bow stock slots");
                  }
              })
              .thenSucceed()
        );
    }

    private static void assertInsert(MekGameTestHelper helper, IInventorySlot slot, ItemResource resource, boolean expected, String what) {
        int inserted;
        try (Transaction transaction = Transaction.openRoot()) {
            //Simulated: never committed
            inserted = slot.insert(resource, 1, transaction, AutomationType.EXTERNAL);
        }
        if ((inserted == 1) != expected) {
            throw helper.assertionException("Expected " + what + " to be " + (expected ? "accepted" : "rejected") + " but inserted " + inserted);
        }
    }
}
