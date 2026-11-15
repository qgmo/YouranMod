package moe.qingu.youran.compat.ic2;

import ic2.api.recipe.Recipes;
import moe.qingu.youran.YouranMod;
import moe.qingu.youran.compat.alchemistry.AlchemistryCompat;
import moe.qingu.youran.registry.ModItems;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;

/** 加进 IC2 机器的配方。只有装了 IC2 时才会被加载（{@code recipe.CompatRecipes} 先查 IC2 在不在） */
public final class Ic2Recipes {
    /** 热能离心机要的最低热量：同 IC2 自己的铁相关配方（粉碎铁矿石、纯净的粉碎铁矿石，见 IC2 的 thermal_centrifuge.ini） */
    static final int CENTRIFUGE_MIN_HEAT = 1500;

    private Ic2Recipes() {
    }

    public static void register() {
        registerCentrifuge();
        registerRolling();
        registerFloppy();
        registerSiliconMacerator();
    }

    /** 打粉机：1 炼金化学硅锭 → 1 硅粉（芯片线“硅”第 2 步）。要装了炼金化学 */
    static void registerSiliconMacerator() {
        ItemStack ingot = AlchemistryCompat.ingot(AlchemistryCompat.SILICON, 1);
        if (ingot.isEmpty()) {
            YouranMod.LOG.info("没有炼金化学的硅锭，不加 IC2 打粉机的硅粉配方");
            return;
        }
        if (!Recipes.macerator.addRecipe(Recipes.inputFactory.forStack(ingot), null, false, new ItemStack(ModItems.siliconDust))) {
            YouranMod.LOG.error("IC2 打粉机配方（硅锭 → 硅粉）没加上：已有输入相同的配方");
        } else {
            YouranMod.LOG.info("已加 IC2 打粉机配方：硅锭 → 硅粉");
        }
    }

    /** 热能离心机：1 铁粉（dustIron）→ 3 小撮磁粉 */
    static void registerCentrifuge() {
        NBTTagCompound meta = new NBTTagCompound();
        meta.setInteger("minHeat", CENTRIFUGE_MIN_HEAT);
        boolean ok = Recipes.centrifuge.addRecipe(Recipes.inputFactory.forOreDict("dustIron"), meta, false,
                new ItemStack(ModItems.smallMagnetiteDust, 3));
        if (!ok) {
            YouranMod.LOG.error("IC2 热能离心机配方（铁粉 → 3 小撮磁粉）没加上：已有输入相同的配方");
        }
    }

    /** 金属成型机辊压（同锭压成板）：精制盘片 → 抛光盘片，聚氯乙烯团 → 聚氯乙烯片 */
    static void registerRolling() {
        rolling(new ItemStack(ModItems.refinedPlatter), new ItemStack(ModItems.polishedPlatter));
        rolling(new ItemStack(ModItems.pvcLump), new ItemStack(ModItems.pvcSheet));
    }

    private static void rolling(ItemStack input, ItemStack output) {
        if (!Recipes.metalformerRolling.addRecipe(Recipes.inputFactory.forStack(input), null, false, output)) {
            YouranMod.LOG.error("IC2 金属成型机辊压配方（{} → {}）没加上：已有输入相同的配方", input.getItem().getRegistryName(), output.getItem().getRegistryName());
        }
    }

    /**
     * 流体/固体装罐机的固体装罐模式（容器放“罐子”槽，填充物放输入槽）：1 抛光盘片 + 6 聚氯乙烯片 → 1 软盘。
     * 套子只用聚氯乙烯（服主 11-15：聚乙烯太软、标签和衬布粘不牢，不做套子；聚乙烯可以先加氯气变成聚氯乙烯）。
     */
    static void registerFloppy() {
        if (!Recipes.cannerBottle.addRecipe(Recipes.inputFactory.forStack(new ItemStack(ModItems.polishedPlatter), 1),
                Recipes.inputFactory.forStack(new ItemStack(ModItems.pvcSheet), 6), new ItemStack(ModItems.floppyDisk), false)) {
            YouranMod.LOG.error("IC2 固体装罐配方（抛光盘片 + 6 聚氯乙烯片 → 软盘）没加上：已有输入相同的配方");
        }
    }
}
