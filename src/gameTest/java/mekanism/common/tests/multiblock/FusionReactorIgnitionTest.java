package mekanism.common.tests.multiblock;

import mekanism.api.chemical.ChemicalIds;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import mekanism.api.chemical.ChemicalResource;
import mekanism.api.chemical.IChemicalTank;
import mekanism.common.lib.multiblock.MultiblockCache;
import mekanism.common.tests.helpers.MekGameTestHelper;
import mekanism.common.util.ChemicalUtils;
import mekanism.generators.common.content.fusion.FusionReactorMultiblockData;
import mekanism.generators.common.registries.GeneratorsBlocks;
import mekanism.generators.common.tile.fusion.TileEntityFusionReactorController;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.transfer.transaction.Transaction;
import net.neoforged.testframework.DynamicTest;
import net.neoforged.testframework.annotation.ForEachTest;
import net.neoforged.testframework.annotation.TestHolder;
import net.neoforged.testframework.gametest.GameTest;
import net.neoforged.testframework.gametest.StructureTemplateBuilder;

@ForEachTest(groups = "multiblock.fusion")
public class FusionReactorIgnitionTest {

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
    //Well above the ignition temperature, as in a running reactor
    private static final double HOT_PLASMA = 3 * FusionReactorMultiblockData.BURN_TEMPERATURE;
    private static final int FUEL = 1_000;

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

    private static void setPlasma(FusionReactorMultiblockData multiblock, double temperature) {
        try (Transaction transaction = Transaction.openRoot()) {
            multiblock.setPlasmaTemp(temperature, transaction);
            transaction.commit();
        }
        multiblock.updateTemperatures();
    }

    private static void fillFuel(MekGameTestHelper helper, FusionReactorMultiblockData multiblock) {
        multiblock.deuteriumTank.setContents(ChemicalUtils.getResource(helper.getLevel().registryAccess(), ChemicalIds.DEUTERIUM), FUEL, null);
        multiblock.tritiumTank.setContents(ChemicalUtils.getResource(helper.getLevel().registryAccess(), ChemicalIds.TRITIUM), FUEL, null);
    }

    /// Represents [Issue 7957](https://github.com/mekanism/Mekanism/issues/7957): a running fusion reactor was no longer ignited after a restart. When it
    /// reforms from its cache, its fuel supply (pipes, other chunks, quantum entangloporters) can arrive a few ticks after its first tick; with no fuel to burn
    /// on that tick the reactor turned its ignition off, although the plasma was still far above the ignition temperature, and needed a new hohlraum.
    @GameTest(timeoutTicks = 200)
    @TestHolder(description = "Tests that a hot fusion reactor that reforms (as after a restart) keeps burning when its fuel arrives a few ticks late.")
    public static void ignitionKeptAfterReform(final DynamicTest test) {
        registerReactor(test);
        AtomicReference<UUID> cacheId = new AtomicReference<>();
        test.onGameTest(MekGameTestHelper.class, helper -> helper.startSequence()
              .thenWaitUntil(() -> formedReactor(helper))
              //Ignite the reactor and let it run on fuel
              .thenExecute(() -> {
                  FusionReactorMultiblockData multiblock = formedReactor(helper);
                  setPlasma(multiblock, HOT_PLASMA);
                  multiblock.setBurning(true);
                  fillFuel(helper, multiblock);
              })
              .thenExecuteAfter(5, () -> {
                  FusionReactorMultiblockData multiblock = formedReactor(helper);
                  helper.assertTrue(multiblock.isBurning(), "Expected the fueled reactor to be burning");
                  helper.assertTrue(multiblock.deuteriumTank.amountAsLong() < FUEL, "Expected the reactor to be consuming deuterium");
                  helper.assertTrue(multiblock.inventoryID != null, "Expected the formed reactor to have a cache id");
                  cacheId.set(multiblock.inventoryID);
                  //Break the structure so that it reforms from its cache, which is what happens when it is loaded after a restart
                  helper.setBlock(REFORM_X, REFORM_Y, REFORM_Z, Blocks.AIR);
              })
              .thenExecuteAfter(5, () -> {
                  TileEntityFusionReactorController controller = helper.getBlockEntity(2, SIZE - 1, 2, TileEntityFusionReactorController.class);
                  helper.assertFalse(controller.getMultiblock().isFormed(), "Expected the fusion reactor to be unformed");
                  MultiblockCache<FusionReactorMultiblockData> cache = controller.getManager().getCache(cacheId.get());
                  helper.assertTrue(cache != null, "Expected the reactor's cache to exist");
                  //The fuel that was buffered when the world was saved has been used up; the supply is not there yet when the structure comes back
                  for (IChemicalTank tank : cache.getChemicalTanks()) {
                      tank.setContents(ChemicalResource.EMPTY, 0, null);
                  }
                  helper.setBlock(REFORM_X, REFORM_Y, REFORM_Z, GeneratorsBlocks.FUSION_REACTOR_FRAME.defaultState());
              })
              .thenWaitUntil(() -> formedReactor(helper))
              //The fuel supply arrives a few ticks after the reactor's first tick
              .thenExecuteAfter(3, () -> {
                  FusionReactorMultiblockData multiblock = formedReactor(helper);
                  helper.assertTrue(multiblock.deuteriumTank.isEmpty() && multiblock.fuelTank.isEmpty(), "Expected the reformed reactor to have no fuel yet");
                  helper.assertTrue(multiblock.getPlasmaTemp() >= FusionReactorMultiblockData.BURN_TEMPERATURE,
                        "Expected the plasma to still be above the ignition temperature, was " + multiblock.getPlasmaTemp());
                  helper.assertTrue(multiblock.isBurning(), "Expected the reformed reactor to still be ignited while its plasma is above the ignition temperature");
                  fillFuel(helper, multiblock);
              })
              .thenExecuteAfter(5, () -> {
                  FusionReactorMultiblockData multiblock = formedReactor(helper);
                  helper.assertTrue(multiblock.isBurning(), "Expected the reformed reactor to be burning again once its fuel arrived");
                  helper.assertTrue(multiblock.deuteriumTank.amountAsLong() < FUEL, "Expected the reformed reactor to consume the fuel that arrived");
              })
              .thenSucceed()
        );
    }

    /// Guard for the fix of [Issue 7957](https://github.com/mekanism/Mekanism/issues/7957): ignition is only kept while the plasma is hot enough to burn, a
    /// reactor whose plasma is below the ignition temperature still loses its ignition (no free restart from cold).
    @GameTest
    @TestHolder(description = "Tests that a fusion reactor whose plasma is below the ignition temperature does not stay ignited.")
    public static void ignitionLostWhenCold(final DynamicTest test) {
        registerReactor(test);
        test.onGameTest(MekGameTestHelper.class, helper -> helper.startSequence()
              .thenWaitUntil(() -> formedReactor(helper))
              .thenExecute(() -> {
                  FusionReactorMultiblockData multiblock = formedReactor(helper);
                  setPlasma(multiblock, FusionReactorMultiblockData.BURN_TEMPERATURE / 2);
                  multiblock.setBurning(true);
                  fillFuel(helper, multiblock);
              })
              .thenExecuteAfter(3, () -> helper.assertFalse(formedReactor(helper).isBurning(), "Expected a reactor below the ignition temperature to not be ignited"))
              .thenSucceed()
        );
    }
}
