package moe.qingu.youran.compat.jei;

import mezz.jei.api.IGuiHelper;
import mezz.jei.api.IModPlugin;
import mezz.jei.api.IJeiRuntime;
import mezz.jei.api.IModRegistry;
import mezz.jei.api.ISubtypeRegistry;
import mezz.jei.api.JEIPlugin;
import mezz.jei.api.ingredients.VanillaTypes;
import mezz.jei.api.recipe.IRecipeCategoryRegistration;
import moe.qingu.youran.YouranMod;
import moe.qingu.youran.fluid.FluidPurity;
import moe.qingu.youran.item.ItemSiliconWafer;
import moe.qingu.youran.logic.purity.WaferMold;
import moe.qingu.youran.registry.ModBlocks;
import moe.qingu.youran.registry.ModItems;
import moe.qingu.youran.logic.purity.Purity;
import moe.qingu.youran.registry.ModFluids;
import net.minecraft.item.ItemStack;
import net.minecraftforge.fluids.FluidStack;
import net.minecraftforge.fml.common.Loader;

import java.util.ArrayList;
import java.util.List;

/**
 * JEI 插件（软依赖：JEI 按 {@code @JEIPlugin} 注解找到它，没装 JEI 时不加载）。四个分类：
 * <ul>
 *     <li>IC2 流体/固体装罐机的反应配方表（富集模式，“流体 + 物品 → 流体 + 物品”），全部配方；</li>
 *     <li>IC2 太阳能蒸馏机：原来的水 → 蒸馏水，以及三氯氢硅提纯；</li>
 *     <li>沉浸原油精馏塔的三氯氢硅提纯（精馏塔原来的原油配方沉浸原油自己没有接 JEI，这里也不放）；</li>
 *     <li>IC2 洗矿机洗硅晶圆：每种水一条，写上纯净率与报废率（洗矿机自己的配方 IC2 的 JEI 插件也显示，但看不出水的区别）。</li>
 * </ul>
 * 电弧炉、粉碎机、搅拌机、打粉机、炼油厂、灌装机、热能离心机、溶解机、浇铸台、冶炼炉的新配方由各模组自己的 JEI 插件显示；
 * 按纯度多登记的配方只列 9 个 9 的（沉浸工程按 {@code listInJEI}，匠魂合金在这里藏起来）。
 * 三氯氢硅、混合气、硅晶圆、报废硅晶圆另有说明页。
 */
