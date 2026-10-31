package mekanism.generators.common.tile;

import it.unimi.dsi.fastutil.HashCommon;
import java.util.Set;
import mekanism.api.IContentsListener;
import mekanism.api.RelativeSide;
import mekanism.api.inventory.IInventorySlot;
import mekanism.api.math.MathUtils;
import mekanism.common.capabilities.energy.BasicEnergyContainer;
import mekanism.common.capabilities.holder.container.IContainerHolder;
import mekanism.common.capabilities.holder.container.MekContainerHelper;
import mekanism.common.integration.computer.SpecialComputerMethodWrapper.ComputerIInventorySlotWrapper;
import mekanism.common.integration.computer.annotation.ComputerMethod;
import mekanism.common.integration.computer.annotation.WrappingComputerMethod;
import mekanism.common.inventory.container.MekanismContainer;
import mekanism.common.inventory.container.sync.SyncableBoolean;
import mekanism.common.inventory.container.sync.SyncableInt;
import mekanism.common.inventory.slot.EnergyInventorySlot;
import mekanism.common.util.LightLevelUtils;
import mekanism.generators.common.config.MekanismGeneratorsConfig;
import mekanism.generators.common.registries.GeneratorsBlocks;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Biome.Precipitation;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.UnknownNullability;
import org.jspecify.annotations.Nullable;

public class TileEntitySolarGenerator extends TileEntityGenerator {

    private static final Set<RelativeSide> ENERGY_SIDES = Set.of(RelativeSide.BOTTOM);
    private static final int SUN_RECHECK_TICKS = SharedConstants.TICKS_PER_SECOND;
    private boolean seesSun;
    /// Production per tick from the last sun check.
    private int production;
    private boolean refreshSun;
    /// Whether the panel produced anything since the last sun check; decides the active state at the next check.
    private boolean producedSinceRefresh;
    /// Whether the last emit sent anything.
    private boolean lastEmitSent = true;
    /// This panel's tick offset within the sun check and emit intervals, or -1 before it is computed.
    private int phase = -1;
    private int lastProductionAmount = 0;
    /// Game time of the last tick this panel processed (see [#skipServerTick]), or [Long#MIN_VALUE] before the first.
    private long lastProcessedTick = Long.MIN_VALUE;
    /// Game ticks the current processed tick stands for.
    private int tickSpan = 1;
    @UnknownNullability//Initialized via getInitialInventory
    @WrappingComputerMethod(wrapper = ComputerIInventorySlotWrapper.class, methodNames = "getEnergyItem", docPlaceholder = "energy item slot")
    EnergyInventorySlot energySlot;
    @Nullable
    protected SolarCheck solarCheck;

    public TileEntitySolarGenerator(BlockPos pos, BlockState state) {
        this(GeneratorsBlocks.SOLAR_GENERATOR, pos, state);
    }

    protected TileEntitySolarGenerator(Holder<Block> blockProvider, BlockPos pos, BlockState state) {
        super(blockProvider, pos, state);
    }

    @Override
    protected IContainerHolder<IInventorySlot> getInitialInventory(IContentsListener listener) {
        MekContainerHelper<IInventorySlot> builder = MekContainerHelper.forSide(facingSupplier);
        builder.addContainer(energySlot = EnergyInventorySlot.drain(energyContainer(), listener, 143, 35));
        return builder.build();
    }

    @ComputerMethod
    public boolean canSeeSun() {
        return seesSun;
    }

