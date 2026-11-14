package moe.qingu.youran.compat.tconstruct;

import moe.qingu.youran.YouranMod;
import moe.qingu.youran.registry.ModFluids;
import net.minecraftforge.fluids.FluidRegistry;
import net.minecraftforge.fluids.FluidStack;

/**
 * 加进匠魂冶炼炉的合金配方。按类名反射调用 {@code TinkerRegistry.registerAlloy(FluidStack, FluidStack...)}，不编译依赖匠魂；
 * 没装匠魂时不调用（{@code recipe.CompatRecipes} 先查）。
 */
public final class TicRecipes {
    private TicRecipes() {
    }

    /**
     * 乙醇氧化成乙酸：20 mB 乙醇 + 13 mB 氧气 → 20 mB 乙酸。与沉浸工程炼油厂的 500 + 325 → 500 同一比例（÷25），
     * 合金配方习惯用小整数。要有沉浸工程的乙醇与 IC2 的氧气。
     */
    public static void registerAceticAcid() {
        FluidStack ethanol = FluidRegistry.getFluidStack("ethanol", 20);
        FluidStack oxygen = FluidRegistry.getFluidStack("ic2oxygen", 13);
        if (ethanol == null || oxygen == null) {
            YouranMod.LOG.info("没有流体 ethanol 或 ic2oxygen，不加匠魂冶炼炉的乙酸合金配方");
            return;
        }
        try {
            Class.forName("slimeknights.tconstruct.library.TinkerRegistry")
                    .getMethod("registerAlloy", FluidStack.class, FluidStack[].class)
                    .invoke(null, new FluidStack(ModFluids.aceticAcid, 20), new FluidStack[]{ethanol, oxygen});
            YouranMod.LOG.info("已加匠魂冶炼炉合金配方：20 mB 乙醇 + 13 mB 氧气 → 20 mB 乙酸");
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            YouranMod.LOG.error("加匠魂冶炼炉的乙酸合金配方失败", e);
        }
    }
}
