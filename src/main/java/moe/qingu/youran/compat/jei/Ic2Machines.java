package moe.qingu.youran.compat.jei;

import ic2.api.item.IC2Items;
import net.minecraft.item.ItemStack;

/** IC2 的机器物品（JEI 分类的图标与“在哪做”）。只有装了 IC2 时才会被加载 */
final class Ic2Machines {
    private Ic2Machines() {
    }

    static ItemStack canner() {
        return orEmpty(IC2Items.getItem("te", "canner"));
    }

    static ItemStack solarDistiller() {
        return orEmpty(IC2Items.getItem("te", "solar_distiller"));
    }

    private static ItemStack orEmpty(ItemStack stack) {
        return stack == null ? ItemStack.EMPTY : stack;
    }
}
