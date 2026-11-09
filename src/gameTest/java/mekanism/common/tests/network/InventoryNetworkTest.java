package mekanism.common.tests.network;

import java.util.function.Supplier;
import java.util.function.UnaryOperator;
import mekanism.api.text.EnumColor;
import mekanism.api.tier.AlloyTier;
import mekanism.common.content.network.transmitter.LogisticalTransporterBase;
import mekanism.common.content.transporter.TransporterStack;
import mekanism.common.tile.transmitter.TileEntityLogisticalTransporterBase;
import mekanism.common.content.network.transmitter.DiversionTransporter.DiversionControl;
import mekanism.common.lib.transmitter.ConnectionType;
import mekanism.common.registries.MekanismBlocks;
import mekanism.common.tests.MekanismTests;
import mekanism.common.tests.helpers.TransmitterTestHelper;
import mekanism.common.tests.util.StructureBuilderUtils;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BarrelBlockEntity;
import net.minecraft.world.level.block.entity.RandomizableContainerBlockEntity;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.capabilities.RegisterCapabilitiesEvent;
import net.neoforged.neoforge.registries.DeferredBlock;
import net.neoforged.neoforge.transfer.item.ItemResource;
import net.neoforged.testframework.DynamicTest;
import net.neoforged.testframework.annotation.ForEachTest;
import net.neoforged.testframework.annotation.RegisterStructureTemplate;
import net.neoforged.testframework.annotation.TestHolder;
import net.neoforged.testframework.gametest.GameTest;
import net.neoforged.testframework.gametest.StructureTemplateBuilder;
import net.neoforged.testframework.registration.RegistrationHelper;
import org.jspecify.annotations.Nullable;

@ForEachTest(groups = "network.inventory")
public class InventoryNetworkTest {

    //TODO: Can we somehow test the case of when we add a shorter path to the same destination, as the newly pulled items go via the new shorter path
    // but any already en-route ones continue the original way?
    //TODO: Write a test for the following
    // - Single transporter with no destination stays in the transporter, it fails and pops out if there is only a single connection point
    //   Note: We may want to change that behavior so even with a single connection point it just stays idling and only pops out if there are zero connection points
    // - Test machine transporter side config color is properly obeyed

    private static final String UPGRADEABLE = MekanismTests.MODID + ":upgradeable_transporter";
    //Note: Our template is lazy so that we ensure the transporters are registered
    @RegisterStructureTemplate(UPGRADEABLE)
    public static final Supplier<StructureTemplate> UPGRADEABLE_TEMPLATE = StructureTemplateBuilder.lazy(10, 1, 3, builder -> builder
          //Start barrel
          .set(3, 0, 0, Blocks.BARREL.defaultBlockState())
          //End barrels
          .set(2, 0, 0, Blocks.BARREL.defaultBlockState())
          .set(8, 0, 0, Blocks.BARREL.defaultBlockState())

          .set(3, 0, 1, MekanismBlocks.BASIC_LOGISTICAL_TRANSPORTER.defaultState(), StructureBuilderUtils.configured(Direction.NORTH))

          .fill(0, 0, 0, 1, 0, 0, MekanismBlocks.BASIC_LOGISTICAL_TRANSPORTER.defaultState())
          .set(0, 0, 1, MekanismBlocks.BASIC_LOGISTICAL_TRANSPORTER.defaultState())
          .fill(0, 0, 2, 9, 0, 2, MekanismBlocks.BASIC_LOGISTICAL_TRANSPORTER.defaultState())
          .fill(9, 0, 0, 9, 0, 1, MekanismBlocks.BASIC_LOGISTICAL_TRANSPORTER.defaultState())
    );
    private static final String SIMPLE_PATH = MekanismTests.MODID + ":simple_path";
    //Note: Our template is lazy so that we ensure the transporters are registered
    @RegisterStructureTemplate(SIMPLE_PATH)
    public static final Supplier<StructureTemplate> SIMPLE_PATH_TEMPLATE = StructureTemplateBuilder.lazy(1, 1, 6, builder -> builder
          //Start barrel
          .set(0, 0, 0, Blocks.BARREL.defaultBlockState())
          //End barrel
          .set(0, 0, 5, Blocks.BARREL.defaultBlockState())

          .set(0, 0, 1, MekanismBlocks.BASIC_LOGISTICAL_TRANSPORTER.defaultState(), StructureBuilderUtils.configured(Direction.NORTH))
          .fill(0, 0, 2, 0, 0, 4, MekanismBlocks.BASIC_LOGISTICAL_TRANSPORTER.defaultState())
    );

    @GameTest(template = SIMPLE_PATH, timeoutTicks = 10 * SharedConstants.TICKS_PER_SECOND, setupTicks = SharedConstants.TICKS_PER_SECOND)
    @TestHolder(description = "Tests that items will properly be sent back and inserted into their home if the destination is removed while the stacks are en-route.")
    public static void sendsBackToHome(final TransmitterTestHelper helper) {
        helper.startSequence()
              .thenExecute(() -> {
                  //Insert the items so that we only start processing once this test is started
                  helper.insertIntoContainer(0, 0, 0, Items.STONE);
                  //And reset the delay that might be present based on what tick we started ticking
                  helper.resetTransporterDelay(0, 0, 1);
              })
              //Wait a second for it to pull the item out, and remove the destination
              .thenExecuteAfter(SharedConstants.TICKS_PER_SECOND, () -> helper.setBlock(0, 0, 5, Blocks.AIR))
              //Make sure the start container is empty
              .thenExecute(() -> helper.assertContainerEmpty(0, 0, 0))
              //And then after a few seconds that the item has transferred back into the destination it was pulled from
              .thenExecuteAfter(6 * SharedConstants.TICKS_PER_SECOND, () -> helper.assertContainerContains(0, 0, 0, Items.STONE))
              .thenSucceed();
    }

