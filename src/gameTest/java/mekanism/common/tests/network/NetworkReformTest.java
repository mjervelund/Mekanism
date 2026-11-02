package mekanism.common.tests.network;

import com.mojang.authlib.GameProfile;
import io.netty.channel.embedded.EmbeddedChannel;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Supplier;
import mekanism.common.Mekanism;
import mekanism.common.content.network.EnergyNetwork;
import mekanism.common.network.to_client.PacketUpdateTile;
import mekanism.common.registries.MekanismBlocks;
import mekanism.common.tests.MekanismTests;
import mekanism.common.tests.helpers.MekGameTestHelper;
import mekanism.common.tile.transmitter.TileEntityUniversalCable;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.BundlePacket;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.testframework.DynamicTest;
import net.neoforged.testframework.annotation.ForEachTest;
import net.neoforged.testframework.annotation.RegisterStructureTemplate;
import net.neoforged.testframework.annotation.TestHolder;
import net.neoforged.testframework.gametest.GameTest;
import net.neoforged.testframework.gametest.StructureTemplateBuilder;

/// Reproduces a large cable network splitting and merging (a cable broken and replaced) with a player tracking it, and measures the post-tick handler time
/// and the transmitter update payloads the player receives.
@ForEachTest(groups = "network.transmitter")
public class NetworkReformTest {

    private static final int SIZE = 100;
    private static final String SERPENTINE = MekanismTests.MODID + ":cable_serpentine";
    //One cable path of 100 rows of 50, joined at alternating ends: 5049 cables
    @RegisterStructureTemplate(SERPENTINE)
    public static final Supplier<StructureTemplate> SERPENTINE_TEMPLATE = StructureTemplateBuilder.lazy(SIZE, 1, SIZE, builder -> {
        for (int z = 0; z < SIZE; z += 2) {
            builder.fill(0, 0, z, SIZE - 1, 0, z, MekanismBlocks.BASIC_UNIVERSAL_CABLE.defaultState());
            if (z + 1 < SIZE - 1) {
                int x = (z / 2) % 2 == 0 ? SIZE - 1 : 0;
                builder.fill(x, 0, z + 1, x, 0, z + 1, MekanismBlocks.BASIC_UNIVERSAL_CABLE.defaultState());
            }
        }
        return builder;
    });
    private static final BlockPos BREAK = new BlockPos(SIZE / 2, 0, SIZE / 2);

    private static final class Probe {

        private final EmbeddedChannel channel;
        private final ServerPlayer player;
        private long tickStart;
        private long maxPostNanos;
        private long totalPostNanos;

        private Probe(ServerPlayer player, EmbeddedChannel channel) {
            this.player = player;
            this.channel = channel;
        }

        private void reset() {
            maxPostNanos = 0;
            totalPostNanos = 0;
            channel.outboundMessages().clear();
        }

        /// @return {transmitter update payloads, of which batched, positions updated}
        private int[] countUpdates() {
            int[] counts = new int[3];
            for (Object message : channel.outboundMessages()) {
                if (message instanceof Packet<?> packet) {
                    count(packet, counts);
                }
            }
            return counts;
        }

        private static void count(Packet<?> packet, int[] counts) {
            if (packet instanceof BundlePacket<?> bundle) {
                for (Packet<?> sub : bundle.subPackets()) {
                    count(sub, counts);
                }
            } else if (packet instanceof ClientboundCustomPayloadPacket(CustomPacketPayload payload)) {
                if (payload.type() == PacketUpdateTile.TYPE) {
                    counts[0]++;
                    counts[2]++;
                } else {
                    int batched = batchedPositions(payload);
                    if (batched >= 0) {
                        counts[0]++;
                        counts[1]++;
                        counts[2] += batched;
                    }
                }
            }
        }
    }

    /// Positions in a batched transmitter update payload, or -1 if the payload is not one.
    private static int batchedPositions(CustomPacketPayload payload) {
        return -1;
    }

    private static Probe addTrackingPlayer(MekGameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        CommonListenerCookie cookie = CommonListenerCookie.createInitial(new GameProfile(UUID.randomUUID(), "reform-probe"), false);
        ServerPlayer player = new ServerPlayer(level.getServer(), level, cookie.gameProfile(), cookie.clientInformation()) {
            @Override
            public GameType gameMode() {
                return GameType.SPECTATOR;
            }
        };
        Connection connection = new Connection(PacketFlow.SERVERBOUND);
        EmbeddedChannel channel = new EmbeddedChannel(connection);
        level.getServer().getPlayerList().placeNewPlayer(connection, player, cookie);
        BlockPos center = helper.absolutePos(BREAK);
        player.teleportTo(center.getX() + 0.5, center.getY() + 3, center.getZ() + 0.5);
        level.getChunkSource().move(player);
        return new Probe(player, channel);
    }

