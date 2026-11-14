package moe.qingu.youran.compat.ic2;

import ic2.api.recipe.Recipes;
import moe.qingu.youran.YouranMod;
import moe.qingu.youran.registry.ModItems;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;

/** 加进 IC2 机器的配方。只有装了 IC2 时才会被加载（{@code YouranMod#postInit} 先查 IC2 在不在） */
public final class Ic2Recipes {
    /** 热能离心机要的最低热量：同 IC2 自己的铁相关配方（粉碎铁矿石、纯净的粉碎铁矿石，见 IC2 的 thermal_centrifuge.ini） */
    static final int CENTRIFUGE_MIN_HEAT = 1500;

    private Ic2Recipes() {
    }

    /** 热能离心机：1 铁粉（dustIron）→ 3 小撮磁粉 */
    public static void register() {
        NBTTagCompound meta = new NBTTagCompound();
        meta.setInteger("minHeat", CENTRIFUGE_MIN_HEAT);
        boolean ok = Recipes.centrifuge.addRecipe(Recipes.inputFactory.forOreDict("dustIron"), meta, false,
                new ItemStack(ModItems.smallMagnetiteDust, 3));
        if (!ok) {
            YouranMod.LOG.error("IC2 热能离心机配方（铁粉 → 3 小撮磁粉）没加上：已有输入相同的配方");
        }
    }
}
