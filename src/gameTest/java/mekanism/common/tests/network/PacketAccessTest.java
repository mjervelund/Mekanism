package mekanism.common.tests.network;

import mekanism.common.network.PacketUtils;
import mekanism.common.registries.MekanismBlocks;
import mekanism.common.tests.helpers.MekGameTestHelper;
import mekanism.common.util.WorldUtils;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.phys.Vec3;
import net.neoforged.testframework.DynamicTest;
import net.neoforged.testframework.annotation.ForEachTest;
import net.neoforged.testframework.annotation.TestHolder;
import net.neoforged.testframework.gametest.GameTest;
import net.neoforged.testframework.gametest.StructureTemplateBuilder;

@ForEachTest(groups = "network.security")
public class PacketAccessTest {

    @GameTest(setupTicks = SharedConstants.TICKS_PER_SECOND)
    @TestHolder(description = "Packets that target a block entity by a client supplied position only reach it when the player is in reach (or has its menu open), and never load chunks.")
    public static void testBlockEntityPacketReach(final DynamicTest test) {
        test.registerGameTestTemplate(() -> StructureTemplateBuilder.withSize(1, 1, 1)
              .fill(0, 0, 0, 0, 0, 0, MekanismBlocks.ENRICHMENT_CHAMBER.defaultState())
        );

        test.onGameTest(MekGameTestHelper.class, helper -> helper.startSequence()
              .thenExecute(() -> {
                  BlockPos machine = helper.absolutePos(BlockPos.ZERO);
                  BlockEntity tile = helper.getLevel().getBlockEntity(machine);
                  helper.assertTrue(tile != null, "machine has no block entity");

                  Player player = helper.makeMockPlayerLookingAt(BlockPos.ZERO, Direction.NORTH);
                  helper.assertTrue(PacketUtils.canUse(player, tile), "a player in reach of the machine was rejected");

                  player.snapTo(Vec3.atCenterOf(machine).add(0, 0, -64));
                  helper.assertFalse(PacketUtils.canUse(player, tile), "a player 64 blocks away was allowed to use the machine");

                  //Positions in unloaded chunks are never looked up, so a packet can't make the server load or generate them
                  BlockPos unloaded = machine.offset(100_000, 0, 100_000);
                  helper.assertTrue(WorldUtils.getTileEntity(helper.getLevel(), unloaded) == null, "got a block entity in an unloaded chunk");
                  helper.assertFalse(helper.getLevel().hasChunk(unloaded.getX() >> 4, unloaded.getZ() >> 4), "looking up a block entity loaded its chunk");
              })
              .thenSucceed()
        );
    }
}