    @GameTest(template = SIMPLE_PATH, timeoutTicks = 10 * SharedConstants.TICKS_PER_SECOND, setupTicks = SharedConstants.TICKS_PER_SECOND)
    @TestHolder(description = "Tests that items will properly be sent back and inserted into their home if the destination becomes inaccessible "
                              + "due to side changes while the stacks are en-route.")
    public static void sendsBackToHomeDisabled(final TransmitterTestHelper helper) {
        helper.startSequence()
              .thenExecute(() -> {
                  //Insert the items so that we only start processing once this test is started
                  helper.insertIntoContainer(0, 0, 0, Items.STONE);
                  //And reset the delay that might be present based on what tick we started ticking
                  helper.resetTransporterDelay(0, 0, 1);
              })
              //Wait a second for it to pull the item out, and disable the path to the destination
              .thenExecuteAfter(SharedConstants.TICKS_PER_SECOND, () -> helper.useConfigurator(0, 0, 4, Direction.SOUTH, 3))
              //Make sure the start container is empty
              .thenExecute(() -> helper.assertContainerEmpty(0, 0, 0))
              //And then after a few seconds that the item has transferred back into the destination it was pulled from
              .thenExecuteAfter(6 * SharedConstants.TICKS_PER_SECOND, () -> helper.assertContainerContains(0, 0, 0, Items.STONE))
              .thenSucceed();
    }

    @GameTest(template = SIMPLE_PATH, timeoutTicks = 10 * SharedConstants.TICKS_PER_SECOND, setupTicks = SharedConstants.TICKS_PER_SECOND)
    @TestHolder(description = "Tests that items will properly be sent back and inserted into their home if the destination becomes inaccessible "
                              + "due to a transporter color change while the stacks are en-route.")
    public static void sendsBackToHomeColorChanged(final TransmitterTestHelper helper) {
        helper.startSequence()
              .thenExecute(() -> {
                  //Insert the items so that we only start processing once this test is started
                  helper.insertIntoContainer(0, 0, 0, Items.STONE);
                  //And reset the delay that might be present based on what tick we started ticking
                  helper.resetTransporterDelay(0, 0, 1);
              })
              //Wait a second for it to pull the item out, and then color the path to the destination
              .thenExecuteAfter(SharedConstants.TICKS_PER_SECOND, () -> helper.useConfigurator(0, 0, 4, Direction.UP))
              //Make sure the start container is empty
              .thenExecute(() -> helper.assertContainerEmpty(0, 0, 0))
              //And then after a few seconds that the item has transferred back into the destination it was pulled from
              .thenExecuteAfter(5 * SharedConstants.TICKS_PER_SECOND, () -> helper.assertContainerContains(0, 0, 0, Items.STONE))
              .thenSucceed();
    }

    @GameTest(setupTicks = SharedConstants.TICKS_PER_SECOND)
    @TestHolder(description = "Tests that items will be able to continue to their destination if the color changes but is still valid for the en-route stack.")
    public static void colorChangesStillValid(final DynamicTest test) {
        test.registerGameTestTemplate(() -> StructureTemplateBuilder.withSize(1, 1, 6)
              //Start barrel
              .set(0, 0, 0, Blocks.BARREL.defaultBlockState())
              //End barrel
              .set(0, 0, 5, Blocks.BARREL.defaultBlockState())

              .set(0, 0, 1, MekanismBlocks.BASIC_LOGISTICAL_TRANSPORTER.defaultState(), StructureBuilderUtils.configured(EnumColor.BLACK, Direction.NORTH))
              .fill(0, 0, 2, 0, 0, 4, MekanismBlocks.BASIC_LOGISTICAL_TRANSPORTER.defaultState())
        );

        test.onGameTest(TransmitterTestHelper.class, helper -> helper.startSequence()
              .thenExecute(() -> {
                  //Insert the items so that we only start processing once this test is started
                  helper.insertIntoContainer(0, 0, 0, Items.STONE);
                  //And reset the delay that might be present based on what tick we started ticking
                  helper.resetTransporterDelay(0, 0, 1);
              })
              //Wait a second for it to pull the item out, and then color the path to the destination
              .thenExecuteAfter(SharedConstants.TICKS_PER_SECOND, () -> helper.useConfigurator(0, 0, 4, Direction.UP))
              //And then after a few seconds that the item has transferred to the destination
              .thenExecuteAfter(3 * SharedConstants.TICKS_PER_SECOND, () -> helper.assertContainerContains(0, 0, 5, Items.STONE))
              //And make sure the start container is empty
              .thenExecute(() -> helper.assertContainerEmpty(0, 0, 0))
              .thenSucceed()
        );
    }

    @GameTest(timeoutTicks = 15 * SharedConstants.TICKS_PER_SECOND, setupTicks = SharedConstants.TICKS_PER_SECOND)
    @TestHolder(description = "Tests that items will properly be sent back and inserted into their home if the destination fills up while the stacks are en-route. "
                              + "And then will be sent to the inventory when there is room again.")
    public static void sendsBackToHomeWhileFilled(final DynamicTest test) {
        test.registerGameTestTemplate(() -> StructureTemplateBuilder.withSize(1, 1, 6)
              //Start barrel
              .set(0, 0, 0, Blocks.BARREL.defaultBlockState())
              //End barrel
              .set(0, 0, 5, Blocks.BARREL.defaultBlockState(), StructureBuilderUtils.containing(Items.OAK_LOG.getDefaultInstance(), 26))

              .set(0, 0, 1, MekanismBlocks.BASIC_LOGISTICAL_TRANSPORTER.defaultState(), StructureBuilderUtils.configured(Direction.NORTH))
              .fill(0, 0, 2, 0, 0, 4, MekanismBlocks.BASIC_LOGISTICAL_TRANSPORTER.defaultState())
        );

        test.onGameTest(TransmitterTestHelper.class, helper -> helper.startSequence()
              .thenExecute(() -> {
                  //Insert the items so that we only start processing once this test is started
                  helper.insertIntoContainer(0, 0, 0, Items.STONE);
                  //And reset the delay that might be present based on what tick we started ticking
                  helper.resetTransporterDelay(0, 0, 1);
              })
              .thenMap(() -> helper.getBlockEntity(0, 0, 5, BarrelBlockEntity.class))
              //Wait a second for it to pull the item out, and fill the last slot of the barrel
              .thenExecuteAfter(SharedConstants.TICKS_PER_SECOND, barrel -> barrel.setItem(26, Items.OAK_LOG.getDefaultInstance()))
              //Make sure the start container is empty
              .thenExecute(() -> helper.assertContainerEmpty(0, 0, 0))
              //And then after a few seconds that the item has transferred back into the destination it was pulled from
              .thenExecuteAfter(6 * SharedConstants.TICKS_PER_SECOND, () -> helper.assertContainerContains(0, 0, 0, Items.STONE))
              //After we make it back, then allow sending the item for real by removing the item from the barrel
              .thenExecute(RandomizableContainerBlockEntity::clearContent)
              .thenExecuteAfter(SharedConstants.TICKS_PER_SECOND, () -> helper.assertContainerEmpty(0, 0, 0))
              .thenExecuteAfter(4 * SharedConstants.TICKS_PER_SECOND, () -> helper.assertContainerContains(0, 0, 5, Items.STONE))
              .thenSucceed()
        );
    }

