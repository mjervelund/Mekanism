package mekanism.common.tests.multiblock;

import mekanism.api.chemical.ChemicalResource;
import mekanism.common.tests.helpers.MekGameTestHelper;
import mekanism.generators.common.config.MekanismGeneratorsConfig;
import mekanism.generators.common.content.fusion.FusionReactorMultiblockData;
import mekanism.generators.common.registries.GeneratorsBlocks;
import mekanism.generators.common.tile.fusion.TileEntityFusionReactorController;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.Fluids;
import net.neoforged.neoforge.transfer.fluid.FluidResource;
import net.neoforged.testframework.DynamicTest;
import net.neoforged.testframework.annotation.ForEachTest;
import net.neoforged.testframework.annotation.TestHolder;
import net.neoforged.testframework.gametest.GameTest;
import net.neoforged.testframework.gametest.StructureTemplateBuilder;

@ForEachTest(groups = "multiblock.fusion")
public class FusionReactorTest {

    private static final int SIZE = 5;
    //Mirrors FusionReactorValidator's ALLOWED_GRID: 0 = not required, anything else needs a casing block
    private static final byte[][] GRID = {
          {0, 0, 1, 0, 0},
          {0, 1, 2, 1, 0},
          {1, 2, 2, 2, 1},
          {0, 1, 2, 1, 0},
          {0, 0, 1, 0, 0}
    };
    //Relative position of the frame block we break and replace to force a reform from the cache
    private static final int REFORM_X = 0, REFORM_Y = 2, REFORM_Z = 2;

    private static boolean isOnBoundary(int coord) {
        return coord == 0 || coord == SIZE - 1;
    }

    private static boolean isCasing(int x, int y, int z) {
        boolean onX = isOnBoundary(x), onY = isOnBoundary(y), onZ = isOnBoundary(z);
        if (!onX && !onY && !onZ) {
            //Interior
            return false;
        }
        return (!onX || GRID[y][z] != 0) && (!onY || GRID[x][z] != 0) && (!onZ || GRID[x][y] != 0);
    }

    private static void registerReactor(DynamicTest test) {
        test.registerGameTestTemplate(() -> {
            StructureTemplateBuilder builder = StructureTemplateBuilder.withSize(SIZE, SIZE, SIZE);
            BlockState frame = GeneratorsBlocks.FUSION_REACTOR_FRAME.defaultState();
            for (int x = 0; x < SIZE; x++) {
                for (int y = 0; y < SIZE; y++) {
                    for (int z = 0; z < SIZE; z++) {
                        if (isCasing(x, y, z)) {
                            builder.set(x, y, z, frame);
                        }
                    }
                }
            }
            //The controller has to be in the center of the top face
            builder.set(2, SIZE - 1, 2, GeneratorsBlocks.FUSION_REACTOR_CONTROLLER.defaultState());
            return builder;
        });
    }

    private static FusionReactorMultiblockData formedReactor(MekGameTestHelper helper) {
        FusionReactorMultiblockData multiblock = helper.getBlockEntity(2, SIZE - 1, 2, TileEntityFusionReactorController.class).getMultiblock();
        helper.assertTrue(multiblock.isFormed(), "Expected the fusion reactor to be formed");
        return multiblock;
    }

    private static void assertCoolingCapacity(MekGameTestHelper helper, FusionReactorMultiblockData multiblock, String when) {
        int rate = multiblock.getInjectionRate();
        helper.assertValueEqual(multiblock.waterTank.capacityAsLong(FluidResource.of(Fluids.WATER)),
              rate * MekanismGeneratorsConfig.generators.fusionWaterPerInjection.get(), "water tank capacity " + when + " (injection rate " + rate + ")");
        helper.assertValueEqual(multiblock.steamTank.capacityAsLong(ChemicalResource.EMPTY),
              rate * MekanismGeneratorsConfig.generators.fusionSteamPerInjection.get(), "steam tank capacity " + when + " (injection rate " + rate + ")");
    }

    /// Represents [Issue 7977](https://github.com/mekanism/Mekanism/issues/7977): the water and steam tanks of a fusion reactor had no capacity
    /// (so it could not be water cooled) until the injection rate was changed away from the value the multiblock was formed with.
    @GameTest
    @TestHolder(description = "Tests that a newly formed fusion reactor has water and steam capacity for its default injection rate.")
    public static void coolingCapacityOnForm(final DynamicTest test) {
        registerReactor(test);
        test.onGameTest(MekGameTestHelper.class, helper -> helper.startSequence()
              .thenWaitUntil(() -> formedReactor(helper))
              .thenExecute(() -> {
                  FusionReactorMultiblockData multiblock = formedReactor(helper);
                  helper.assertValueEqual(multiblock.getInjectionRate(), FusionReactorMultiblockData.DEFAULT_INJECTION_RATE, "injection rate");
                  assertCoolingCapacity(helper, multiblock, "after forming");
              })
              .thenSucceed()
        );
    }

    /// Represents [Issue 7977](https://github.com/mekanism/Mekanism/issues/7977) for a reactor that reforms from its cache (as happens when the
    /// structure is broken and rebuilt, or when it is loaded again after a restart).
    @GameTest
    @TestHolder(description = "Tests that a fusion reactor reformed from its cache keeps water and steam capacity.")
    public static void coolingCapacityOnReform(final DynamicTest test) {
        registerReactor(test);
        test.onGameTest(MekGameTestHelper.class, helper -> helper.startSequence()
              .thenWaitUntil(() -> formedReactor(helper))
              //Change the injection rate and back, so that the tanks get a capacity regardless of the form behaviour
              .thenExecute(() -> {
                  FusionReactorMultiblockData multiblock = formedReactor(helper);
                  multiblock.setInjectionRate(10);
                  multiblock.setInjectionRate(FusionReactorMultiblockData.DEFAULT_INJECTION_RATE);
                  assertCoolingCapacity(helper, multiblock, "after changing the injection rate");
              })
              //Wait so the cache gets synced, then break the structure
              .thenExecuteAfter(5, () -> helper.setBlock(REFORM_X, REFORM_Y, REFORM_Z, Blocks.AIR))
              .thenExecuteAfter(5, () -> helper.setBlock(REFORM_X, REFORM_Y, REFORM_Z, GeneratorsBlocks.FUSION_REACTOR_FRAME.defaultState()))
              .thenWaitUntil(() -> formedReactor(helper))
              .thenExecute(() -> assertCoolingCapacity(helper, formedReactor(helper), "after reforming"))
              .thenSucceed()
        );
    }
}
