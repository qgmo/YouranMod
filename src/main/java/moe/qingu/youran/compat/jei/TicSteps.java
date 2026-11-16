package moe.qingu.youran.compat.jei;

import mezz.jei.api.IModRegistry;
import moe.qingu.youran.item.LampNbt;
import moe.qingu.youran.logic.litho.LampGas;
import moe.qingu.youran.logic.litho.LampGlass;
import moe.qingu.youran.logic.litho.LampSpec;
import moe.qingu.youran.logic.litho.LithoNumbers;
import moe.qingu.youran.logic.purity.Purity;
import moe.qingu.youran.logic.purity.WaferMold;
import moe.qingu.youran.registry.ModFluids;
import moe.qingu.youran.registry.ModItems;
import net.minecraft.item.ItemStack;
import net.minecraftforge.fluids.FluidStack;
import slimeknights.mantle.util.RecipeMatch;
import slimeknights.tconstruct.library.smeltery.CastingRecipe;
import slimeknights.tconstruct.plugin.jei.JEIPlugin;
import slimeknights.tconstruct.plugin.jei.casting.CastingRecipeCategory;
import slimeknights.tconstruct.plugin.jei.casting.CastingRecipeWrapper;

import java.util.Collections;

/**
 * 匠魂浇铸台给汞灯补汞：真正的配方是个自定义的 {@code ICastingRecipe}（按灯里还差多少汞浇，{@code recipe.LithoRecipes.MercuryCasting}），
 * 匠魂的 JEI 只列它自己的 {@code CastingRecipe}。这里造一个只展示用的 {@code CastingRecipe}（<b>不</b>登记进匠魂的配方表）加到匠魂浇铸台的页里。
 * 只有装了匠魂、JEI 时才会被加载。
 */
final class TicSteps {
    private TicSteps() {
    }

    static void register(IModRegistry registry) {
        if (ModFluids.hydrargyrum == null || JEIPlugin.castingCategory == null) {
            return;
        }
        LampSpec tube = LampSpec.tube(LampGlass.GLASS, WaferMold.GOLD, Purity.DEFAULT_NINES).assembled(LampGas.ARGON);
        ItemStack empty = new ItemStack(ModItems.mercuryLamp);
        empty.setTagCompound(LampNbt.tag(tube));
        ItemStack full = new ItemStack(ModItems.mercuryLamp);
        full.setTagCompound(LampNbt.tag(tube.refilled()));
        CastingRecipe shown = new CastingRecipe(full, RecipeMatch.ofNBT(empty), new FluidStack(ModFluids.hydrargyrum, LithoNumbers.LAMP_MERCURY),
                LithoNumbers.MERCURY_CASTING_TIME, true, false);
        CastingRecipeWrapper wrapper = new CastingRecipeWrapper(shown, JEIPlugin.castingCategory.castingTable);
        if (wrapper.isValid(true)) {
            registry.addRecipes(Collections.singletonList(wrapper), CastingRecipeCategory.CATEGORY);
        }
    }
}
