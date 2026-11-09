package mekanism.common.tests.multiblock;

import java.util.function.Supplier;
import mekanism.api.inventory.IInventorySlot;
import mekanism.common.content.matrix.MatrixMultiblockData;
import mekanism.common.registries.MekanismBlocks;
import mekanism.common.registries.MekanismItems;
import mekanism.common.tests.MekanismTests;
import mekanism.common.tests.helpers.MekGameTestHelper;
import mekanism.common.tile.base.TileEntityMekanism;
import mekanism.common.tile.multiblock.TileEntityInductionCasing;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import net.neoforged.testframework.DynamicTest;
import net.neoforged.testframework.annotation.ForEachTest;
import net.neoforged.testframework.annotation.RegisterStructureTemplate;
import net.neoforged.testframework.annotation.TestHolder;
import net.neoforged.testframework.gametest.GameTest;
import net.neoforged.testframework.gametest.StructureTemplateBuilder;

@ForEachTest(groups = "multiblock.cache")
public class MultiblockCacheTest {

    private static final int LENGTH = 5;
    private static final int SIZE = 3;

    private static final String INDUCTION_MATRIX = MekanismTests.MODID + ":induction_matrix";
    //Note: Our template is lazy so that we ensure the blocks are registered
    @RegisterStructureTemplate(INDUCTION_MATRIX)
    public static final Supplier<StructureTemplate> INDUCTION_MATRIX_TEMPLATE = StructureTemplateBuilder.lazy(LENGTH, SIZE, SIZE, builder -> builder
          .fill(0, 0, 0, LENGTH - 1, SIZE - 1, SIZE - 1, MekanismBlocks.INDUCTION_CASING.defaultState())
          .set(1, 1, 1, MekanismBlocks.BASIC_INDUCTION_CELL.defaultState())
          .set(2, 1, 1, MekanismBlocks.BASIC_INDUCTION_PROVIDER.defaultState())
          .set(3, 1, 1, MekanismBlocks.BASIC_INDUCTION_CELL.defaultState())
    );

    private static MatrixMultiblockData matrix(MekGameTestHelper helper) {
        return helper.getBlockEntity(0, 0, 0, TileEntityInductionCasing.class).getMultiblock();
    }

    private static IInventorySlot inputSlot(MekGameTestHelper helper) {
        return matrix(helper).getInventorySlots().getFirst();
    }

    @GameTest(template = INDUCTION_MATRIX, timeoutTicks = 15 * SharedConstants.TICKS_PER_SECOND)
    @TestHolder(description = "https://github.com/mekanism/Mekanism/issues/7970: Tests that removing an internal block from a formed induction matrix "
                              + "does not void the matrix's items when the re-formation lines up with the structure validation tick.")
    public static void editInternalKeepsItems(final DynamicTest test) {
        test.onGameTest(MekGameTestHelper.class, helper -> helper.startSequence()
              .thenWaitUntil(() -> {
                  if (!matrix(helper).isFormed()) {
                      helper.fail("Induction matrix did not form");
                  }
              })
              .thenExecute(() -> inputSlot(helper).setContents(MekanismItems.ENERGY_TABLET.asResource(), 1, null))
              //Give the multiblock a couple ticks so that the contents are synced to its cache
              .thenIdle(2)
              .thenExecute(() -> {
                  //Line every multiblock tile up so that its next tick is a structure validation tick (ticker % 10 == 0),
                  // then edit the inside of the matrix like a player would by breaking one of the cells
                  for (int x = 0; x < LENGTH; x++) {
                      for (int y = 0; y < SIZE; y++) {
                          for (int z = 0; z < SIZE; z++) {
                              if (helper.getLevel().getBlockEntity(helper.absolutePos(new BlockPos(x, y, z))) instanceof TileEntityMekanism tile) {
                                  tile.ticker = tile.ticker - tile.ticker % 10 + 10;
                              }
                          }
                      }
                  }
                  helper.setBlock(3, 1, 1, Blocks.AIR);
              })
              .thenIdle(5)
              .thenWaitUntil(() -> {
                  if (!matrix(helper).isFormed()) {
                      helper.fail("Induction matrix did not re-form");
                  }
              })
              .thenExecute(() -> {
                  IInventorySlot slot = inputSlot(helper);
                  if (!slot.resource().is(MekanismItems.ENERGY_TABLET.asItem()) || slot.amountAsLong() != 1) {
                      helper.fail("Induction matrix lost its input item after editing its inside, slot contains: " + slot.asStack());
                  }
              })
              .thenSucceed()
        );
    }
}
