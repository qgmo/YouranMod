package moe.qingu.youran.compat.jei;

import moe.qingu.youran.compat.ic2.OreWashingHooks;

import java.util.List;

/** 洗矿机认的、整合包里有的水（JEI 用）。只有装了 IC2 时才会被加载 */
final class Ic2Waters {
    private Ic2Waters() {
    }

    static List<String> present() {
        return OreWashingHooks.presentWaters();
    }
}