    @GameTest(timeoutTicks = 10 * SharedConstants.TICKS_PER_SECOND, setupTicks = SharedConstants.TICKS_PER_SECOND)
    @TestHolder(description = "Tests that items will properly be sent back and inserted into their home if the destination becomes inaccessible "
                              + "due to a diversion transporter's power level changing while the stacks are en-route.")
    public static void sendsBackHomeDiversionDisabled(final DynamicTest test) {
        test.registerGameTestTemplate(() -> StructureTemplateBuilder.withSize(1, 2, 6)
              //Start barrel
              .set(0, 0, 0, Blocks.BARREL.defaultBlockState())
              //End barrel
              .set(0, 0, 5, Blocks.BARREL.defaultBlockState())

              .set(0, 0, 1, MekanismBlocks.BASIC_LOGISTICAL_TRANSPORTER.defaultState(), StructureBuilderUtils.configured(Direction.NORTH))
              .fill(0, 0, 2, 0, 0, 3, MekanismBlocks.BASIC_LOGISTICAL_TRANSPORTER.defaultState())
              .set(0, 0, 4, MekanismBlocks.DIVERSION_TRANSPORTER.defaultState(), StructureBuilderUtils.diversionMode(Direction.SOUTH, DiversionControl.LOW))
              .set(0, 1, 5, Blocks.STONE.defaultBlockState())
              .set(0, 1, 4, Blocks.LEVER.defaultBlockState())
        );

        test.onGameTest(TransmitterTestHelper.class, helper -> helper.startSequence()
              .thenExecute(() -> {
                  //Insert the items so that we only start processing once this test is started
                  helper.insertIntoContainer(0, 0, 0, Items.STONE);
                  //And reset the delay that might be present based on what tick we started ticking
                  helper.resetTransporterDelay(0, 0, 1);
              })
              //Wait a second for it to pull the item out, and then pull the lever that is controlling the diversion transporter
              .thenExecuteAfter(SharedConstants.TICKS_PER_SECOND, () -> helper.pullLever(0, 1, 4))
              //Make sure the start container is empty
              .thenExecute(() -> helper.assertContainerEmpty(0, 0, 0))
              //And then after a few seconds that the item has transferred back into the destination it was pulled from
              .thenExecuteAfter(6 * SharedConstants.TICKS_PER_SECOND, () -> helper.assertContainerContains(0, 0, 0, Items.STONE))
              .thenSucceed()
        );
    }

    @GameTest(timeoutTicks = 8 * SharedConstants.TICKS_PER_SECOND, setupTicks = SharedConstants.TICKS_PER_SECOND)
    @TestHolder(description = "Tests that items will properly get to their destination, even if one of the paths to them is disabled, but another exists.")
    public static void pathDisabledButStillHasPath(final DynamicTest test) {
        test.registerGameTestTemplate(() -> StructureTemplateBuilder.withSize(3, 1, 6)
              //Start barrel
              .set(0, 0, 0, Blocks.BARREL.defaultBlockState())
              //End barrels
              .set(0, 0, 5, Blocks.BARREL.defaultBlockState())

              .set(0, 0, 1, MekanismBlocks.BASIC_LOGISTICAL_TRANSPORTER.defaultState(), StructureBuilderUtils.configured(Direction.NORTH))
              .fill(0, 0, 2, 0, 0, 4, MekanismBlocks.BASIC_LOGISTICAL_TRANSPORTER.defaultState())
              .fill(1, 0, 4, 1, 0, 5, MekanismBlocks.BASIC_LOGISTICAL_TRANSPORTER.defaultState())
        );

        test.onGameTest(TransmitterTestHelper.class, helper -> helper.startSequence()
              .thenExecute(() -> {
                  //Insert the items so that we only start processing once this test is started
                  helper.insertIntoContainer(0, 0, 0, Items.STONE);
                  //And reset the delay that might be present based on what tick we started ticking
                  helper.resetTransporterDelay(0, 0, 1);
              })
              //Wait a second for it to pull the item out, and disable the base path to the destination
              .thenExecuteAfter(SharedConstants.TICKS_PER_SECOND, () -> helper.useConfigurator(0, 0, 4, Direction.SOUTH, 3))
              //Make sure the start container is empty
              .thenExecute(() -> helper.assertContainerEmpty(0, 0, 0))
              //And then after a few seconds that the item has transferred back into the destination it was pulled from
              .thenExecuteAfter(6 * SharedConstants.TICKS_PER_SECOND, () -> helper.assertContainerContains(0, 0, 5, Items.STONE))
              .thenSucceed()
        );
    }

    @GameTest(template = SIMPLE_PATH, timeoutTicks = 16 * SharedConstants.TICKS_PER_SECOND, setupTicks = SharedConstants.TICKS_PER_SECOND)
    @TestHolder(description = "Tests that idling items will properly find a destination if both the destination and source are removed "
                              + "and then later a new destination is added.")
    public static void findPathFromIdle(final TransmitterTestHelper helper) {
        helper.startSequence()
              .thenExecute(() -> {
                  //Insert the items so that we only start processing once this test is started
                  helper.insertIntoContainer(0, 0, 0, Items.STONE);
                  //And reset the delay that might be present based on what tick we started ticking
                  helper.resetTransporterDelay(0, 0, 1);
              })
              //Wait a second for it to pull the item out, and remove the destination
              .thenExecuteAfter(SharedConstants.TICKS_PER_SECOND, () -> helper.setBlock(0, 0, 5, Blocks.AIR))
              .thenExecute(() -> helper.setBlock(0, 0, 0, Blocks.AIR))
              .thenExecuteAfter(10 * SharedConstants.TICKS_PER_SECOND, () -> helper.setBlock(0, 0, 5, Blocks.BARREL))
              //And then after a few seconds that the item has transferred back into the destination it was pulled from
              .thenExecuteAfter(5 * SharedConstants.TICKS_PER_SECOND, () -> helper.assertContainerContains(0, 0, 5, Items.STONE))
              .thenSucceed();
    }

