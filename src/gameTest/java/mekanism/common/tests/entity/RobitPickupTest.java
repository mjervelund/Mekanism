package mekanism.common.tests.entity;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import mekanism.api.inventory.IInventorySlot;
import mekanism.common.entity.EntityRobit;
import mekanism.common.entity.ai.RobitAIPickup;
import mekanism.common.registries.MekanismBlocks;
import mekanism.common.registries.MekanismContainerTypes;
import mekanism.common.registries.MekanismEntityTypes;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.neoforge.transfer.item.ItemResource;
import net.neoforged.testframework.DynamicTest;
import net.neoforged.testframework.annotation.ForEachTest;
import net.neoforged.testframework.annotation.TestHolder;
import net.neoforged.testframework.gametest.ExtendedGameTestHelper;
import net.neoforged.testframework.gametest.GameTest;
import net.neoforged.testframework.gametest.StructureTemplateBuilder;

@ForEachTest(groups = "entity.robit")
public class RobitPickupTest {

    @GameTest
    @TestHolder(description = "Tests that a robit with a full inventory does not keep targeting items it cannot pick up (https://github.com/mekanism/Mekanism/issues/8443).")
    public static void fullInventoryDoesNotTargetItems(final DynamicTest test) {
        test.registerGameTestTemplate(() -> StructureTemplateBuilder.withSize(6, 3, 6)
              .fill(0, 0, 0, 5, 0, 5, Blocks.STONE.defaultBlockState())
              //Robits are discarded if they have no home or their home isn't a chargepad
              .set(0, 1, 0, MekanismBlocks.CHARGEPAD.defaultState())
        );
        AtomicReference<EntityRobit> robitRef = new AtomicReference<>();
        test.onGameTest(ExtendedGameTestHelper.class, helper -> helper.startSequence()
              .thenExecute(() -> {
                  EntityRobit robit = helper.spawn(MekanismEntityTypes.ROBIT.get(), 1, 1, 1);
                  robit.setHome(GlobalPos.of(helper.getLevel().dimension(), helper.absolutePos(new BlockPos(0, 1, 0))));
                  //Give it power so it doesn't go home to its chargepad
                  robit.getEnergyContainer().setEnergy(EntityRobit.MAX_ENERGY, null);
                  //Don't let it go after anything until we are ready
                  robit.setDropPickup(false);
                  robitRef.set(robit);
              })
              //Let the robit land, ground navigation can't create paths while it is still in the air
              .thenExecuteAfter(SharedConstants.TICKS_PER_SECOND, () -> {
                  EntityRobit robit = robitRef.get();
                  helper.assertTrue(robit.isAlive() && robit.onGround(), "Expected robit to have landed");
                  robit.setDropPickup(true);
                  ItemEntity item = helper.spawnItem(Items.STICK, 4.5F, 1F, 4.5F);
                  item.setNoPickUpDelay();
                  RobitAIPickup goal = new RobitAIPickup(robit, 1);
                  //Sanity check: with an empty inventory the robit wants to go pick up the item
                  helper.assertTrue(goal.canUse(), "Expected robit with an empty inventory to target the nearby item");
                  //Fill the robit's main inventory with something else. Pickup inserts internally, so the energy slot and the smelting
                  // output slot also accept any item; fill those too, as they would be in a robit that has picked up items until full
                  List<IInventorySlot> slots = new ArrayList<>(robit.getContainerInventorySlots(MekanismContainerTypes.INVENTORY_ROBIT.get()));
                  slots.addAll(robit.getContainerInventorySlots(MekanismContainerTypes.MAIN_ROBIT.get()));
                  slots.add(robit.getContainerInventorySlots(MekanismContainerTypes.SMELTING_ROBIT.get()).getLast());
                  for (IInventorySlot slot : slots) {
                      slot.setContents(ItemResource.of(Items.DIRT), 64, null);
                  }
                  //Now the item can't be picked up, so the goal must not keep running (it has priority over following the owner)
                  helper.assertFalse(goal.canUse(), "Expected robit with a full inventory to not target an item it cannot pick up");
                  helper.assertFalse(new RobitAIPickup(robit, 1).canUse(), "Expected a fresh pickup goal to not target an item the robit cannot pick up");
              })
              .thenSucceed()
        );
    }
}
