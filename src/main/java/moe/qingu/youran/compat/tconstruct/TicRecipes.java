package moe.qingu.youran.compat.tconstruct;

import moe.qingu.youran.YouranMod;
import moe.qingu.youran.fluid.FluidPurity;
import moe.qingu.youran.logic.purity.Purity;
import moe.qingu.youran.logic.purity.WaferRules;
import moe.qingu.youran.registry.ModFluids;
import net.minecraftforge.fluids.FluidRegistry;
import net.minecraftforge.fluids.FluidStack;
import slimeknights.tconstruct.library.TinkerRegistry;
import slimeknights.tconstruct.library.smeltery.AlloyRecipe;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 加进匠魂冶炼炉的合金配方。乙酸那条按类名反射调用 {@code TinkerRegistry.registerAlloy(FluidStack, FluidStack...)}（写在编译依赖匠魂之前）；
 * 芯片线的直接调匠魂的接口（只编译依赖，不打包）。没装匠魂时不加载这个类（{@code recipe.CompatRecipes} 先查）。
 */
public final class TicRecipes {
    /** 只为不同纯度多登记的合金配方（9 个 9 以外的），JEI 里藏起来 */
    private static final List<AlloyRecipe> UNLISTED_ALLOYS = new ArrayList<>();

    private TicRecipes() {
    }

    /** JEI 里不列的合金配方 */
    public static List<AlloyRecipe> unlistedAlloys() {
        return Collections.unmodifiableList(UNLISTED_ALLOYS);
    }

    /**
     * 混合气（芯片线第 6 步）：1 mB 三氯氢硅 + 4 mB 氢气 → 5 mB 三氯氢硅氢气混合气（同炼油厂的 10 + 40 → 50）。
     * 冶炼炉按 NBT 比合金配方（{@code FluidStack.containsFluid}），抽、灌也按 NBT，所以 9–32 个 9 每种各一条，
     * 混合气的纯度同输入的三氯氢硅；不到 9 个 9 的没有配方。要装了 IC2（氢气）。
     */
    public static void registerMixture() {
        FluidStack hydrogen = FluidRegistry.getFluidStack("ic2hydrogen", WaferRules.ALLOY_HYDROGEN);
        if (hydrogen == null) {
            YouranMod.LOG.info("没有流体 ic2hydrogen，不加匠魂冶炼炉的混合气合金配方");
            return;
        }
        int added = 0;
        for (int nines : WaferRules.mixtureGrades()) {
            AlloyRecipe recipe = new AlloyRecipe(FluidPurity.mixture(WaferRules.ALLOY_MIXTURE, WaferRules.mixtureNines(nines)),
                    FluidPurity.trichlorosilane(WaferRules.ALLOY_TRICHLOROSILANE, nines), hydrogen.copy());
            TinkerRegistry.registerAlloy(recipe);
            if (nines != Purity.WAFER_GRADE_NINES) {
                UNLISTED_ALLOYS.add(recipe);
            }
            added++;
        }
        YouranMod.LOG.info("已加匠魂冶炼炉合金配方：{} mB 三氯氢硅（{}–{} 个 9，{} 条）+ {} mB 氢气 → {} mB 混合气", WaferRules.ALLOY_TRICHLOROSILANE,
                Purity.WAFER_GRADE_NINES, Purity.MAX_NINES, added, WaferRules.ALLOY_HYDROGEN, WaferRules.ALLOY_MIXTURE);
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