    @GameTest(timeoutTicks = 16 * SharedConstants.TICKS_PER_SECOND, setupTicks = SharedConstants.TICKS_PER_SECOND)
    @TestHolder(description = "Tests that any items that are currently en-route to a closer destination will recalculate their paths "
                              + "if the shorter destination is removed.")
    public static void shorterDestinationRemoved(final DynamicTest test) {
        test.registerGameTestTemplate(() -> StructureTemplateBuilder.withSize(3, 1, 6)
              //Start barrel
              .set(0, 0, 0, Blocks.BARREL.defaultBlockState())
              //End barrels
              .set(0, 0, 5, Blocks.BARREL.defaultBlockState())
              .set(2, 0, 2, Blocks.BARREL.defaultBlockState())

              .set(0, 0, 1, MekanismBlocks.BASIC_LOGISTICAL_TRANSPORTER.defaultState(), StructureBuilderUtils.configured(Direction.NORTH))
              .set(1, 0, 2, MekanismBlocks.BASIC_LOGISTICAL_TRANSPORTER.defaultState())
              .fill(0, 0, 2, 0, 0, 4, MekanismBlocks.BASIC_LOGISTICAL_TRANSPORTER.defaultState())
        );

        test.onGameTest(TransmitterTestHelper.class, helper -> helper.startSequence()
              .thenExecute(() -> {
                  //Insert the items so that we only start processing once this test is started
                  helper.insertIntoContainer(0, 0, 0, Items.STONE, 20);
                  //And reset the delay that might be present based on what tick we started ticking
                  helper.resetTransporterDelay(0, 0, 1);
              })
              //Wait a few seconds for it to pull some items out, and remove the transporter to the shorter destination
              .thenExecuteAfter(4 * SharedConstants.TICKS_PER_SECOND, () -> helper.setBlock(1, 0, 2, Blocks.AIR))
              //Validate original destination has expected count
              .thenExecute(() -> helper.assertContainerContains(2, 0, 2, Items.STONE, 3))
              //Validate that two items were dropped when the transporter was broken
              .thenExecute(() -> helper.assertItemEntityCountIs(Items.STONE, new BlockPos(1, 0, 2), 1, 1))
              //Validate new destination has expected count after we give some time for the items to transfer
              .thenExecuteAfter(11 * SharedConstants.TICKS_PER_SECOND, () -> helper.assertContainerContains(0, 0, 5, Items.STONE, 16))
              //Validate start is also empty
              .thenExecute(() -> helper.assertContainerEmpty(0, 0, 0))
              .thenSucceed()
        );
    }

    @GameTest(timeoutTicks = 15 * SharedConstants.TICKS_PER_SECOND, setupTicks = SharedConstants.TICKS_PER_SECOND)
    @TestHolder(description = "Tests that newly pulled items will go to the new destination that has a shorter path, "
                              + "but any items that were already en-route will continue to the destination they had already calculated.")
    public static void shorterNewDestination(final DynamicTest test) {
        test.registerGameTestTemplate(() -> StructureTemplateBuilder.withSize(3, 1, 6)
              //Start barrel
              .set(0, 0, 0, Blocks.BARREL.defaultBlockState())
              //End barrels
              .set(0, 0, 5, Blocks.BARREL.defaultBlockState())
              .set(2, 0, 2, Blocks.BARREL.defaultBlockState())

              .set(0, 0, 1, MekanismBlocks.BASIC_LOGISTICAL_TRANSPORTER.defaultState(), StructureBuilderUtils.configured(Direction.NORTH))
              .fill(0, 0, 2, 0, 0, 4, MekanismBlocks.BASIC_LOGISTICAL_TRANSPORTER.defaultState())
        );

        test.onGameTest(TransmitterTestHelper.class, helper -> helper.startSequence()
              .thenExecute(() -> {
                  //Insert the items so that we only start processing once this test is started
                  helper.insertIntoContainer(0, 0, 0, Items.STONE, 20);
                  //And reset the delay that might be present based on what tick we started ticking
                  helper.resetTransporterDelay(0, 0, 1);
              })
              //Wait a few seconds for it to pull some items out, and add a transporter to create a shorter destination
              .thenExecuteAfter(4 * SharedConstants.TICKS_PER_SECOND, () -> helper.setBlock(1, 0, 2, MekanismBlocks.BASIC_LOGISTICAL_TRANSPORTER.defaultState()))
              //Validate original destination has expected count
              .thenExecuteAfter(10 * SharedConstants.TICKS_PER_SECOND, () -> helper.assertContainerContains(0, 0, 5, Items.STONE, 8))
              //Validate new destination has expected count
              .thenExecute(() -> helper.assertContainerContains(2, 0, 2, Items.STONE, 12))
              //Validate start is also empty
              .thenExecute(() -> helper.assertContainerEmpty(0, 0, 0))
              .thenSucceed()
        );
    }

