package mekanism.common.tests.network;

import it.unimi.dsi.fastutil.longs.LongLinkedOpenHashSet;
import it.unimi.dsi.fastutil.objects.ReferenceOpenHashSet;
import java.util.Set;
import java.util.function.Supplier;
import mekanism.api.SerializationConstants;
import mekanism.api.functions.ConstantPredicates;
import mekanism.common.content.network.EnergyNetwork;
import mekanism.common.content.network.transmitter.UniversalCable;
import mekanism.common.lib.transmitter.TransmitterNetworkRegistry;
import mekanism.common.registries.MekanismBlocks;
import mekanism.common.tests.MekanismTests;
import mekanism.common.tests.helpers.MekGameTestHelper;
import mekanism.common.tile.transmitter.TileEntityUniversalCable;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ChunkHolder;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.DistanceManager;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import net.neoforged.neoforge.event.level.ChunkEvent;
import net.neoforged.testframework.DynamicTest;
import net.neoforged.testframework.annotation.ForEachTest;
import net.neoforged.testframework.annotation.RegisterStructureTemplate;
import net.neoforged.testframework.annotation.TestHolder;
import net.neoforged.testframework.gametest.GameTest;
import net.neoforged.testframework.gametest.StructureTemplateBuilder;

/// Energy networks mark their chunks unsaved only now and then (DynamicBufferedNetwork#limitsSaveDirtying); these tests check that the energy a
/// network stores still survives saving, unloading and reloading exactly, and that splits and the final save of a stopping server still mark.
///
/// Note: The chunks are taken from the cables' own positions, as the structure is not aligned to chunks.
@ForEachTest(groups = "network.transmitter.save_share")
public class CableSaveShareTest {

    private static final int LENGTH = 3 * 16;
    private static final int MIDDLE = LENGTH / 2;
    private static final String CABLE_LINE = MekanismTests.MODID + ":save_share_cable_line";
    //Note: Our template is lazy so that we ensure the universal cable is registered
    @RegisterStructureTemplate(CABLE_LINE)
    public static final Supplier<StructureTemplate> CABLE_LINE_TEMPLATE = StructureTemplateBuilder.lazy(LENGTH, 1, 1,
          builder -> builder.fill(0, 0, 0, LENGTH - 1, 0, 0, MekanismBlocks.BASIC_UNIVERSAL_CABLE.defaultState())
    );

    @GameTest(template = CABLE_LINE, setupTicks = SharedConstants.TICKS_PER_SECOND, timeoutTicks = 15 * SharedConstants.TICKS_PER_SECOND, batch = "save_share_1")
    @TestHolder(description = "Tests that a cable network keeps exactly its energy when part of it is saved with older shares, then unloaded and reloaded.")
    public static void unloadReloadKeepsEnergy(final DynamicTest test) {
        final ChunkUnloader unloader = new ChunkUnloader();
        test.eventListeners().forge().addListener((final ChunkEvent.Unload event) -> unloader.onChunkEvent(event, false));
        test.eventListeners().forge().addListener((final ChunkEvent.Load event) -> unloader.onChunkEvent(event, true));
        final long[] expected = new long[1];

        test.onGameTest(MekGameTestHelper.class, helper -> helper.startSequence()
              //Store half the capacity and save every chunk of the line (like an autosave), so that each chunk stores its share of that amount
              .thenExecute(() -> {
                  EnergyNetwork network = network(helper, 0);
                  network.energyContainer.setEnergy(network.getCapacity() / 2, null);
                  saveAll(helper);
                  assertChunksSaved(helper, "after the save");
              })
              //Change the energy by less than a quarter of the capacity: the chunks are not marked again, so they keep their older shares on disk
              .thenExecuteAfter(1, () -> {
                  EnergyNetwork network = network(helper, 0);
                  expected[0] = network.getCapacity() / 2 - network.getCapacity() / 10;
                  network.energyContainer.setEnergy(expected[0], null);
              })
              .thenExecuteAfter(1, () -> assertChunksSaved(helper, "after a small change of the stored energy"))
              //Unload the middle chunk: it must save the shares its cables take out of the network, not the older ones
              .thenExecute(() -> unloader.unload(helper, chunkOf(helper, MIDDLE)))
              .thenWaitUntil(() -> unloader.waitFor(helper, false))
              //Let the rest of the line form its networks again
              .thenIdle(5)
              .thenExecute(() -> {
                  //What the unloaded chunk stores on disk plus what the loaded cables still hold must be exactly what the line held
                  long onDisk = savedCableEnergy(helper, unloader.chunk);
                  long loaded = 0;
                  Set<EnergyNetwork> networks = new ReferenceOpenHashSet<>();
                  for (int x = 0; x < LENGTH; x++) {
                      BlockPos pos = new BlockPos(x, 0, 0);
                      if (!chunkOf(helper, x).equals(unloader.chunk)) {
                          EnergyNetwork network = cable(helper, x).getTransmitterNetwork();
                          if (network == null) {
                              helper.fail("Loaded cable has no network", pos);
                          } else if (networks.add(network)) {
                              loaded += network.energyContainer.getAmountAsLong();
                          }
                      }
                  }
                  helper.assertValueEqual(onDisk + loaded, expected[0], "energy saved with the unloaded chunk (" + onDisk + ") plus energy of the loaded networks (" + loaded + ")");
                  if (onDisk == 0) {
                      helper.fail("Expected the unloaded chunk to store some energy");
                  }
              })
              //Load the chunk again: the line forms one network with exactly the same energy
              .thenExecute(() -> unloader.load(helper))
              .thenWaitUntil(() -> unloader.waitFor(helper, true))
              .thenWaitUntil(() -> {
                  EnergyNetwork network = cable(helper, 0).getTransmitterNetwork();
                  for (int x = 0; x < LENGTH; x++) {
                      EnergyNetwork other = cable(helper, x).getTransmitterNetwork();
                      if (network == null || other != network) {
                          helper.fail("Cable line has not reformed into one network yet", new BlockPos(x, 0, 0));
                      }
                  }
              })
              .thenExecute(() -> helper.assertValueEqual(network(helper, 0).energyContainer.getAmountAsLong(), expected[0],
                    "network energy after reloading the middle chunk"))
              .thenSucceed()
        );
    }

