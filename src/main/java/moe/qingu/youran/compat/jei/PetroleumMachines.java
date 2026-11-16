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
     * 精馏塔配方表（{@code DistillationRecipe.recipeList}）里的全部配方（原油、本模组加的空气分离、杂酚油精馏、整合包脚本加的……运行时读，
     * 不写死）：沉浸原油自己没有接 JEI，这里都列在我们的精馏塔页里。按概率掉出的物品只写“偶尔多出一个”，不写概率。
     * 三氯氢硅提纯是按输入现给的，不在表里，另见 {@link JeiRecipes#purification}。
     */
    static List<FluidItemRecipe> allRecipes() {
        List<FluidItemRecipe> list = new ArrayList<>();
        for (DistillationRecipe r : DistillationRecipe.recipeList) {
            if (r.fluidOutput == null || r.fluidOutput.length == 0 || r.input == null) {
                continue;
            }
            List<FluidStack> rest = new ArrayList<>(Arrays.asList(r.fluidOutput).subList(1, r.fluidOutput.length));
            List<String> notes = new ArrayList<>();
            List<ItemStack> items = new ArrayList<>();
            for (ItemStack item : r.itemOutput == null ? new ItemStack[0] : r.itemOutput) {
                items.add(item);
                notes.add(I18n.format("youranmod.jei.distillation_tower.byproduct", item.getDisplayName()));
            }
            list.add(new FluidItemRecipe(r.input, Collections.<ItemStack>emptyList(), r.fluidOutput[0], rest, items, notes));
        }
        return list;
    }
}
