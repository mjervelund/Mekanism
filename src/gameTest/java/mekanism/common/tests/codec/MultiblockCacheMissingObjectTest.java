package mekanism.common.tests.codec;

import java.util.List;
import mekanism.api.chemical.BasicChemicalTank;
import mekanism.api.chemical.ChemicalIds;
import mekanism.api.chemical.ChemicalResource;
import mekanism.api.chemical.IChemicalTank;
import mekanism.common.Mekanism;
import mekanism.common.capabilities.fluid.BasicFluidTank;
import mekanism.common.component.containers.type.ContainerType;
import mekanism.common.content.tank.TankCache;
import mekanism.common.tests.helpers.MekGameTestHelper;
import mekanism.common.util.ChemicalUtils;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.level.storage.TagValueInput;
import net.minecraft.world.level.storage.TagValueOutput;
import net.neoforged.neoforge.transfer.fluid.FluidResource;
import net.neoforged.testframework.annotation.ForEachTest;
import net.neoforged.testframework.annotation.TestHolder;
import net.neoforged.testframework.gametest.EmptyTemplate;
import net.neoforged.testframework.gametest.GameTest;

@ForEachTest(groups = "codec.missing")
public class MultiblockCacheMissingObjectTest {

    /// Audit for [Issue 8415](https://github.com/mekanism/Mekanism/issues/8415) (dynamic tanks empty after a modpack update): a multiblock cache (here a
    /// dynamic tank's) that stores a chemical whose registry entry is gone must only lose that tank, not the rest of its contents.
    @GameTest
    @EmptyTemplate
    @TestHolder(description = "Tests that a multiblock cache with a missing chemical keeps its other tanks when loaded.")
    public static void tankCacheMissingChemical(final MekGameTestHelper helper) {
        helper.succeedIf(() -> {
            ChemicalResource hydrogen = ChemicalUtils.getResource(helper.getLevel().registryAccess(), ChemicalIds.HYDROGEN);
            ChemicalResource oxygen = ChemicalUtils.getResource(helper.getLevel().registryAccess(), ChemicalIds.OXYGEN);
            IChemicalTank hydrogenTank = BasicChemicalTank.createAllValid(Long.MAX_VALUE, null);
            hydrogenTank.setContents(hydrogen, 1_000, null);
            IChemicalTank oxygenTank = BasicChemicalTank.createAllValid(Long.MAX_VALUE, null);
            oxygenTank.setContents(oxygen, 3_000_000_000L, null);
            BasicFluidTank waterTank = BasicFluidTank.create(Long.MAX_VALUE, null);
            waterTank.setContents(FluidResource.of(Fluids.WATER), 2_000, null);

            CompoundTag saved;
            try (ProblemReporter.ScopedCollector reporter = new ProblemReporter.ScopedCollector(Mekanism.logger)) {
                TagValueOutput output = TagValueOutput.createWithContext(reporter, helper.getLevel().registryAccess());
                output.putInt(ContainerType.CHEMICAL.getTag() + "_stored", 2);
                ContainerType.CHEMICAL.saveTo(output, List.of(hydrogenTank, oxygenTank));
                output.putInt(ContainerType.FLUID.getTag() + "_stored", 1);
                ContainerType.FLUID.saveTo(output, List.of(waterTank));
                saved = output.buildResult();
            }
            //Sanity check: the hand-built tag loads with every tank
            TankCache valid = load(helper, saved.copy());
            helper.assertValueEqual(valid.getChemicalTanks().size(), 2, "chemical tanks");
            helper.assertValueEqual(valid.getChemicalTanks().getFirst().resource(), hydrogen, "first chemical");

            //Now the hydrogen is from a mod that is no longer installed
            String hydrogenId = ChemicalIds.HYDROGEN.identifier().toString();
            helper.assertTrue(replaceString(saved, hydrogenId, "mekanism:invalid"), "Expected to find " + hydrogenId + " in " + saved);
            TankCache cache = load(helper, saved);
            helper.assertValueEqual(cache.getChemicalTanks().size(), 2, "chemical tanks");
            helper.assertTrue(cache.getChemicalTanks().getFirst().isEmpty(), "Expected the tank with the missing chemical to be empty");
            helper.assertValueEqual(cache.getChemicalTanks().get(1).resource(), oxygen, "second chemical");
            helper.assertValueEqual(cache.getChemicalTanks().get(1).amountAsLong(), 3_000_000_000L, "second chemical amount");
            helper.assertValueEqual(cache.getFluidTanks().size(), 1, "fluid tanks");
            helper.assertValueEqual(cache.getFluidTanks().getFirst().resource(), FluidResource.of(Fluids.WATER), "fluid");
            helper.assertValueEqual(cache.getFluidTanks().getFirst().amountAsLong(), 2_000L, "fluid amount");
        });
    }

    private static TankCache load(MekGameTestHelper helper, CompoundTag tag) {
        TankCache cache = new TankCache();
        cache.load(TagValueInput.create(ProblemReporter.DISCARDING, helper.getLevel().registryAccess(), tag));
        return cache;
    }

    private static boolean replaceString(Tag tag, String from, String to) {
        boolean replaced = false;
        if (tag instanceof CompoundTag compound) {
            for (String key : List.copyOf(compound.keySet())) {
                Tag child = compound.get(key);
                if (child instanceof StringTag(String value) && value.equals(from)) {
                    compound.putString(key, to);
                    replaced = true;
                } else if (child != null) {
                    replaced |= replaceString(child, from, to);
                }
            }
        } else if (tag instanceof ListTag list) {
            for (Tag child : list) {
                replaced |= replaceString(child, from, to);
            }
        }
        return replaced;
    }
}