    @GameTest(template = CABLE_LINE, setupTicks = SharedConstants.TICKS_PER_SECOND, timeoutTicks = 10 * SharedConstants.TICKS_PER_SECOND, batch = "save_share_2")
    @TestHolder(description = "Tests that the final save of a stopping server and network splits still mark the chunks of a cable network.")
    public static void finalSaveAndSplitMark(final MekGameTestHelper helper) {
        helper.startSequence()
              .thenExecute(() -> {
                  EnergyNetwork network = network(helper, 0);
                  network.energyContainer.setEnergy(network.getCapacity() / 2, null);
                  saveAll(helper);
              })
              .thenExecuteAfter(1, () -> {
                  EnergyNetwork network = network(helper, 0);
                  network.energyContainer.setEnergy(network.getCapacity() / 3, null);
              })
              .thenExecuteAfter(1, () -> {
                  assertChunksSaved(helper, "after a small change of the stored energy");
                  //What the server stopping does before its final save
                  TransmitterNetworkRegistry.prepareFinalSave();
                  assertChunksUnsaved(helper, 0, LENGTH - 1, "for the final save");
                  //The final save stores the current energy
                  long stored = 0;
                  for (int x = 0; x < LENGTH; x++) {
                      stored += savedEnergy(helper, x);
                  }
                  helper.assertValueEqual(stored, network(helper, 0).energyContainer.getAmountAsLong(), "energy stored by the final save");
                  saveAll(helper);
                  assertChunksSaved(helper, "after the save");
              })
              //Split the network by breaking a cable in the middle: the two new networks mark their chunks
              .thenExecuteAfter(1, () -> helper.setBlock(MIDDLE, 0, 0, Blocks.AIR))
              .thenExecuteAfter(3, () -> {
                  assertChunksUnsaved(helper, 0, 0, "after the network split");
                  assertChunksUnsaved(helper, LENGTH - 1, LENGTH - 1, "after the network split");
              })
              .thenSucceed();
    }

    private static UniversalCable cable(MekGameTestHelper helper, int x) {
        return helper.getBlockEntity(new BlockPos(x, 0, 0), TileEntityUniversalCable.class).getTransmitter();
    }

    private static EnergyNetwork network(MekGameTestHelper helper, int x) {
        return cable(helper, x).getTransmitterNetworkNN();
    }

    private static long savedEnergy(MekGameTestHelper helper, int x) {
        TileEntityUniversalCable tile = helper.getBlockEntity(new BlockPos(x, 0, 0), TileEntityUniversalCable.class);
        return tile.saveWithoutMetadata(helper.getLevel().registryAccess()).getLongOr(SerializationConstants.ENERGY, 0);
    }

