package moe.qingu.youran.compat.jei;

import mezz.jei.api.IRecipeRegistry;
import mezz.jei.api.recipe.IRecipeWrapper;
import moe.qingu.youran.YouranMod;
import moe.qingu.youran.compat.tconstruct.TicRecipes;
import slimeknights.tconstruct.library.smeltery.AlloyRecipe;

/** 在 JEI 里藏起匠魂冶炼炉按纯度多登记的混合气合金配方。只有装了匠魂时才会被加载 */
final class TicHidden {
    private TicHidden() {
    }

    static void hide(IRecipeRegistry registry, String category) {
        int hidden = 0;
        for (AlloyRecipe recipe : TicRecipes.unlistedAlloys()) {
            try {
                IRecipeWrapper wrapper = registry.getRecipeWrapper(recipe, category);
                if (wrapper != null) {
                    registry.hideRecipe(wrapper, category);
                    hidden++;
                }
            } catch (RuntimeException e) {
                YouranMod.LOG.warn("JEI 里藏不起匠魂的合金配方 {}", recipe.getResult().getUnlocalizedName(), e);
                return;
            }
        }
        YouranMod.LOG.info("JEI 里藏起了 {} 条按纯度多登记的混合气合金配方", hidden);
    }
}
