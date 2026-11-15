package moe.qingu.youran.recipe;

import moe.qingu.youran.compat.alchemistry.AlchemistryCompat;
import moe.qingu.youran.registry.ModFluids;
import moe.qingu.youran.registry.ModItems;
import net.minecraft.item.ItemStack;
import net.minecraftforge.fml.common.Loader;

/**
 * 加进别的模组机器的配方，后初始化时调用（两端）。各模组都是软依赖：先查装没装，没装时不加载对应的 compat 类。
 * 装罐机反应配方与炼金化学的化合物见 {@link BuiltinRecipes}。
 */
public final class CompatRecipes {
    private CompatRecipes() {
    }

    public static void postInit() {
        boolean ic2 = Loader.isModLoaded("ic2");
        boolean ie = Loader.isModLoaded("immersiveengineering");
        boolean tic = Loader.isModLoaded("tconstruct");
        if (ic2) {
            moe.qingu.youran.compat.ic2.Ic2Recipes.register();
        }
        if (ie) {
            moe.qingu.youran.compat.immersiveengineering.IeRecipes.registerMetalPress();
            moe.qingu.youran.compat.immersiveengineering.IeRecipes.registerPvc();
        }
        if (ie && tic) {
            moe.qingu.youran.compat.immersiveengineering.IeRecipes.registerCobaltMagnetic();
            // 芯片线第 7 步：匠魂“盘”铸模 + 硼锭 + 萤石粉 → 掺杂硅晶圆铸模（电弧炉；硼锭要炼金化学）
            moe.qingu.youran.compat.immersiveengineering.IeRecipes.registerDopedMolds();
        }
        boolean alchemistry = Loader.isModLoaded(AlchemistryCompat.MODID);
        if (ie && alchemistry) {
            moe.qingu.youran.compat.immersiveengineering.IeRecipes.registerCelluloseAcetate();
            // 芯片线“硅”：粗硅（电弧炉）
            moe.qingu.youran.compat.immersiveengineering.IeRecipes.registerSiliconArcFurnace();
            // 硅粉（粉碎机）
            moe.qingu.youran.compat.immersiveengineering.IeRecipes.registerSiliconCrusher();
            // 硼的来源：5 硼硅酸盐渣 → 硼锭 + 二氧化硅粉（电弧炉）
            moe.qingu.youran.compat.immersiveengineering.IeRecipes.registerBoronArcFurnace();
        }
        // 芯片线“硅”：三氯氢硅（搅拌机，要 Mixin 改它的流体比例）
        if (ie) {
            moe.qingu.youran.compat.immersiveengineering.IeRecipes.registerTrichlorosilaneMixer(
                    moe.qingu.youran.compat.immersiveengineering.RatioMixerProcess.active());
        }
        // 乙醇（沉浸工程）氧化成乙酸，氧气是 IC2 的
        if (ie && ic2) {
            moe.qingu.youran.compat.immersiveengineering.IeRecipes.registerRefinery();
        }
        if (tic && ie && ic2) {
            moe.qingu.youran.compat.tconstruct.TicRecipes.registerAceticAcid();
        }
        // 芯片线第 6 步：三氯氢硅（9 个 9）+ 氢气（IC2）→ 混合气
        if (ie && ic2) {
            moe.qingu.youran.compat.immersiveengineering.IeRecipes.registerMixtureRefinery();
        }
        if (tic && ic2) {
            moe.qingu.youran.compat.tconstruct.TicRecipes.registerMixture();
        }
        // 炼金化学的化合机、液化器、蒸发器（在它载入完成时应用 CraftTweaker 的删改之前加；没装时什么都不做）
        AlchemistryCompat.registerCombinerRecipe(new ItemStack(ModItems.magnetiteDust));
        AlchemistryCompat.registerAceticAcidConversion(ModFluids.aceticAcid);
        // 硼的来源：洞穴水晶（电气石）进溶解机（要夸克）
        if (alchemistry) {
            BoronRecipes.registerCrystalDissolver();
            BoronRecipes.registerSlagDissolver(new ItemStack(ModItems.borosilicateSlag));
        }
    }
}