    /// Sums the energy of the universal cables stored in the saved data of a chunk.
    private static long savedCableEnergy(MekGameTestHelper helper, ChunkPos chunk) {
        CompoundTag data = helper.getChunkMap().read(chunk).join().orElse(null);
        if (data == null) {
            helper.fail("No saved data for chunk " + chunk);
        }
        long energy = 0;
        int cables = 0;
        for (Tag tag : data.getListOrEmpty("block_entities")) {
            if (tag instanceof CompoundTag blockEntity && blockEntity.getStringOr("id", "").equals("mekanism:basic_universal_cable")) {
                energy += blockEntity.getLongOr(SerializationConstants.ENERGY, 0);
                cables++;
            }
        }
        if (cables == 0) {
            helper.fail("No saved cables in chunk " + chunk);
        }
        return energy;
    }

    private static ChunkPos chunkOf(MekGameTestHelper helper, int x) {
        return ChunkPos.containing(helper.absolutePos(new BlockPos(x, 0, 0)));
    }

    private static LevelChunk chunk(MekGameTestHelper helper, ChunkPos pos) {
        return helper.getLevel().getChunk(pos.x(), pos.z());
    }

    /// The chunks of the cables from `fromX` to `toX`.
    private static LongLinkedOpenHashSet chunks(MekGameTestHelper helper, int fromX, int toX) {
        LongLinkedOpenHashSet chunks = new LongLinkedOpenHashSet();
        for (int x = fromX; x <= toX; x++) {
            chunks.add(chunkOf(helper, x).pack());
        }
        return chunks;
    }

    /// Marks and saves the chunks of the line, like an autosave does with chunks that changed.
    private static void saveAll(MekGameTestHelper helper) {
        for (long pos : chunks(helper, 0, LENGTH - 1)) {
            chunk(helper, ChunkPos.unpack(pos)).markUnsaved();
        }
        helper.getLevel().getChunkSource().save(false);
    }

    private static void assertChunksSaved(MekGameTestHelper helper, String when) {
        for (long pos : chunks(helper, 0, LENGTH - 1)) {
            if (chunk(helper, ChunkPos.unpack(pos)).isUnsaved()) {
                helper.fail("Chunk " + ChunkPos.unpack(pos) + " is marked unsaved " + when);
            }
        }
    }

    private static void assertChunksUnsaved(MekGameTestHelper helper, int fromX, int toX, String when) {
        for (long pos : chunks(helper, fromX, toX)) {
            if (!chunk(helper, ChunkPos.unpack(pos)).isUnsaved()) {
                helper.fail("Chunk " + ChunkPos.unpack(pos) + " was not marked " + when);
            }
        }
    }

    /// Unloads and reloads one chunk by absolute position, the same way TransmitterNetworkTest's ChunkData does.
    private static class ChunkUnloader {

        private ChunkPos chunk;
        private int level;
        private boolean loaded = true;

        void unload(MekGameTestHelper helper, ChunkPos chunk) {
            this.chunk = chunk;
            ChunkMap chunkMap = helper.getChunkMap();
            DistanceManager distanceManager = chunkMap.getDistanceManager();
            ChunkHolder holder = distanceManager.getChunk(chunk.pack());
            if (holder == null) {
                helper.fail("Chunk " + chunk + " is not loaded");
            }
            level = holder.getTicketLevel();
            distanceManager.updateChunkScheduling(chunk.pack(), MekGameTestHelper.UNLOAD_LEVEL, holder, level);
            chunkMap.processUnloads(ConstantPredicates.ALWAYS_TRUE);
        }

        void load(MekGameTestHelper helper) {
            DistanceManager distanceManager = helper.getChunkMap().getDistanceManager();
            //Note: The unload bypassed the ticket system, so it may have scheduled the chunk again by itself already. Only load it if it didn't,
            // as a second holder for the same chunk would load it twice
            if (distanceManager.getChunk(chunk.pack()) == null) {
                ChunkHolder holder = distanceManager.updateChunkScheduling(chunk.pack(), level, null, MekGameTestHelper.UNLOAD_LEVEL);
                if (holder == null) {
                    helper.fail("Error loading chunk " + chunk);
                }
                distanceManager.chunksToUpdateFutures.add(holder);
            }
        }

        void onChunkEvent(ChunkEvent<LevelChunk> event, boolean loaded) {
            if (!event.getLevel().isClientSide() && event.getChunk().getPos().equals(chunk)) {
                this.loaded = loaded;
            }
        }

        void waitFor(MekGameTestHelper helper, boolean loaded) {
            if (this.loaded != loaded) {
                helper.fail("Chunk " + chunk + " has not been " + (loaded ? "loaded" : "unloaded") + " yet");
            }
        }
    }
}
