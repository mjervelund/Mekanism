package mekanism.generators.common.tile;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import mekanism.common.tile.base.TileEntityMekanism;
import mekanism.generators.common.config.MekanismGeneratorsConfig;
import net.minecraft.CrashReport;
import net.minecraft.CrashReportCategory;
import net.minecraft.ReportedException;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.FullChunkStatus;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.neoforged.neoforge.event.level.LevelEvent;
import net.neoforged.neoforge.event.tick.LevelTickEvent;

/// Ticks solar generators on the server instead of the vanilla block entity ticker list.
///
/// A panel only needs to act once per emit interval (it checks the sun once a second and emits in batches), so as a ticking block entity
/// it cost a ticker-list entry, vanilla's per-entry checks and a call on every tick for nothing on all but one tick in twenty; with tens of
/// thousands of panels that alone was several ms per tick. Panels register here when loaded, in one bucket per tick of the interval (their
/// phase), and each level tick processes only the bucket whose turn it is, with the same checks vanilla makes before ticking a block entity.
/// A panel charging an item from its slot is ticked every tick while the slot holds something.
public final class SolarTickScheduler {

    private static final Map<ServerLevel, SolarTickScheduler> SCHEDULERS = new IdentityHashMap<>();

    private int interval;
    private List<TileEntitySolarGenerator>[] buckets;
    private final List<TileEntitySolarGenerator> everyTick = new ArrayList<>();
    private final List<TileEntitySolarGenerator> pending = new ArrayList<>();

    private SolarTickScheduler() {
    }

    public static void add(ServerLevel level, TileEntitySolarGenerator panel) {
        SCHEDULERS.computeIfAbsent(level, l -> new SolarTickScheduler()).pending.add(panel);
    }

    public static void onLevelTick(LevelTickEvent.Post event) {
        if (event.getLevel() instanceof ServerLevel level) {
            SolarTickScheduler scheduler = SCHEDULERS.get(level);
            if (scheduler != null && level.tickRateManager().runsNormally()) {
                scheduler.tick(level);
            }
        }
    }

    public static void onLevelUnload(LevelEvent.Unload event) {
        if (event.getLevel() instanceof ServerLevel level) {
            SCHEDULERS.remove(level);
        }
    }

    public static void onServerStopped() {
        SCHEDULERS.clear();
    }

    @SuppressWarnings("unchecked")
    private void tick(ServerLevel level) {
        int configured = Math.max(1, MekanismGeneratorsConfig.generators.solarEmitInterval.get());
        if (buckets == null || configured != interval) {
            //(Re)bucket everything for the current interval
            List<TileEntitySolarGenerator> all = new ArrayList<>(pending);
            if (buckets != null) {
                for (List<TileEntitySolarGenerator> bucket : buckets) {
                    all.addAll(bucket);
                }
            }
            all.addAll(everyTick);
            everyTick.clear();
            pending.clear();
            interval = configured;
            buckets = new List[interval];
            for (int i = 0; i < interval; i++) {
                buckets[i] = new ArrayList<>();
            }
            pending.addAll(all);
        }
        long time = level.getGameTime();
        if (!pending.isEmpty()) {
            //New panels: tick once now (sets them up and syncs bounding blocks like their first vanilla tick did), then file them by phase
            List<TileEntitySolarGenerator> fresh = new ArrayList<>(pending);
            pending.clear();
            for (TileEntitySolarGenerator panel : fresh) {
                if (!panel.isRemoved()) {
                    tickPanel(level, panel);
                    buckets[bucketOf(panel)].add(panel);
                }
            }
        }
        //Panels charging an item tick every tick (before the bucket, so a panel moved over from the bucket below is not ticked twice now)
        everyTick.removeIf(panel -> {
            if (panel.isRemoved()) {
                return true;
            }
            tickPanel(level, panel);
            if (!panel.needsEveryTick()) {
                buckets[bucketOf(panel)].add(panel);
                return true;
            }
            return false;
        });
        buckets[(int) Math.floorMod(time, (long) interval)].removeIf(panel -> {
            if (panel.isRemoved()) {
                return true;
            }
            tickPanel(level, panel);
            if (panel.needsEveryTick()) {
                everyTick.add(panel);
                return true;
            }
            return false;
        });
    }

    /// The bucket processed on the panel's phase tick: (time + phase) % interval == 0.
    private int bucketOf(TileEntitySolarGenerator panel) {
        return Math.floorMod(-panel.phase(), interval);
    }

    /// Ticks the panel if vanilla would tick a block entity there now (same checks as `Level#tickBlockEntities` and `LevelChunk#isTicking`).
    private static void tickPanel(ServerLevel level, TileEntitySolarGenerator panel) {
        BlockPos pos = panel.getBlockPos();
        long chunkKey = ChunkPos.pack(pos);
        if (!level.shouldTickBlocksAt(chunkKey) || !level.getWorldBorder().isWithinBounds(pos) || !level.areEntitiesLoaded(chunkKey)) {
            return;
        }
        LevelChunk chunk = level.getChunkSource().getChunkNow(pos.getX() >> 4, pos.getZ() >> 4);
        if (chunk == null || !chunk.getFullStatus().isOrAfter(FullChunkStatus.BLOCK_TICKING)) {
            return;
        }
        BlockState state = chunk.getBlockState(pos);
        if (!panel.getType().isValid(state)) {
            return;
        }
        try {
            TileEntityMekanism.tickServer(level, pos, state, panel);
        } catch (Throwable t) {
            CrashReport report = CrashReport.forThrowable(t, "Ticking block entity");
            CrashReportCategory category = report.addCategory("Block entity being ticked");
            panel.fillCrashReportCategory(category);
            //Like a vanilla block entity tick with NeoForge's default settings
            throw new ReportedException(report);
        }
    }
}
