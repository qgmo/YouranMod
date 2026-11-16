package moe.qingu.youran.compat.jei;

import ic2.api.recipe.IRecipeInput;
import ic2.api.recipe.MachineRecipe;
import ic2.api.recipe.Recipes;
import ic2.jeiIntegration.recipe.machine.IORecipeCategory;
import ic2.jeiIntegration.recipe.machine.IORecipeWrapper;
import mezz.jei.api.IRecipeRegistry;
import mezz.jei.api.ingredients.IIngredients;
import mezz.jei.api.ingredients.VanillaTypes;
import mezz.jei.api.recipe.IRecipeCategory;
import mezz.jei.api.recipe.IRecipeWrapper;
import moe.qingu.youran.YouranMod;
import moe.qingu.youran.registry.ModItems;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.RenderHelper;
import net.minecraft.client.resources.I18n;
import net.minecraft.item.ItemStack;
import net.minecraftforge.oredict.OreDictionary;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;

/**
 * IC2 热能离心机分粉碎的 / 纯净的铜矿石时偶尔多出一小撮钼粉（{@code compat.ic2.CentrifugeHooks}，做完时按几率加的，IC2 的配方表里没有）。
 * IC2 的 JEI 页画不了按概率掉出的东西，所以把这两条配方在 JEI 里换成一个只展示用的版本（<b>不</b>碰 IC2 的配方表）：
 * 输出格照旧，页面右边多一个小撮钼粉的图标，悬停写“偶尔”（不写概率），小撮钼粉的“怎么来”里也能查到这一页。
 * 只有装了 IC2 与 JEI 时才会被加载。
 */
final class Ic2CentrifugeSteps {
    private static final String[] COPPER_ORES = {"crushedCopper", "crushedPurifiedCopper"};

    private Ic2CentrifugeSteps() {
    }

    /** JEI 运行时可用后调用：藏起铜矿那两条原页的条目，加上带钼粉的展示版 */
    @SuppressWarnings({"unchecked", "rawtypes"})
    static void apply(IRecipeRegistry registry) {
        if (ModItems.smallMolybdenumDust == null) {
            return;
        }
        IRecipeCategory category = null;
        for (IRecipeCategory c : registry.getRecipeCategories()) {
            if (c instanceof IORecipeCategory && ic2.core.ref.TeBlock.centrifuge.getName().equals(c.getUid())) {
                category = c;
            }
        }
        if (category == null) {
            YouranMod.LOG.info("JEI 里没有 IC2 的热能离心机页，不加钼粉的展示");
            return;
        }
        List<IRecipeWrapper> wrappers = registry.getRecipeWrappers(category);
        int replaced = 0;
        for (MachineRecipe<IRecipeInput, Collection<ItemStack>> r : Recipes.centrifuge.getRecipes()) {
            if (!isCopper(r.getInput())) {
                continue;
            }
            for (IRecipeWrapper w : wrappers) {
                if (w instanceof IORecipeWrapper && sameInput((IORecipeWrapper) w, r.getInput())) {
                    registry.hideRecipe(w, category.getUid());
                    registry.addRecipe(new CopperWrapper(r.getInput(), r.getOutput(), (IORecipeCategory<?>) category), category.getUid());
                    replaced++;
                    break;
                }
            }
        }
        YouranMod.LOG.info("JEI 里热能离心机的铜矿配方换成了带小撮钼粉的展示版：{} 条", replaced);
    }

    private static boolean isCopper(IRecipeInput input) {
        for (String ore : COPPER_ORES) {
            for (ItemStack s : OreDictionary.getOres(ore, false)) {
                if (input.matches(s)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean sameInput(IORecipeWrapper w, IRecipeInput input) {
        List<List<ItemStack>> in = w.getInputs();
        return !in.isEmpty() && !in.get(0).isEmpty() && input.matches(in.get(0).get(0));
    }

    /** IC2 页同样的输入输出，另加一个只展示用的小撮钼粉 */
    private static final class CopperWrapper extends IORecipeWrapper {
        private static final int ICON_X = 142;
        private static final int ICON_Y = 19;
        private final ItemStack molybdenum = new ItemStack(ModItems.smallMolybdenumDust);

        CopperWrapper(IRecipeInput input, Collection<ItemStack> output, IORecipeCategory<?> category) {
            super(input, output, category);
        }

        @Override
        public void getIngredients(IIngredients ingredients) {
            super.getIngredients(ingredients);
            List<ItemStack> outs = new ArrayList<>(getOutputs());
            outs.add(molybdenum);
            ingredients.setOutputs(VanillaTypes.ITEM, outs);
        }

        @Override
        public void drawInfo(Minecraft minecraft, int recipeWidth, int recipeHeight, int mouseX, int mouseY) {
            RenderHelper.enableGUIStandardItemLighting();
            minecraft.getRenderItem().renderItemIntoGUI(molybdenum, ICON_X, ICON_Y);
            RenderHelper.disableStandardItemLighting();
        }

        @Override
        public List<String> getTooltipStrings(int mouseX, int mouseY) {
            if (mouseX >= ICON_X && mouseX < ICON_X + 16 && mouseY >= ICON_Y && mouseY < ICON_Y + 16) {
                return java.util.Arrays.asList(molybdenum.getDisplayName(), I18n.format("youranmod.jei.centrifuge.molybdenum"));
            }
            return Collections.emptyList();
        }
    }
}