    @GameTest(template = SERPENTINE, setupTicks = 2 * SharedConstants.TICKS_PER_SECOND, timeoutTicks = 60 * SharedConstants.TICKS_PER_SECOND, batch = "reform")
    @TestHolder(description = "A 5049 cable network split by breaking one cable and merged by replacing it ends with one network again; logs the post"
                              + " tick handler time and the update payloads a tracking player receives.")
    public static void splitAndMergeWithPlayer(final DynamicTest test) {
        Probe[] probe = new Probe[1];
        Consumer<ServerTickEvent.Pre> ack = event -> {
            if (probe[0] != null) {
                //Acknowledge chunk batches like a client so that the cable chunks get sent and count as tracked
                probe[0].player.connection.chunkSender.onChunkBatchReceivedByClient(64);
            }
        };
        Consumer<ServerTickEvent.Post> start = event -> {
            if (probe[0] != null) {
                probe[0].tickStart = System.nanoTime();
            }
        };
        Consumer<ServerTickEvent.Post> end = event -> {
            if (probe[0] != null) {
                long nanos = System.nanoTime() - probe[0].tickStart;
                probe[0].totalPostNanos += nanos;
                probe[0].maxPostNanos = Math.max(probe[0].maxPostNanos, nanos);
            }
        };
        NeoForge.EVENT_BUS.addListener(EventPriority.HIGHEST, false, ServerTickEvent.Pre.class, ack);
        NeoForge.EVENT_BUS.addListener(EventPriority.HIGHEST, false, ServerTickEvent.Post.class, start);
        NeoForge.EVENT_BUS.addListener(EventPriority.LOWEST, false, ServerTickEvent.Post.class, end);
        Runnable cleanup = () -> {
            NeoForge.EVENT_BUS.unregister(ack);
            NeoForge.EVENT_BUS.unregister(start);
            NeoForge.EVENT_BUS.unregister(end);
            if (probe[0] != null) {
                probe[0].player.level().getServer().getPlayerList().remove(probe[0].player);
                probe[0].channel.finishAndReleaseAll();
                probe[0] = null;
            }
        };
        test.onGameTest(MekGameTestHelper.class, helper -> helper.startSequence()
              .thenExecute(() -> probe[0] = addTrackingPlayer(helper))
              .thenIdle(3 * SharedConstants.TICKS_PER_SECOND)
              .thenExecute(() -> {
                  helper.assertTrue(helper.getLevel().getChunkSource().chunkMap.isChunkTracked(probe[0].player,
                        helper.absolutePos(BREAK).getX() >> 4, helper.absolutePos(BREAK).getZ() >> 4), "probe tracks the cable chunk");
                  assertOneNetwork(helper, "before");
                  probe[0].reset();
                  helper.setBlock(BREAK, Blocks.AIR.defaultBlockState());
              })
              .thenIdle(5)
              .thenExecute(() -> {
                  report("split", probe[0]);
                  EnergyNetwork a = network(helper, BREAK.west());
                  EnergyNetwork b = network(helper, BREAK.east());
                  helper.assertTrue(a != null && b != null && a != b, "split into two networks");
                  probe[0].reset();
                  helper.setBlock(BREAK, MekanismBlocks.BASIC_UNIVERSAL_CABLE.defaultState());
              })
              .thenIdle(5)
              .thenExecute(() -> {
                  report("merge", probe[0]);
                  assertOneNetwork(helper, "after merge");
                  cleanup.run();
              })
              .thenSucceed()
        );
    }

    private static void report(String phase, Probe probe) {
        int[] counts = probe.countUpdates();
        Mekanism.logger.info("NetworkReformTest {}: post handlers max {} ms / total {} ms over 5 ticks; transmitter update payloads {} (batched {}),"
                             + " positions {}", phase, probe.maxPostNanos / 1_000_000.0, probe.totalPostNanos / 1_000_000.0, counts[0], counts[1], counts[2]);
    }

    private static EnergyNetwork network(MekGameTestHelper helper, BlockPos pos) {
        return helper.getBlockEntity(pos, TileEntityUniversalCable.class).getTransmitter().getTransmitterNetwork();
    }

    private static void assertOneNetwork(MekGameTestHelper helper, String phase) {
        EnergyNetwork first = network(helper, new BlockPos(0, 0, 0));
        EnergyNetwork last = network(helper, new BlockPos(SIZE - 1, 0, SIZE - 2));
        helper.assertTrue(first != null && first == last, "one network " + phase);
        helper.assertValueEqual(first.transmittersSize(), 5049, "network size " + phase);
    }
}