    @Override
    protected boolean skipServerTick(ServerLevel level) {
        //A panel only needs to act on its emit tick: it checks the sun once a second and emits every interval anyway. So skip the whole tile
        // tick in between (also the generic TileEntityMekanism work), and add the skipped ticks' production on the next processed tick.
        // Not while an item charges from the panel (it charges per tick), and not before the first processed tick.
        int interval = MekanismGeneratorsConfig.generators.solarEmitInterval.get();
        long now = level.getGameTime();
        if (interval > 1 && lastProcessedTick != Long.MIN_VALUE && solarCheck != null && energySlot.isEmpty()
            && Math.floorMod(now + phase(), interval) != 0 && now - lastProcessedTick < interval) {
            return true;
        }
        tickSpan = lastProcessedTick == Long.MIN_VALUE ? 1 : (int) Math.clamp(now - lastProcessedTick, 1, Math.max(interval, 1));
        lastProcessedTick = now;
        return false;
    }

    @Override
    protected int serverTickSpan() {
        return tickSpan;
    }

    @Override
    protected boolean onUpdateServer(ServerLevel level) {
        boolean firstCheck = solarCheck == null;
        if (firstCheck) {
            recheckSettings();
            //Check the sky on the first tick, then once a second
            refreshSun = true;
        }
        if (refreshSun || Math.floorMod(level.getGameTime() + phase(), SUN_RECHECK_TICKS) == 0 || tickSpan >= SUN_RECHECK_TICKS) {
            // Sort out if the generator can see the sun; we no longer check if it's raining here,
            // since under the new rules, we can still generate power when it's raining, albeit at a
            // significant penalty.
            //Note: Sky visibility and brightness change slowly (blocks above, time of day, weather), so this runs once a second per panel
            // (each panel on its own tick) instead of every tick, and the production is reused in between.
            refreshSun = false;
            //Update the active state once a second too, from whether we produced anything since the last check: a panel whose buffer is full
            // while it waits for its emit tick adds nothing on those ticks, and setting the state per tick would flip it on and off every interval
            // (Not on the first check after loading: nothing was produced yet, and the loaded state is kept until the next check)
            if (!firstCheck) {
                setActive(producedSinceRefresh);
            }
            producedSinceRefresh = false;
            seesSun = checkCanSeeSun();
            production = seesSun ? Math.max(0, getProduction()) : 0;
        }
        //When this tick stands for several (skipped) ticks, first fill the buffer with their production so it is emitted now: the buffer only
        // holds about one emit interval of peak production, and biome multipliers can exceed peak
        long batched = 0;
        if (tickSpan > 1 && seesSun && canFunction()) {
            batched = addProduction((long) production * tickSpan);
        }
        //Note: The generator emits (in super) before adding this tick's production, like before; shouldEmit uses the production computed above
        boolean sendUpdatePacket = super.onUpdateServer(level);
        energySlot.drainContainerIntoSlot(null);
        if (seesSun && canFunction()) {
            //Add the production directly instead of inserting in a root transaction: this runs every tick for every panel, and every transaction open
            // costs NeoForge a stack walk. The generator's container is a BasicEnergyContainer without a rate limit that allows internal insertion,
            // so an internal insert would add exactly min(production, needed) and fire the contents listener on commit, which setEnergy does directly.
            long added = batched + addProduction((long) production * tickSpan - batched);
            //Per tick, for the GUI and computers
            lastProductionAmount = (int) (added / tickSpan);
            if (added > 0) {
                producedSinceRefresh = true;
            }
        } else {
            lastProductionAmount = 0;
        }
        return sendUpdatePacket;
    }

    /// Adds up to `amount` to the buffer (as much as fits) and returns how much was added.
    private long addProduction(long amount) {
        if (amount <= 0) {
            return 0;
        }
        BasicEnergyContainer container = energyContainer();
        long toAdd = Math.min(amount, container.getNeededAsLong());
        if (toAdd > 0) {
            container.setEnergy(container.getAmountAsLong() + toAdd, null);
        }
        return toAdd;
    }

    private int phase() {
        if (phase == -1) {
            //Spread panels over the ticks of the interval, so that they do not all check and emit on the same tick
            phase = (int) (HashCommon.mix(worldPosition.asLong()) >>> 33);
        }
        return phase;
    }