    @GameTest(timeoutTicks = 15 * SharedConstants.TICKS_PER_SECOND, setupTicks = SharedConstants.TICKS_PER_SECOND)
    @TestHolder(description = "Tests that newly pulled items will go to the new destination that has a shorter path now that it has been re-enabled, "
                              + "but any items that were already en-route will continue to the destination they had already calculated.")
    public static void shorterEnabledPath(final DynamicTest test) {
        test.registerGameTestTemplate(() -> StructureTemplateBuilder.withSize(3, 1, 6)
              //Start barrel
              .set(0, 0, 0, Blocks.BARREL.defaultBlockState())
              //End barrels
              .set(0, 0, 5, Blocks.BARREL.defaultBlockState())
              .set(2, 0, 2, Blocks.BARREL.defaultBlockState())

              .set(0, 0, 1, MekanismBlocks.BASIC_LOGISTICAL_TRANSPORTER.defaultState(), StructureBuilderUtils.configured(Direction.NORTH))
              .fill(0, 0, 2, 0, 0, 4, MekanismBlocks.BASIC_LOGISTICAL_TRANSPORTER.defaultState())

              .set(1, 0, 3, MekanismBlocks.BASIC_LOGISTICAL_TRANSPORTER.defaultState())
              .set(1, 0, 2, MekanismBlocks.BASIC_LOGISTICAL_TRANSPORTER.defaultState(), StructureBuilderUtils.configured(null, Direction.WEST, ConnectionType.NONE))
        );

        test.onGameTest(TransmitterTestHelper.class, helper -> helper.startSequence()
              .thenExecute(() -> {
                  //Insert the items so that we only start processing once this test is started
                  helper.insertIntoContainer(0, 0, 0, Items.STONE, 20);
                  //And reset the delay that might be present based on what tick we started ticking
                  helper.resetTransporterDelay(0, 0, 1);
              })
              //Wait a few seconds for it to pull some items out, re-enable a disabled path to make there be a shorter destination
              .thenExecuteAfter(4 * SharedConstants.TICKS_PER_SECOND, () -> helper.useConfigurator(1, 0, 2, Direction.WEST))
              //Validate original destination has expected count
              .thenExecuteAfter(10 * SharedConstants.TICKS_PER_SECOND, () -> helper.assertContainerContains(0, 0, 5, Items.STONE, 8))
              //Validate new destination has expected count
              .thenExecute(() -> helper.assertContainerContains(2, 0, 2, Items.STONE, 12))
              //Validate start is also empty
              .thenExecute(() -> helper.assertContainerEmpty(0, 0, 0))
              .thenSucceed()
        );
    }

    @GameTest(setupTicks = SharedConstants.TICKS_PER_SECOND)
    @TestHolder(description = "Tests that nothing changes as colorless transporter stacks cannot enter a colored transporter.")
    public static void colorlessIntoColor(final DynamicTest test) {
        test.registerGameTestTemplate(() -> StructureTemplateBuilder.withSize(2, 1, 2)
              //Start barrel
              .set(0, 0, 0, Blocks.BARREL.defaultBlockState())
              //End barrel
              .set(1, 0, 0, Blocks.BARREL.defaultBlockState())
              .set(0, 0, 1, MekanismBlocks.BASIC_LOGISTICAL_TRANSPORTER.defaultState(), StructureBuilderUtils.configured(Direction.NORTH))
              .set(1, 0, 1, MekanismBlocks.BASIC_LOGISTICAL_TRANSPORTER.defaultState(), StructureBuilderUtils.configured(EnumColor.BLACK))
        );

        //Note: We initialize the starting inventory above
        test.onGameTest(TransmitterTestHelper.class, helper -> helper.startSequence()
              .thenExecute(() -> {
                  //Insert the items so that we only start processing once this test is started
                  helper.insertIntoContainer(0, 0, 0, Items.STONE);
                  //And reset the delay that might be present based on what tick we started ticking
                  helper.resetTransporterDelay(0, 0, 1);
              })
              //Wait a little to validate nothing is happening
              //Validate original destination has the starting amount
              .thenExecuteAfter(2 * SharedConstants.TICKS_PER_SECOND, () -> helper.assertContainerContains(0, 0, 0, Items.STONE))
              //Validate new destination is still empty
              .thenExecute(() -> helper.assertContainerEmpty(1, 0, 0))
              .thenSucceed()
        );
    }

    @GameTest(setupTicks = SharedConstants.TICKS_PER_SECOND)
    @TestHolder(description = "Tests that the stack moves to the further chest as the closer one has the color not matching.")
    public static void colorMatches(final DynamicTest test) {
        test.registerGameTestTemplate(() -> StructureTemplateBuilder.withSize(3, 1, 4)
              //Start barrel
              .set(0, 0, 0, Blocks.BARREL.defaultBlockState())
              //End barrels
              .set(2, 0, 2, Blocks.BARREL.defaultBlockState())
              .set(2, 0, 3, Blocks.BARREL.defaultBlockState())

              .set(0, 0, 1, MekanismBlocks.BASIC_LOGISTICAL_TRANSPORTER.defaultState(), StructureBuilderUtils.configured(EnumColor.BLACK, Direction.NORTH))
              .set(1, 0, 2, MekanismBlocks.BASIC_LOGISTICAL_TRANSPORTER.defaultState(), StructureBuilderUtils.configured(EnumColor.DARK_BLUE))
              .set(1, 0, 3, MekanismBlocks.BASIC_LOGISTICAL_TRANSPORTER.defaultState(), StructureBuilderUtils.configured(EnumColor.BLACK))
              .fill(0, 0, 2, 0, 0, 3, MekanismBlocks.BASIC_LOGISTICAL_TRANSPORTER.defaultState())
        );

        test.onGameTest(TransmitterTestHelper.class, helper -> helper.startSequence()
              .thenExecute(() -> {
                  //Insert the items so that we only start processing once this test is started
                  helper.insertIntoContainer(0, 0, 0, Items.STONE, 2);
                  //And reset the delay that might be present based on what tick we started ticking
                  helper.resetTransporterDelay(0, 0, 1);
              })
              //Wait a few seconds for transferring to happen then validate stuff
              //Validate colored destination has expected count
              .thenExecuteAfter(5 * SharedConstants.TICKS_PER_SECOND, () -> helper.assertContainerContains(2, 0, 3, Items.STONE, 2))
              //Validate wrong color destination is empty
              .thenExecute(() -> helper.assertContainerEmpty(2, 0, 2))
              //Validate start is also empty
              .thenExecute(() -> helper.assertContainerEmpty(0, 0, 0))
              .thenSucceed()
        );
    }

