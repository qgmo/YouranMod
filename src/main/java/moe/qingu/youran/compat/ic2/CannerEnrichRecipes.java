package moe.qingu.youran.compat.ic2;

import ic2.api.recipe.ICannerEnrichRecipeManager;
import ic2.api.recipe.IRecipeInput;
import ic2.api.recipe.Recipes;
import moe.qingu.youran.YouranMod;
import moe.qingu.youran.fluid.FluidVariants;
import moe.qingu.youran.logic.reaction.FluidItemReaction;
import moe.qingu.youran.logic.reaction.ItemIngredient;
import moe.qingu.youran.recipe.CannerReactions;
import moe.qingu.youran.recipe.ReactionItem;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.util.ResourceLocation;
import net.minecraftforge.fluids.FluidStack;
import net.minecraftforge.fml.common.registry.ForgeRegistries;

import javax.annotation.Nullable;

/**
 * 把内置的“流体 + 物品 → 只出流体”的反应（{@link CannerReactions#nativeEnrichments}）登记进 IC2 自己的富集配方表
 * （{@code Recipes.cannerEnrich}）：IC2 装罐机富集模式照它自己的规则做，IC2 的 JEI 页里也直接显示。数量、产物与原来的反应表一样。
 * 只有装了 IC2 时才会被加载，后初始化时调用（{@link Ic2Recipes#register}）。
 */
final class CannerEnrichRecipes {
    private CannerEnrichRecipes() {
    }

    static void register() {
        int added = 0;
        for (FluidItemReaction<ItemStack, ReactionItem> r : CannerReactions.nativeEnrichments()) {
            FluidStack in = FluidVariants.stack(r.inputFluid(), r.inputAmount());
            FluidStack out = FluidVariants.stack(r.outputFluid(), r.outputAmount());
            IRecipeInput additive = additive(r.inputItem());
            if (in == null || out == null || additive == null) {
                YouranMod.LOG.info("IC2 富集配方（{}）没加：流体或物品没有注册", r);
                continue;
            }
            if (Recipes.cannerEnrich.addRecipe(new ICannerEnrichRecipeManager.Input(in, additive), out, null, false)) {
                added++;
            } else {
                YouranMod.LOG.error("IC2 富集配方（{}）没加上：已有输入相同的配方", r);
            }
        }
        YouranMod.LOG.info("已加 IC2 装罐机富集配方：{} 条", added);
    }

    @Nullable
    private static IRecipeInput additive(ItemIngredient<ItemStack> ingredient) {
        if (ingredient.oreName() != null) {
            return Recipes.inputFactory.forOreDict(ingredient.oreName(), ingredient.count());
        }
        Item item = ForgeRegistries.ITEMS.getValue(new ResourceLocation(ingredient.itemId()));
        return item == null ? null : Recipes.inputFactory.forStack(new ItemStack(item, ingredient.count(), ingredient.meta()), ingredient.count());
    }
}