    @Override
    protected boolean shouldEmit(ServerLevel level) {
        BasicEnergyContainer container = energyContainer();
        if (container.isEmpty()) {
            return false;
        }
        int interval = MekanismGeneratorsConfig.generators.solarEmitInterval.get();
        if (interval <= 1 || Math.floorMod(level.getGameTime() + phase(), interval) == 0) {
            return true;
        }
        //Emit early if the buffer could not take this tick's production, unless the last attempt sent nothing (the targets are full: wait for our tick)
        return lastEmitSent && container.getNeededAsLong() < Math.max(production, 1);
    }

    @Override
    protected void onEmitted(int sent) {
        lastEmitSent = sent > 0;
    }

    protected void recheckSettings() {
        if (level == null) {
            return;
        }
        solarCheck = new SolarCheck(level, worldPosition);
    }

    protected boolean checkCanSeeSun() {
        if (solarCheck == null) {
            return false;
        }
        solarCheck.recheckCanSeeSun();
        return solarCheck.canSeeSun();
    }

    public int getProduction() {
        if (level == null || solarCheck == null) {
            return 0;
        }
        float brightness = getBrightnessMultiplier(level);
        //Production is a function of the peak possible output in this biome and sun's current brightness
        return MathUtils.clampToInt(getConfiguredMax() * (brightness * solarCheck.getGenerationMultiplier()));
    }

    protected float getBrightnessMultiplier(Level world) {
        //Get the brightness of the sun; including rain penalty
        return LightLevelUtils.getSunBrightness(world, this.worldPosition);
    }

    @Override
    protected Set<RelativeSide> getEnergySides() {
        return ENERGY_SIDES;
    }

    protected int getConfiguredMax() {
        return MekanismGeneratorsConfig.generators.solarGeneration.get();
    }

    @Override
    public int getProductionRate() {
        return lastProductionAmount;
    }

    @Override
    public void addContainerTrackers(MekanismContainer container) {
        super.addContainerTrackers(container);
        container.track(SyncableBoolean.create(this::canSeeSun, value -> seesSun = value));
        container.track(SyncableInt.create(this::getProductionRate, value -> lastProductionAmount = value));
    }

    protected static class SolarCheck {

        private final boolean needsRainCheck;
        private final float peakMultiplier;
        protected final BlockPos pos;
        protected final Level world;
        protected boolean canSeeSun;

        public SolarCheck(Level world, BlockPos pos) {
            this.world = world;
            this.pos = pos;
            int seaLevel = world.getSeaLevel();
            Biome b = this.world.getBiomeManager().getBiome(this.pos).value();
            needsRainCheck = b.getPrecipitationAt(this.pos, seaLevel) != Precipitation.NONE;
            // Consider the best temperature to be 0.8; biomes that are higher than that
            // will suffer an efficiency loss (semiconductors don't like heat); biomes that are cooler
            // get a boost. We scale the efficiency to around 30% so that it doesn't totally dominate
            float tempEff = 0.3F * (0.8F - b.getTemperature(this.pos, seaLevel));

            // Treat rainfall as a proxy for humidity; any humidity works as a drag on overall efficiency.
            // As with temperature, we scale it so that it doesn't overwhelm production. Note the signedness
            // on the scaling factor. Also note that we only use rainfall as a proxy if it CAN rain; some dimensions
            // (like the End) have rainfall set, but can't actually support rain.
            float humidityEff = needsRainCheck ? -0.3F * b.getModifiedClimateSettings().downfall() : 0;
            peakMultiplier = 1.0F + tempEff + humidityEff;
        }

        public void recheckCanSeeSun() {
            canSeeSun = LightLevelUtils.canSeeSun(world, pos);
        }

        public boolean canSeeSun() {
            return canSeeSun;
        }

        public float getPeakMultiplier() {
            return peakMultiplier;
        }

        public float getGenerationMultiplier() {
            if (!canSeeSun) {
                return 0;
            }
            return peakMultiplier;
        }
    }
}