    @GameTest(setupTicks = SharedConstants.TICKS_PER_SECOND)
    @TestHolder(description = "Tests that the stack moves to the closer destination as even though it is colorless and the further one has the original color, "
                              + "it is further away.")
    public static void colorIsNotPriority(final DynamicTest test) {
        test.registerGameTestTemplate(() -> StructureTemplateBuilder.withSize(2, 1, 4)
              //Start barrel
              .set(0, 0, 0, Blocks.BARREL.defaultBlockState())
              //End barrels
              .set(1, 0, 2, Blocks.BARREL.defaultBlockState())
              .set(1, 0, 3, Blocks.BARREL.defaultBlockState())

              .set(0, 0, 1, MekanismBlocks.BASIC_LOGISTICAL_TRANSPORTER.defaultState(), StructureBuilderUtils.configured(EnumColor.BLACK, Direction.NORTH))
              .set(0, 0, 2, MekanismBlocks.BASIC_LOGISTICAL_TRANSPORTER.defaultState())
              .set(0, 0, 3, MekanismBlocks.BASIC_LOGISTICAL_TRANSPORTER.defaultState(), StructureBuilderUtils.configured(EnumColor.BLACK))
        );

        test.onGameTest(TransmitterTestHelper.class, helper -> helper.startSequence()
              .thenExecute(() -> {
                  //Insert the items so that we only start processing once this test is started
                  helper.insertIntoContainer(0, 0, 0, Items.STONE, 2);
                  //And reset the delay that might be present based on what tick we started ticking
                  helper.resetTransporterDelay(0, 0, 1);
              })
              //Wait a few seconds for transferring to happen then validate stuff
              //Validate colored destination has expected count
              .thenExecuteAfter(5 * SharedConstants.TICKS_PER_SECOND, () -> helper.assertContainerContains(1, 0, 2, Items.STONE, 2))
              //Validate wrong color destination is empty
              .thenExecute(() -> helper.assertContainerEmpty(1, 0, 3))
              //Validate start is also empty
              .thenExecute(() -> helper.assertContainerEmpty(0, 0, 0))
              .thenSucceed()
        );
    }

    @GameTest(setupTicks = SharedConstants.TICKS_PER_SECOND)
    @TestHolder(description = "Tests that restrictive transporters are properly treated as lower priority.")
    public static void restrictiveIsLowPriority(final DynamicTest test) {
        test.registerGameTestTemplate(() -> StructureTemplateBuilder.withSize(2, 1, 6)
              //Start barrel
              .set(0, 0, 0, Blocks.BARREL.defaultBlockState())
              //End barrels
              .set(1, 0, 0, Blocks.BARREL.defaultBlockState())
              .set(0, 0, 5, Blocks.BARREL.defaultBlockState())

              .set(0, 0, 1, MekanismBlocks.BASIC_LOGISTICAL_TRANSPORTER.defaultState(), StructureBuilderUtils.configured(Direction.NORTH))
              .set(1, 0, 1, MekanismBlocks.RESTRICTIVE_TRANSPORTER.defaultState())

              .fill(0, 0, 2, 0, 0, 4, MekanismBlocks.BASIC_LOGISTICAL_TRANSPORTER.defaultState())
        );

        test.onGameTest(TransmitterTestHelper.class, helper -> helper.startSequence()
              .thenExecute(() -> {
                  //Insert the items so that we only start processing once this test is started
                  helper.insertIntoContainer(0, 0, 0, Items.STONE);
                  //And reset the delay that might be present based on what tick we started ticking
                  helper.resetTransporterDelay(0, 0, 1);
              })
              //Wait a few seconds for transferring to happen then validate stuff
              .thenExecuteAfter(5 * SharedConstants.TICKS_PER_SECOND, () -> helper.assertContainerContains(0, 0, 5, Items.STONE))
              .thenExecute(() -> helper.assertContainerEmpty(0, 0, 0))
              .thenExecute(() -> helper.assertContainerEmpty(1, 0, 0))
              .thenSucceed()
        );
    }

    @GameTest(template = UPGRADEABLE, timeoutTicks = 20 * SharedConstants.TICKS_PER_SECOND, setupTicks = SharedConstants.TICKS_PER_SECOND)
    @TestHolder(description = "Tests that newly pulled items will go to the destination that had its path upgraded, "
                              + "but any items that were already en-route will continue to the destination they had already calculated.")
    public static void upgradeFurtherPath(final TransmitterTestHelper helper) {
        helper.startSequence()
              .thenExecute(() -> {
                  //Insert the items so that we only start processing once this test is started
                  helper.insertIntoContainer(3, 0, 0, Items.STONE, 20);
                  //And reset the delay that might be present based on what tick we started ticking
                  helper.resetTransporterDelay(3, 0, 1);
              })
              //Wait a few seconds for it to pull some items out, and upgrade the transporter to the further destination
              .thenExecuteAfter(4 * SharedConstants.TICKS_PER_SECOND, () -> helper.applyAlloyUpgrade(new BlockPos(9, 0, 0), AlloyTier.INFUSED))
              //Wait a few seconds for transferring to happen then validate stuff
              //Validate original destination has expected count
              .thenExecuteAfter(13 * SharedConstants.TICKS_PER_SECOND, () -> helper.assertContainerContains(2, 0, 0, Items.STONE, 8))
              //Validate further but now "closer" destination has expected count
              .thenExecute(() -> helper.assertContainerContains(8, 0, 0, Items.STONE, 12))
              //Validate start is also empty
              .thenExecute(() -> helper.assertContainerEmpty(3, 0, 0))
              .thenSucceed();
    }

    @GameTest(template = UPGRADEABLE, timeoutTicks = 20 * SharedConstants.TICKS_PER_SECOND, setupTicks = SharedConstants.TICKS_PER_SECOND)
    @TestHolder(description = "Tests that newly pulled items will go to the destination that had its path upgraded, "
                              + "but any items that were already en-route will continue to the destination they had "
                              + "already calculated as the new destination is slightly \"closer\".")
    public static void upgradeFurtherOverlapping(final TransmitterTestHelper helper) {
        helper.startSequence()
              .thenExecute(() -> {
                  //Insert the items so that we only start processing once this test is started
                  helper.insertIntoContainer(3, 0, 0, Items.STONE, 20);
                  //And reset the delay that might be present based on what tick we started ticking
                  helper.resetTransporterDelay(3, 0, 1);
              })
              //Wait a few seconds for it to pull some items out, and upgrade the transporter to the further destination
              .thenExecuteAfter(4 * SharedConstants.TICKS_PER_SECOND, () -> helper.applyAlloyUpgrade(new BlockPos(6, 0, 2), AlloyTier.INFUSED))
              //Wait a few seconds for transferring to happen then validate stuff
              //Validate original destination has expected count
              .thenExecuteAfter(13 * SharedConstants.TICKS_PER_SECOND, () -> helper.assertContainerContains(2, 0, 0, Items.STONE, 8))
              //Validate further but now "closer" destination has expected count
              .thenExecute(() -> helper.assertContainerContains(8, 0, 0, Items.STONE, 12))
              //Validate start is also empty
              .thenExecute(() -> helper.assertContainerEmpty(3, 0, 0))
              .thenSucceed();
    }

