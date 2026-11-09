package mekanism.common.tests.gear;

import java.util.Objects;
import mekanism.api.gear.IModule;
import mekanism.common.component.containers.type.ContainerType;
import mekanism.common.content.gear.ModuleContainer;
import mekanism.common.content.gear.ModuleHelper;
import mekanism.common.content.gear.mekasuit.ModuleChargeDistributionUnit;
import mekanism.common.item.gear.ItemMekaSuitArmor;
import mekanism.common.registries.MekanismItems;
import mekanism.common.registries.MekanismModules;
import mekanism.common.tests.helpers.MekGameTestHelper;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameType;
import net.neoforged.neoforge.transfer.access.ItemAccess;
import net.neoforged.neoforge.transfer.transaction.Transaction;
import net.neoforged.testframework.annotation.ForEachTest;
import net.neoforged.testframework.annotation.TestHolder;
import net.neoforged.testframework.gametest.EmptyTemplate;
import net.neoforged.testframework.gametest.GameTest;

@ForEachTest(groups = "gear.mekasuit")
public class ChargeDistributionUnitTest {

    private static final int TABLET_SLOT = 9;

    @GameTest
    @EmptyTemplate
    @TestHolder(description = "Tests that the MekaSuit charge distribution unit can charge items in the player's inventory.")
    public static void chargesInventory(final MekGameTestHelper helper) {
        helper.startSequence()
              .thenExecute(() -> {
                  Player player = helper.makeTickingMockServerPlayerInLevel(GameType.SURVIVAL);
                  ItemStack body = ContainerType.ENERGY.getFilledVariant(MekanismItems.MEKASUIT_BODYARMOR, null);
                  if (ContainerType.ENERGY.getOrZero(body) <= 0) {
                      helper.fail("MekaSuit body armor has no energy");
                  }
                  ItemAccess bodyAccess = ItemAccess.forStack(body);
                  ModuleContainer container = Objects.requireNonNull(ModuleHelper.get().getModuleContainer(body), "Body armor is not a module container");
                  try (Transaction transaction = Transaction.openRoot()) {
                      if (container.addModule(helper.getLevel().registryAccess(), bodyAccess, MekanismModules.CHARGE_DISTRIBUTION_UNIT, 1, transaction) != 1) {
                          helper.fail("Unable to install the charge distribution unit");
                      }
                      transaction.commit();
                  }
                  container = Objects.requireNonNull(ModuleHelper.get().getModuleContainer(body), "Body armor is not a module container");
                  IModule<ModuleChargeDistributionUnit> module = Objects.requireNonNull(container.get(MekanismModules.CHARGE_DISTRIBUTION_UNIT), "Charge distribution unit not installed");
                  module.replaceModuleConfig(helper.getLevel().registryAccess(), bodyAccess, null,
                        module.<Boolean>getConfigOrThrow(ModuleChargeDistributionUnit.CHARGE_INVENTORY).with(true));
                  container = Objects.requireNonNull(ModuleHelper.get().getModuleContainer(body), "Body armor is not a module container");
                  module = Objects.requireNonNull(container.get(MekanismModules.CHARGE_DISTRIBUTION_UNIT), "Charge distribution unit not installed");
                  if (!module.isEnabled() || !module.getBooleanConfigOrFalse(ModuleChargeDistributionUnit.CHARGE_INVENTORY)) {
                      helper.fail("Charge distribution unit is not enabled with charge inventory on");
                  }

                  player.setItemSlot(EquipmentSlot.CHEST, body);
                  player.getInventory().setItem(TABLET_SLOT, MekanismItems.ENERGY_TABLET.asStack());
                  if (ContainerType.ENERGY.getOrZero(player.getInventory().getItem(TABLET_SLOT)) != 0) {
                      helper.fail("Energy tablet did not start empty");
                  }

                  ItemStack worn = player.getItemBySlot(EquipmentSlot.CHEST);
                  ((ItemMekaSuitArmor) worn.getItem()).inventoryTick(worn, helper.getLevel(), player, EquipmentSlot.CHEST);

                  long charged = ContainerType.ENERGY.getOrZero(player.getInventory().getItem(TABLET_SLOT));
                  if (charged <= 0) {
                      helper.fail("Charge distribution unit did not charge the energy tablet in the inventory");
                  }
              })
              .thenSucceed();
    }
}
