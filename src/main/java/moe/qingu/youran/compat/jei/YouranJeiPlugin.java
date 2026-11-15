package moe.qingu.youran.compat.jei;

import mezz.jei.api.IGuiHelper;
import mezz.jei.api.IModPlugin;
import mezz.jei.api.IModRegistry;
import mezz.jei.api.JEIPlugin;
import mezz.jei.api.ingredients.VanillaTypes;
import mezz.jei.api.recipe.IRecipeCategoryRegistration;
import moe.qingu.youran.YouranMod;
import moe.qingu.youran.fluid.FluidPurity;
import moe.qingu.youran.logic.purity.Purity;
import moe.qingu.youran.registry.ModFluids;
import net.minecraft.item.ItemStack;
import net.minecraftforge.fluids.FluidStack;
import net.minecraftforge.fml.common.Loader;

import java.util.ArrayList;
import java.util.List;

/**
 * JEI 插件（软依赖：JEI 按 {@code @JEIPlugin} 注解找到它，没装 JEI 时不加载）。三个分类：
 * <ul>
 *     <li>IC2 流体/固体装罐机的反应配方表（富集模式，“流体 + 物品 → 流体 + 物品”），全部配方；</li>
 *     <li>IC2 太阳能蒸馏机：原来的水 → 蒸馏水，以及三氯氢硅提纯；</li>
 *     <li>沉浸原油精馏塔的三氯氢硅提纯（精馏塔原来的原油配方沉浸原油自己没有接 JEI，这里也不放）。</li>
 * </ul>
 * 电弧炉、粉碎机、搅拌机、打粉机的新配方由沉浸工程、IC2 自己的 JEI 插件显示。三氯氢硅另有一页说明（纯度怎么记）。
 */
@JEIPlugin
public final class YouranJeiPlugin implements IModPlugin {
    static final String CANNER = YouranMod.MODID + ".canner_reaction";
    static final String SOLAR_DISTILLER = YouranMod.MODID + ".solar_distiller";
    static final String DISTILLATION_TOWER = YouranMod.MODID + ".distillation_tower";
    /** 沉浸原油的精馏塔（多方块的“主方块”物品，沉浸原油自己的多方块示意图也用它） */
    static final String IP_MULTIBLOCK = "immersivepetroleum:metal_multiblock";
    static final int IP_TOWER_META = 1;

    private static boolean ic2() {
        return Loader.isModLoaded("ic2");
    }

    private static boolean petroleum() {
        return Loader.isModLoaded("immersivepetroleum");
    }

    @Override
    public void registerCategories(IRecipeCategoryRegistration registry) {
        IGuiHelper gui = registry.getJeiHelpers().getGuiHelper();
        if (ic2()) {
            registry.addRecipeCategories(
                    new FluidItemCategory(gui, CANNER, "youranmod.jei.canner_reaction", Ic2Machines.canner()),
                    new FluidItemCategory(gui, SOLAR_DISTILLER, "youranmod.jei.solar_distiller", Ic2Machines.solarDistiller()));
        }
        if (petroleum()) {
            registry.addRecipeCategories(new FluidItemCategory(gui, DISTILLATION_TOWER, "youranmod.jei.distillation_tower",
                    JeiRecipes.item(IP_MULTIBLOCK, IP_TOWER_META)));
        }
    }

    @Override
    public void register(IModRegistry registry) {
        if (ic2()) {
            registry.addRecipes(JeiRecipes.cannerReactions(), CANNER);
            registry.addRecipeCatalyst(Ic2Machines.canner(), CANNER);
            List<FluidItemRecipe> solar = new ArrayList<>();
            FluidItemRecipe water = JeiRecipes.fluid("water", 1, "ic2distilled_water", 1, "youranmod.jei.solar_distiller.water");
            if (water != null) {
                solar.add(water);
            }
            solar.addAll(JeiRecipes.purification("youranmod.jei.solar_distiller.purify", Purity.BATCH_INPUT));
            registry.addRecipes(solar, SOLAR_DISTILLER);
            registry.addRecipeCatalyst(Ic2Machines.solarDistiller(), SOLAR_DISTILLER);
        }
        if (petroleum()) {
            registry.addRecipes(JeiRecipes.purification("youranmod.jei.distillation_tower.purify", PetroleumMachines.energy()),
                    DISTILLATION_TOWER);
            ItemStack tower = JeiRecipes.item(IP_MULTIBLOCK, IP_TOWER_META);
            if (!tower.isEmpty()) {
                registry.addRecipeCatalyst(tower, DISTILLATION_TOWER);
            }
        }
        if (ModFluids.trichlorosilane != null) {
            FluidStack fluid = FluidPurity.trichlorosilane(1000, Purity.DEFAULT_NINES);
            registry.addIngredientInfo(fluid, VanillaTypes.FLUID, "youranmod.jei.trichlorosilane.info");
        }
    }
}