    @GameTest(template = UPGRADEABLE, timeoutTicks = 12 * SharedConstants.TICKS_PER_SECOND, setupTicks = SharedConstants.TICKS_PER_SECOND)
    @TestHolder(description = "Tests that all items pre- and post-upgrade will go to the original destination.")
    public static void upgradeExisting(final TransmitterTestHelper helper) {
        helper.startSequence()
              .thenExecute(() -> {
                  //Insert the items so that we only start processing once this test is started
                  helper.insertIntoContainer(3, 0, 0, Items.STONE, 20);
                  //And reset the delay that might be present based on what tick we started ticking
                  helper.resetTransporterDelay(3, 0, 1);
              })
              //Wait a few seconds for it to pull some items out, and upgrade the transporter to the further destination
              .thenExecuteAfter(4 * SharedConstants.TICKS_PER_SECOND, () -> helper.applyAlloyUpgrade(new BlockPos(3, 0, 2), AlloyTier.INFUSED))
              //Wait a few seconds for transferring to happen then validate stuff
              //Validate original destination has expected count
              .thenExecuteAfter(6 * SharedConstants.TICKS_PER_SECOND, () -> helper.assertContainerContains(2, 0, 0, Items.STONE, 20))
              //Validate further is still empty
              .thenExecute(() -> helper.assertContainerEmpty(8, 0, 0))
              //Validate start is also empty
              .thenExecute(() -> helper.assertContainerEmpty(3, 0, 0))
              .thenSucceed();
    }

    @GameTest(timeoutTicks = 20 * SharedConstants.TICKS_PER_SECOND, setupTicks = SharedConstants.TICKS_PER_SECOND)
    @TestHolder(description = "Tests that diversion transporters can have the paths get switched.")
    public static void diversionSwitchPaths(final DynamicTest test) {
        test.registerGameTestTemplate(() -> StructureTemplateBuilder.withSize(3, 2, 6)
              //Start barrel
              .set(0, 0, 0, Blocks.BARREL.defaultBlockState())
              //End barrels
              .set(0, 0, 5, Blocks.BARREL.defaultBlockState())
              .set(2, 0, 2, Blocks.BARREL.defaultBlockState())

              .set(0, 0, 1, MekanismBlocks.BASIC_LOGISTICAL_TRANSPORTER.defaultState(), StructureBuilderUtils.configured(Direction.NORTH))
              .set(1, 0, 2, MekanismBlocks.BASIC_LOGISTICAL_TRANSPORTER.defaultState())
              .fill(0, 0, 3, 0, 0, 4, MekanismBlocks.BASIC_LOGISTICAL_TRANSPORTER.defaultState())
              .set(0, 0, 2, MekanismBlocks.DIVERSION_TRANSPORTER.defaultState(), StructureBuilderUtils.diversionModes(
                    DiversionControl.DISABLED,
                    DiversionControl.DISABLED,
                    DiversionControl.DISABLED,
                    DiversionControl.LOW,
                    DiversionControl.DISABLED,
                    DiversionControl.HIGH
              ))
              .set(0, 1, 3, Blocks.STONE.defaultBlockState())
              .set(0, 1, 2, Blocks.LEVER.defaultBlockState())
        );

        test.onGameTest(TransmitterTestHelper.class, helper -> helper.startSequence()
              .thenExecute(() -> {
                  //Insert the items so that we only start processing once this test is started
                  helper.insertIntoContainer(0, 0, 0, Items.STONE, 20);
                  //And reset the delay that might be present based on what tick we started ticking
                  helper.resetTransporterDelay(0, 0, 1);
              })
              //Wait a few seconds for it to pull some items out, and remove the transporter to the shorter destination
              .thenExecuteAfter(4 * SharedConstants.TICKS_PER_SECOND, () -> helper.pullLever(0, 1, 2))
              //Validate original destination has expected count
              .thenExecuteAfter(11 * SharedConstants.TICKS_PER_SECOND, () -> helper.assertContainerContains(0, 0, 5, Items.STONE, 4))
              //Validate new destination has expected count after we give some time for the items to transfer
              .thenExecute(() -> helper.assertContainerContains(2, 0, 2, Items.STONE, 16))
              //Validate start is also empty
              .thenExecute(() -> helper.assertContainerEmpty(0, 0, 0))
              .thenSucceed()
        );
    }

    @GameTest(timeoutTicks = 8 * SharedConstants.TICKS_PER_SECOND, setupTicks = SharedConstants.TICKS_PER_SECOND)
    @TestHolder(description = "Tests that blocks which proxy the item capability of an adjacent transporter back to it (such as capability extenders) "
                              + "don't cause infinite recursion when calculating paths, and that items still get to their real destination. "
                              + "See https://github.com/mekanism/Mekanism/issues/8446")
    public static void proxiedTransporterCapability(final DynamicTest test, final RegistrationHelper reg) {
        //A block that when queried for an item handler, hands back the item handler of the block that is querying it
        DeferredBlock<Block> mirror = reg.blocks().registerSimpleBlock("capability_mirror", UnaryOperator.identity());
        ProxyCapabilities.mirror = mirror;

        test.registerGameTestTemplate(() -> StructureTemplateBuilder.withSize(2, 1, 5)
              //Start barrel
              .set(0, 0, 0, Blocks.BARREL.defaultBlockState())
              //End barrel
              .set(0, 0, 4, Blocks.BARREL.defaultBlockState())

              .set(0, 0, 1, MekanismBlocks.BASIC_LOGISTICAL_TRANSPORTER.defaultState(), StructureBuilderUtils.configured(Direction.NORTH))
              .fill(0, 0, 2, 0, 0, 3, MekanismBlocks.BASIC_LOGISTICAL_TRANSPORTER.defaultState())
              //Two mirrors so that the acceptor skipped for being the stack's home doesn't hide the loop
              .fill(1, 0, 2, 1, 0, 3, mirror.get().defaultBlockState())
        );

        test.onGameTest(TransmitterTestHelper.class, helper -> helper.startSequence()
              .thenExecute(() -> {
                  //Insert the items so that we only start processing once this test is started
                  helper.insertIntoContainer(0, 0, 0, Items.STONE);
                  //And reset the delay that might be present based on what tick we started ticking
                  helper.resetTransporterDelay(0, 0, 1);
              })
              //Wait a few seconds for the item to transfer to the destination
              .thenExecuteAfter(4 * SharedConstants.TICKS_PER_SECOND, () -> helper.assertContainerContains(0, 0, 4, Items.STONE))
              .thenExecute(() -> helper.assertContainerEmpty(0, 0, 0))
              .thenSucceed()
        );
    }

