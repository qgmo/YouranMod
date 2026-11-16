package moe.qingu.youran.compat.jei;

import flaxbeard.immersivepetroleum.api.crafting.DistillationRecipe;
import moe.qingu.youran.compat.immersivepetroleum.TrichlorosilaneDistillation;
import net.minecraft.client.resources.I18n;
import net.minecraft.item.ItemStack;
import net.minecraftforge.fluids.FluidStack;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/** 沉浸原油的精馏塔（JEI 说明里的电耗）。只有装了沉浸原油时才会被加载 */
final class PetroleumMachines {
    private PetroleumMachines() {
    }

    /** 提纯一次的电耗：乘上沉浸原油配置里的电耗倍率（同精馏塔算原油配方的办法） */
    static int energy() {
        return (int) Math.floor(TrichlorosilaneDistillation.ENERGY * DistillationRecipe.energyModifier);
    }

    /**
     * 本模组加给精馏塔的、带按概率掉出的物品的配方（杂酚油出萘、空气出氖）：沉浸原油自己没有接 JEI，这里列在我们的精馏塔页里。
     * 只写“偶尔多出一个”，不写概率。
     */
    static List<FluidItemRecipe> byproductRecipes() {
        List<FluidItemRecipe> list = new ArrayList<>();
        for (DistillationRecipe r : DistillationRecipe.recipeList) {
            if (r.itemOutput == null || r.itemOutput.length == 0 || r.fluidOutput == null || r.fluidOutput.length == 0 || r.input == null) {
                continue;
            }
            List<FluidStack> rest = new ArrayList<>(Arrays.asList(r.fluidOutput).subList(1, r.fluidOutput.length));
            List<String> notes = new ArrayList<>();
            List<ItemStack> items = new ArrayList<>();
            for (ItemStack item : r.itemOutput) {
                items.add(item);
                notes.add(I18n.format("youranmod.jei.distillation_tower.byproduct", item.getDisplayName()));
            }
            list.add(new FluidItemRecipe(r.input, Collections.<ItemStack>emptyList(), r.fluidOutput[0], rest, items, notes));
        }
        return list;
    }
}
