package moe.qingu.youran.compat.jei;

import flaxbeard.immersivepetroleum.api.crafting.DistillationRecipe;
import moe.qingu.youran.compat.immersivepetroleum.TrichlorosilaneDistillation;

/** 沉浸原油的精馏塔（JEI 说明里的电耗）。只有装了沉浸原油时才会被加载 */
final class PetroleumMachines {
    private PetroleumMachines() {
    }

    /** 提纯一次的电耗：乘上沉浸原油配置里的电耗倍率（同精馏塔算原油配方的办法） */
    static int energy() {
        return (int) Math.floor(TrichlorosilaneDistillation.ENERGY * DistillationRecipe.energyModifier);
    }
}