@JEIPlugin
public final class YouranJeiPlugin implements IModPlugin {
    static final String CANNER = YouranMod.MODID + ".canner_reaction";
    static final String SOLAR_DISTILLER = YouranMod.MODID + ".solar_distiller";
    static final String DISTILLATION_TOWER = YouranMod.MODID + ".distillation_tower";
    static final String ORE_WASHING_WAFER = YouranMod.MODID + ".ore_washing_wafer";
    /** 灌入容器（汞灯补汞、激光腔灌氩）与紫外擦除器：只展示用 */
    static final String CONTAINER_FILL = YouranMod.MODID + ".container_fill";
    static final String UV_ERASER = YouranMod.MODID + ".uv_eraser";
    /** 匠魂冶炼炉合金的分类（匠魂自己的 JEI 插件） */
    static final String TIC_ALLOY = "tconstruct.alloy";
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
                    Ic2Categories.canner(gui, CANNER, Ic2Machines.canner()),
                    new SolarDistillerCategory(gui, SOLAR_DISTILLER, Ic2Machines.solarDistiller()),
                    Ic2Categories.waferWashing(gui, ORE_WASHING_WAFER, Ic2Machines.oreWashingPlant()),
                    StepCategories.containerFill(gui, CONTAINER_FILL, Ic2Machines.canner()));
        } else {
            registry.addRecipeCategories(StepCategories.containerFill(gui, CONTAINER_FILL, ItemStack.EMPTY));
        }
        if (ModBlocks.uvEraser != null) {
            registry.addRecipeCategories(StepCategories.uvEraser(gui, UV_ERASER, new ItemStack(ModItems.uvEraser)));
        }
        if (petroleum()) {
            registry.addRecipeCategories(PetroleumCategories.distillationTower(gui, DISTILLATION_TOWER,
                    JeiRecipes.item(IP_MULTIBLOCK, IP_TOWER_META)));
        }
    }

    @Override
    public void register(IModRegistry registry) {
        // 主城传送门方块的物品形式只为占住物品编号，没有模型、不该被拿到（同原版下界传送门），JEI 里不显示
        if (ModBlocks.mainCityPortal != null) {
            registry.getJeiHelpers().getIngredientBlacklist().addIngredientToBlacklist(new ItemStack(ModBlocks.mainCityPortal));
        }
        if (ic2()) {
            List<FluidItemRecipe> canner = new ArrayList<>(JeiRecipes.cannerReactions());
            FluidItemRecipe uu = JeiSteps.improvedResist();
            if (uu != null) {
                canner.add(uu);
            }
            registry.addRecipes(canner, CANNER);
            registry.addRecipeCatalyst(Ic2Machines.canner(), CANNER);
            List<FluidItemRecipe> solar = new ArrayList<>();
            FluidItemRecipe water = JeiRecipes.fluid("water", 1, "ic2distilled_water", 1, "youranmod.jei.solar_distiller.water");
            if (water != null) {
                solar.add(water);
            }
            solar.addAll(JeiRecipes.purification("youranmod.jei.solar_distiller.purify", Purity.BATCH_INPUT));
            registry.addRecipes(solar, SOLAR_DISTILLER);
            registry.addRecipeCatalyst(Ic2Machines.solarDistiller(), SOLAR_DISTILLER);
            registry.addRecipes(JeiRecipes.waferWashing(Ic2Waters.present()), ORE_WASHING_WAFER);
            registry.addRecipeCatalyst(Ic2Machines.oreWashingPlant(), ORE_WASHING_WAFER);
        }
        if (petroleum()) {
            registry.addRecipes(JeiRecipes.purification("youranmod.jei.distillation_tower.purify", PetroleumMachines.energy()),
                    DISTILLATION_TOWER);
            registry.addRecipes(PetroleumMachines.byproductRecipes(), DISTILLATION_TOWER);
            ItemStack tower = JeiRecipes.item(IP_MULTIBLOCK, IP_TOWER_META);
            if (!tower.isEmpty()) {
                registry.addRecipeCatalyst(tower, DISTILLATION_TOWER);
            }
        }
        registry.addRecipes(JeiSteps.containerFill(), CONTAINER_FILL);
        registry.addRecipes(JeiSteps.uvEraser(), UV_ERASER);
        registry.addRecipeCatalyst(new ItemStack(ModItems.mercuryLamp), CONTAINER_FILL);
        registry.addRecipeCatalyst(new ItemStack(ModItems.uvEraser), UV_ERASER);
        if (ic2()) {
            registry.addRecipeCatalyst(Ic2Machines.canner(), CONTAINER_FILL);
        }
        if (Loader.isModLoaded("tconstruct")) {
            TicSteps.register(registry);
        }
        registry.addIngredientInfo(JeiSteps.lampExample(), VanillaTypes.ITEM, "youranmod.jei.mercury_lamp.info");
        registry.addIngredientInfo(new ItemStack(ModItems.eprom), VanillaTypes.ITEM, "youranmod.jei.eprom.info");
        registry.addIngredientInfo(new ItemStack(ModItems.epromProgrammer), VanillaTypes.ITEM, "youranmod.jei.eprom_programmer.info");
        registry.addIngredientInfo(JeiSteps.etchedBoards(), VanillaTypes.ITEM, "youranmod.jei.hand_punch.info");
        registry.addIngredientInfo(new ItemStack(ModItems.floppyJacket, 1, moe.qingu.youran.item.ItemFloppyJacket.UNPUNCHED), VanillaTypes.ITEM,
                "youranmod.jei.hand_punch_jacket.info");
        if (ModFluids.trichlorosilane != null) {
            FluidStack fluid = FluidPurity.trichlorosilane(1000, Purity.DEFAULT_NINES);
            registry.addIngredientInfo(fluid, VanillaTypes.FLUID, "youranmod.jei.trichlorosilane.info");
        }
        if (ModFluids.trichlorosilaneHydrogen != null) {
            registry.addIngredientInfo(FluidPurity.mixture(1000, Purity.WAFER_GRADE_NINES), VanillaTypes.FLUID,
                    "youranmod.jei.trichlorosilane_hydrogen.info");
        }
        List<ItemStack> wafers = new ArrayList<>();
        for (WaferMold mold : WaferMold.values()) {
            wafers.add(ItemSiliconWafer.make(ModItems.siliconWafer, Purity.WAFER_GRADE_NINES, mold));
            wafers.add(ItemSiliconWafer.make(ModItems.precisionSiliconWafer, Purity.WAFER_GRADE_NINES, mold));
        }
        registry.addIngredientInfo(wafers, VanillaTypes.ITEM, "youranmod.jei.wafer.info");
        registry.addIngredientInfo(new ItemStack(ModItems.scrapSiliconWafer), VanillaTypes.ITEM, "youranmod.jei.scrap_wafer.info");
        registry.addIngredientInfo(new ItemStack(ModItems.naphthalene), VanillaTypes.ITEM, "youranmod.jei.naphthalene.info");
        registry.addIngredientInfo(new ItemStack(ModItems.locatorModule), VanillaTypes.ITEM, "youranmod.jei.locator_module.info");
        // DUV 一档（服主 11-16）：怎么充气、怎么用；配比只给“预计输出”，不写最佳配比（要玩家自己调）
        registry.addIngredientInfo(new ItemStack(ModItems.arfLaserCavity), VanillaTypes.ITEM, "youranmod.jei.arf_laser_cavity.info");
        registry.addIngredientInfo(new ItemStack(ModItems.heneLaserTube), VanillaTypes.ITEM, "youranmod.jei.hene_laser_tube.info");
        registry.addIngredientInfo(new ItemStack(ModItems.excimerLightSource), VanillaTypes.ITEM, "youranmod.jei.excimer_light_source.info");
        if (ModFluids.photoresist != null) {
            registry.addIngredientInfo(moe.qingu.youran.fluid.FluidVariants.photoresist(moe.qingu.youran.logic.litho.ResistType.IMPROVED_POSITIVE, 1000),
                    VanillaTypes.FLUID, "youranmod.jei.improved_resist.info");
        }
    }

    @Override
    public void registerItemSubtypes(ISubtypeRegistry subtypes) {
        // 硅晶圆、高精硅晶圆按铸模材质分开（纯度不分，免得 JEI 里一种纯度一个格子）
        ISubtypeRegistry.ISubtypeInterpreter byMold = stack -> {
            WaferMold mold = ItemSiliconWafer.mold(stack);
            return mold == null ? ISubtypeRegistry.ISubtypeInterpreter.NONE : mold.id;
        };
        subtypes.registerSubtypeInterpreter(ModItems.siliconWafer, byMold);
        subtypes.registerSubtypeInterpreter(ModItems.precisionSiliconWafer, byMold);
    }

    @Override
    public void onRuntimeAvailable(IJeiRuntime runtime) {
        // IC2 热能离心机分铜矿偶尔多出小撮钼粉：在 JEI 里换成带钼粉的展示版
        if (ic2()) {
            Ic2CentrifugeSteps.apply(runtime.getRecipeRegistry());
        }
        // 冶炼炉按纯度多登记的混合气合金配方（9 个 9 以外的）不列（炼油厂、灌装机的由沉浸工程按 listInJEI 不列）
        if (Loader.isModLoaded("tconstruct")) {
            TicHidden.hide(runtime.getRecipeRegistry(), TIC_ALLOY);
        }
    }
}