    @GameTest(timeoutTicks = 12 * SharedConstants.TICKS_PER_SECOND, setupTicks = SharedConstants.TICKS_PER_SECOND)
    @TestHolder(description = "Tests that blocks which proxy the item capability of a transporter in one network to a transporter in another network "
                              + "and back again don't cause infinite recursion when calculating paths, and that items still get to their real destination. "
                              + "See https://github.com/mekanism/Mekanism/issues/8446")
    public static void proxiedTransporterCapabilityBetweenNetworks(final DynamicTest test, final RegistrationHelper reg) {
        //A block that when queried for an item handler, hands back the item handler of the block on its opposite side
        DeferredBlock<Block> passThrough = reg.blocks().registerSimpleBlock("capability_pass_through", UnaryOperator.identity());
        ProxyCapabilities.passThrough = passThrough;

        test.registerGameTestTemplate(() -> StructureTemplateBuilder.withSize(3, 1, 5)
              //Start barrel
              .set(0, 0, 0, Blocks.BARREL.defaultBlockState())
              //End barrel
              .set(0, 0, 4, Blocks.BARREL.defaultBlockState())

              .set(0, 0, 1, MekanismBlocks.BASIC_LOGISTICAL_TRANSPORTER.defaultState(), StructureBuilderUtils.configured(Direction.NORTH))
              .fill(0, 0, 2, 0, 0, 3, MekanismBlocks.BASIC_LOGISTICAL_TRANSPORTER.defaultState())
              //Connect the network to a second network and the second network back to the first one
              .fill(1, 0, 2, 1, 0, 3, passThrough.get().defaultBlockState())
              .fill(2, 0, 2, 2, 0, 3, MekanismBlocks.BASIC_LOGISTICAL_TRANSPORTER.defaultState())
        );

        test.onGameTest(TransmitterTestHelper.class, helper -> helper.startSequence()
              .thenExecute(() -> {
                  //Insert the items so that we only start processing once this test is started
                  helper.insertIntoContainer(0, 0, 0, Items.STONE);
                  //And reset the delay that might be present based on what tick we started ticking
                  helper.resetTransporterDelay(0, 0, 1);
              })
              //Wait a few seconds for the item to transfer to the destination
              .thenExecuteAfter(8 * SharedConstants.TICKS_PER_SECOND, () -> helper.assertContainerContains(0, 0, 4, Items.STONE))
              .thenExecute(() -> helper.assertContainerEmpty(0, 0, 0))
              .thenSucceed()
        );
    }

    //Note: Capabilities are registered before any tests are enabled, so we can't use the test's event listeners (which only fire while the test is enabled)
    @EventBusSubscriber(modid = MekanismTests.MODID)
    public static class ProxyCapabilities {

        @Nullable
        private static DeferredBlock<Block> mirror;
        @Nullable
        private static DeferredBlock<Block> passThrough;

        @SubscribeEvent
        public static void registerCapabilities(RegisterCapabilitiesEvent event) {
            if (mirror != null) {
                event.registerBlock(Capabilities.Item.BLOCK, (level, pos, state, blockEntity, side) ->
                      side == null ? null : level.getCapability(Capabilities.Item.BLOCK, pos.relative(side), side.getOpposite()), mirror.get());
            }
            if (passThrough != null) {
                event.registerBlock(Capabilities.Item.BLOCK, (level, pos, state, blockEntity, side) ->
                      side == null ? null : level.getCapability(Capabilities.Item.BLOCK, pos.relative(side.getOpposite()), side), passThrough.get());
            }
        }
    }

    @GameTest(setupTicks = SharedConstants.TICKS_PER_SECOND)
    @TestHolder(description = "Tests that the data synced to clients for a transporter holding a very large number of stacks stays bounded, "
                              + "so that the chunk packet does not grow until clients get a Network Protocol Error when joining. "
                              + "https://github.com/mekanism/Mekanism/issues/8279")
    public static void updateTagBoundedWithManyStacks(final DynamicTest test) {
        test.registerGameTestTemplate(() -> StructureTemplateBuilder.withSize(1, 1, 1)
              .set(0, 0, 0, MekanismBlocks.BASIC_LOGISTICAL_TRANSPORTER.defaultState())
        );

        test.onGameTest(TransmitterTestHelper.class, helper -> helper.startSequence()
              .thenExecute(() -> {
                  TileEntityLogisticalTransporterBase tile = helper.getBlockEntity(0, 0, 0, TileEntityLogisticalTransporterBase.class);
                  LogisticalTransporterBase transporter = tile.getTransmitter();
                  //Simulate stacks that accumulated in the transporter over a long time (for example ones that keep bouncing because their destination
                  // keeps getting filled by something else before they arrive)
                  int stacks = 10_000;
                  for (int i = 0; i < stacks; i++) {
                      TransporterStack stack = new TransporterStack();
                      stack.setStack(ItemResource.of(Items.STONE), 1);
                      transporter.addStack(i, stack);
                  }
                  CompoundTag updateTag = tile.getUpdateTag(helper.getLevel().registryAccess());
                  //Syncing must not touch the server side state
                  helper.assertValueEqual(transporter.getTransit().size(), stacks, "Stacks in transit after writing the update tag");
                  //Remove the stacks again before checking, so that they don't get processed (and dropped) after the test
                  for (int i = 0; i < stacks; i++) {
                      transporter.deleteStack(i);
                  }
                  int size = updateTag.sizeInBytes();
                  //One transporter's sync data must stay small (a few stacks are ~100 bytes each); unbounded it is ~1 MB here
                  int limit = 64 * 1024;
                  if (size > limit) {
                      helper.fail("Update tag of a transporter holding " + stacks + " stacks is " + size + " bytes, expected at most " + limit, BlockPos.ZERO);
                  }
              })
              .thenSucceed()
        );
    }
}
