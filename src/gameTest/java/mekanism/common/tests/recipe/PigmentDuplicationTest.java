package mekanism.common.tests.recipe;

import java.util.ArrayList;
import java.util.List;
import mekanism.api.recipes.ItemStackChemicalToItemStackRecipe;
import mekanism.api.recipes.ItemStackToChemicalRecipe;
import mekanism.api.recipes.basic.BasicItemStackChemicalToItemStackRecipe;
import mekanism.api.recipes.basic.BasicItemStackToChemicalRecipe;
import mekanism.api.recipes.book.PaintingBookCategory;
import mekanism.api.recipes.book.PaintingBookCategory.PaintingBookInfo;
import mekanism.api.recipes.book.PigmentExtractingBookCategory;
import mekanism.api.recipes.book.PigmentExtractingBookCategory.PigmentExtractingBookInfo;
import mekanism.common.recipe.MekanismRecipeType;
import mekanism.common.tests.helpers.RecipeTestHelper;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.neoforged.testframework.DynamicTest;
import net.neoforged.testframework.annotation.ForEachTest;
import net.neoforged.testframework.annotation.TestHolder;
import net.neoforged.testframework.gametest.EmptyTemplate;
import net.neoforged.testframework.gametest.GameTest;

@ForEachTest(groups = "recipe.pigment")
public class PigmentDuplicationTest {

    /// https://github.com/mekanism/Mekanism/issues/8619: painting an item must cost at least as much pigment as extracting the painted item gives
    /// back, otherwise painting and extracting in a loop creates pigment from nothing.
    @GameTest
    @EmptyTemplate
    @TestHolder(description = "Tests that no item gives back more pigment from extracting than it costs to paint it.")
    public static void testPaintingExtractingLoop(final DynamicTest test) {
        test.onGameTest(RecipeTestHelper.class, helper -> helper.succeedIf(() -> {
            List<String> duplicating = new ArrayList<>();
            List<RecipeHolder<ItemStackToChemicalRecipe<PigmentExtractingBookCategory, PigmentExtractingBookInfo>>> extracting = helper.getRecipes(MekanismRecipeType.PIGMENT_EXTRACTING);
            for (RecipeHolder<ItemStackChemicalToItemStackRecipe<PaintingBookCategory, PaintingBookInfo>> paintingHolder : helper.getRecipes(MekanismRecipeType.PAINTING)) {
                if (!(paintingHolder.value() instanceof BasicItemStackChemicalToItemStackRecipe<PaintingBookCategory, PaintingBookInfo> painting)) {
                    continue;
                }
                ItemStack painted = painting.getOutputRaw().create();
                //Pigment paid per painted item
                double cost = painting.getChemicalInput().amount() / (double) painted.getCount();
                ItemStack probe = painted.copyWithCount(painted.getMaxStackSize());
                for (RecipeHolder<ItemStackToChemicalRecipe<PigmentExtractingBookCategory, PigmentExtractingBookInfo>> extractingHolder : extracting) {
                    if (!(extractingHolder.value() instanceof BasicItemStackToChemicalRecipe<PigmentExtractingBookCategory, PigmentExtractingBookInfo> extract)
                        || !extract.getInput().test(probe) || !painting.getChemicalInput().testType(extract.getOutputRaw().chemical())) {
                        continue;
                    }
                    double yield = extract.getOutputRaw().amount() / (double) extract.getInput().getNeededAmount(probe);
                    if (yield > cost) {
                        duplicating.add(paintingHolder.id() + " costs " + cost + " but " + extractingHolder.id() + " gives " + yield);
                    }
                }
            }
            if (!duplicating.isEmpty()) {
                helper.fail(duplicating.size() + " painting recipes create pigment when combined with extracting: " + String.join("; ", duplicating));
            }
        }));
    }
}
