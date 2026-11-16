package moe.qingu.youran.recipe;

import moe.qingu.youran.YouranMod;
import moe.qingu.youran.compat.alchemistry.AlchemistryCompat;
import moe.qingu.youran.fluid.FluidVariants;
import moe.qingu.youran.logic.litho.ResistType;
import moe.qingu.youran.logic.reaction.FluidItemReaction;
import moe.qingu.youran.logic.reaction.ItemIngredient;
import moe.qingu.youran.registry.ModFluids;
import moe.qingu.youran.registry.ModItems;
import net.minecraft.item.ItemStack;
import net.minecraft.util.NonNullList;
import net.minecraftforge.fluids.FluidRegistry;
import net.minecraftforge.fluids.FluidStack;

/**
 * 负胶链（服主 11-15 定，光刻.md“负胶第一步”“负胶第二步”）：
 * <pre>
 * 熔融锡 / 小撮锡粉 + 氯气 → 四氯化锡（冶炼炉、炼油厂、装罐机、搅拌机）；四氯化锡 ⇄ 化合物（液化器 / 蒸发器）
 * 粘性树脂 / 橡胶 + 四氯化锡 → 环化橡胶（装罐机、灌装机、匠魂浇铸台；SCP-914 那几条在整合包配置里）
 * 硝石粉 + 辅料 10 铵 + 1 钠锭 → 叠氮化钠粉（电弧炉）
 * （通用材料一律按矿物词典：树脂 itemResin、橡胶 itemRubber、小撮锡粉 dustTinyTin、硝石粉 dustSaltpeter、钠锭 ingotSodium；服主 11-16）
 * 杂酚油 → 芳烃 + 甲酚（沉浸原油精馏塔）
 * 叠氮化钠粉 + 芳烃 → 带叠氮基的芳烃（装罐机、搅拌机）
 * 带叠氮基的芳烃 + 盐酸 → 双叠氮感光剂（炼油厂、冶炼炉）
 * 环化橡胶 + 双叠氮感光剂 → 光刻胶（负胶）（装罐机、搅拌机）
 * </pre>
 * 各模组都是软依赖：用到别的模组的类的放在内部类里，装了才加载。这里的数量都是服主 11-15 定的（精馏塔的量 Claude 定）。
 */
public final class ResistRecipes {
    /** 匠魂的熔融锡、本模组的氯气、盐酸（SCP: Lockdown） */
    static final String MOLTEN_TIN = "tin";
    static final String HCL = "h_cl_fluid";
    /** 杂酚油：沉浸工程的、IC2 的 */
    static final String[] CREOSOTES = {"creosote", "ic2creosote"};

    // ---- 服主 11-15 定的量 ----
    public static final int ALLOY_TIN = 9;
    public static final int ALLOY_CHLORINE = 125;
    public static final int ALLOY_SNCL4 = 9;
    public static final int REFINERY_TIN = 72;
    public static final int REFINERY_CHLORINE = 1000;
    public static final int REFINERY_SNCL4 = 72;
    public static final int TINY_TIN = 4;
    public static final int TINY_TIN_CHLORINE = 1000;
    public static final int TINY_TIN_SNCL4 = 64;
    public static final int SNCL4_COMPOUNDS = 16;
    public static final int SNCL4_COMPOUND_MB = 144;
    public static final int RESIN_SNCL4 = 20;
    public static final int RUBBER_SNCL4 = 15;
    public static final int AZIDE_AMMONIUM = 10;
    public static final int AZIDE_SODIUM = 1;
    public static final int AZIDE_ARENE = 100;
    public static final int SENSITIZER_ARENE = 100;
    public static final int SENSITIZER_HCL = 10;
    public static final int SENSITIZER_OUT = 100;
    public static final int SENSITIZER_ALLOY_ARENE = 10;
    public static final int SENSITIZER_ALLOY_HCL = 1;
    public static final int SENSITIZER_ALLOY_OUT = 10;
    public static final int RESIST_SENSITIZER = 25;
    // ---- Claude 11-16 定 ----
    /** 精馏塔：每 100 mB 杂酚油 → 50 mB 芳烃 + 25 mB 甲酚（余下当沥青、不出） */
    public static final int CREOSOTE_IN = 100;
    public static final int CREOSOTE_ARENE = 50;
    public static final int CREOSOTE_CRESOL = 25;
    /** 精馏塔一份的电耗（倍率前）：按原油 75 mB / 2048 Flux 折算 */
    public static final int CREOSOTE_ENERGY = 2731;
    /** 电弧炉叠氮化钠：时间、每 tick 电耗（同粗硅） */
    public static final int AZIDE_ARC_TIME = 400;
    public static final int AZIDE_ARC_ENERGY = 512;
    /** 搅拌机电耗（每份）、炼油厂每 tick 电耗（同生物柴油） */
    public static final int MIXER_ENERGY = 1600;
    public static final int REFINERY_ENERGY = 80;
    /** 匠魂浇环化橡胶的时间（tick） */
    public static final int CASTING_TIME = 60;

    /** 粘性树脂、橡胶按矿物词典（IC2 注册 itemResin、itemRubber） */
    static final String ORE_RESIN = "itemResin";
    static final String ORE_RUBBER = "itemRubber";

    private ResistRecipes() {
    }

    // ---- 装罐机（富集模式）反应配方：两端、配方注册事件里登记（CannerReactions.registerBuiltins 调用）----

    /** 装罐机的负胶链反应 */
    static java.util.List<FluidItemReaction<ItemStack, ReactionItem>> cannerReactions() {
        java.util.List<FluidItemReaction<ItemStack, ReactionItem>> list = new java.util.ArrayList<>();
        list.add(new FluidItemReaction<>(CannerReactions.CHLORINE, TINY_TIN_CHLORINE, ItemIngredient.ore("dustTinyTin", TINY_TIN),
                "tin_tetrachloride", TINY_TIN_SNCL4, null));
        list.add(new FluidItemReaction<>("tin_tetrachloride", RESIN_SNCL4, ItemIngredient.ore(ORE_RESIN, 1),
                null, 0, new ReactionItem(YouranMod.MODID + ":cyclized_rubber", 0, 1, null)));
        list.add(new FluidItemReaction<>("tin_tetrachloride", RUBBER_SNCL4, ItemIngredient.ore(ORE_RUBBER, 1),
                null, 0, new ReactionItem(YouranMod.MODID + ":cyclized_rubber", 0, 1, null)));
        list.add(new FluidItemReaction<>(FluidVariants.ARENE, AZIDE_ARENE, ItemIngredient.item(YouranMod.MODID + ":sodium_azide_dust", 0, 1),
                FluidVariants.AZIDE_ARENE_KEY, AZIDE_ARENE, null));
        list.add(new FluidItemReaction<>("bisazide_sensitizer", RESIST_SENSITIZER, ItemIngredient.item(YouranMod.MODID + ":cyclized_rubber", 0, 1),
                FluidVariants.photoresistKey(ResistType.NEGATIVE), RESIST_SENSITIZER, null));
        return list;
    }

    // ---- 后初始化 ----

    /** 后初始化：按装了哪些模组加配方（{@link CompatRecipes} 调用） */
    public static void postInit(boolean ic2, boolean ie, boolean tic, boolean ip, boolean alchemistry) {
        AlchemistryCompat.registerFluidConversion(AlchemistryCompat.TIN_TETRACHLORIDE, SNCL4_COMPOUNDS, ModFluids.tinTetrachloride, SNCL4_COMPOUND_MB);
        if (ie) {
            Ie.register(ic2, alchemistry);
        }
        if (tic) {
            Tic.register(ic2);
        }
        if (ip) {
            Ip.register();
        }
    }

    static boolean hasOre(String ore) {
        return net.minecraftforge.oredict.OreDictionary.doesOreNameExist(ore) && !net.minecraftforge.oredict.OreDictionary.getOres(ore, false).isEmpty();
    }

    /** 沉浸工程：炼油厂（四氯化锡、双叠氮感光剂）、搅拌机（四氯化锡、带叠氮基的芳烃、光刻胶）、灌装机（环化橡胶）、电弧炉（叠氮化钠） */
    static final class Ie {
        static void register(boolean ic2, boolean alchemistry) {
            FluidStack tin = FluidRegistry.getFluidStack(MOLTEN_TIN, REFINERY_TIN);
            FluidStack chlorine = new FluidStack(ModFluids.chlorine, REFINERY_CHLORINE);
            if (tin != null) {
                blusunrize.immersiveengineering.api.crafting.RefineryRecipe.addRecipe(new FluidStack(ModFluids.tinTetrachloride, REFINERY_SNCL4),
                        tin, chlorine, REFINERY_ENERGY);
                YouranMod.LOG.info("已加沉浸工程炼油厂配方：{} mB 熔融锡 + {} mB 氯气 → {} mB 四氯化锡", REFINERY_TIN, REFINERY_CHLORINE, REFINERY_SNCL4);
            } else {
                YouranMod.LOG.info("没有熔融锡（匠魂），不加沉浸工程炼油厂的四氯化锡配方");
            }
            FluidStack hcl = FluidRegistry.getFluidStack(HCL, SENSITIZER_HCL);
            if (hcl != null) {
                blusunrize.immersiveengineering.api.crafting.RefineryRecipe.addRecipe(new FluidStack(ModFluids.bisazideSensitizer, SENSITIZER_OUT),
                        FluidVariants.azideArene(SENSITIZER_ARENE), hcl, REFINERY_ENERGY);
                blusunrize.immersiveengineering.api.crafting.RefineryRecipe found =
                        blusunrize.immersiveengineering.api.crafting.RefineryRecipe.findRecipe(FluidVariants.azideArene(SENSITIZER_ARENE), hcl);
                if (found == null || found.output.getFluid() != ModFluids.bisazideSensitizer) {
                    YouranMod.LOG.error("沉浸工程炼油厂的双叠氮感光剂配方没生效：找到的是 {}", found == null ? null : found.output);
                }
            }
            // 搅拌机
            if (moe.qingu.youran.compat.immersiveengineering.RatioMixerProcess.active()) {
                blusunrize.immersiveengineering.api.crafting.MixerRecipe.recipeList.add(new moe.qingu.youran.compat.immersiveengineering.RatioMixerRecipe(
                        new FluidStack(ModFluids.tinTetrachloride, TINY_TIN_SNCL4), new FluidStack(ModFluids.chlorine, TINY_TIN_CHLORINE),
                        new Object[]{new blusunrize.immersiveengineering.api.crafting.IngredientStack("dustTinyTin", TINY_TIN)}, MIXER_ENERGY));
            } else {
                YouranMod.LOG.warn("沉浸工程搅拌机的 Mixin 没有生效，不加四氯化锡配方（搅拌机本来只能 1:1 换流体）");
            }
            blusunrize.immersiveengineering.api.crafting.MixerRecipe.addRecipe(FluidVariants.azideArene(AZIDE_ARENE),
                    new FluidStack(ModFluids.arene, AZIDE_ARENE), new Object[]{new ItemStack(ModItems.sodiumAzideDust)}, MIXER_ENERGY);
            blusunrize.immersiveengineering.api.crafting.MixerRecipe.addRecipe(FluidVariants.photoresist(ResistType.NEGATIVE, RESIST_SENSITIZER),
                    new FluidStack(ModFluids.bisazideSensitizer, RESIST_SENSITIZER), new Object[]{new ItemStack(ModItems.cyclizedRubber)}, MIXER_ENERGY);
            NonNullList<ItemStack> azideIn = NonNullList.withSize(1, new ItemStack(ModItems.sodiumAzideDust));
            blusunrize.immersiveengineering.api.crafting.MixerRecipe m =
                    blusunrize.immersiveengineering.api.crafting.MixerRecipe.findRecipe(new FluidStack(ModFluids.arene, 1000), azideIn);
            if (m == null || !FluidVariants.isAzideArene(m.fluidOutput)) {
                YouranMod.LOG.error("沉浸工程搅拌机的带叠氮基芳烃配方没生效：找到的是 {}", m == null ? null : m.fluidOutput);
            }
            // 灌装机：环化橡胶
            if (hasOre(ORE_RESIN)) {
                blusunrize.immersiveengineering.api.crafting.BottlingMachineRecipe.addRecipe(new ItemStack(ModItems.cyclizedRubber), ORE_RESIN,
                        new FluidStack(ModFluids.tinTetrachloride, RESIN_SNCL4));
            }
            if (hasOre(ORE_RUBBER)) {
                blusunrize.immersiveengineering.api.crafting.BottlingMachineRecipe.addRecipe(new ItemStack(ModItems.cyclizedRubber), ORE_RUBBER,
                        new FluidStack(ModFluids.tinTetrachloride, RUBBER_SNCL4));
            }
            // 电弧炉：叠氮化钠
            ItemStack ammonium = AlchemistryCompat.stack(AlchemistryCompat.AMMONIUM, AZIDE_AMMONIUM);
            if (ammonium.isEmpty() || !hasOre("ingotSodium")) {
                YouranMod.LOG.info("没有炼金化学的铵或钠锭，不加沉浸工程电弧炉的叠氮化钠配方");
            } else {
                blusunrize.immersiveengineering.api.crafting.ArcFurnaceRecipe.addRecipe(new ItemStack(ModItems.sodiumAzideDust), "dustSaltpeter",
                        ItemStack.EMPTY, AZIDE_ARC_TIME, AZIDE_ARC_ENERGY,
                        new blusunrize.immersiveengineering.api.crafting.IngredientStack(ammonium),
                        new blusunrize.immersiveengineering.api.crafting.IngredientStack("ingotSodium", AZIDE_SODIUM));
                YouranMod.LOG.info("已加沉浸工程电弧炉配方：硝石粉 + {} 铵 + {} 钠锭 → 叠氮化钠粉", AZIDE_AMMONIUM, AZIDE_SODIUM);
            }
            YouranMod.LOG.info("已加沉浸工程的负胶链配方（炼油厂、搅拌机、灌装机）");
        }
    }

    /** 匠魂：冶炼炉合金（四氯化锡、双叠氮感光剂）、浇铸台（环化橡胶，物品放台上、浇上去） */
    static final class Tic {
        static void register(boolean ic2) {
            FluidStack tin = FluidRegistry.getFluidStack(MOLTEN_TIN, ALLOY_TIN);
            if (tin != null) {
                slimeknights.tconstruct.library.TinkerRegistry.registerAlloy(new FluidStack(ModFluids.tinTetrachloride, ALLOY_SNCL4), tin,
                        new FluidStack(ModFluids.chlorine, ALLOY_CHLORINE));
            }
            FluidStack hcl = FluidRegistry.getFluidStack(HCL, SENSITIZER_ALLOY_HCL);
            if (hcl != null) {
                slimeknights.tconstruct.library.TinkerRegistry.registerAlloy(new FluidStack(ModFluids.bisazideSensitizer, SENSITIZER_ALLOY_OUT),
                        FluidVariants.azideArene(SENSITIZER_ALLOY_ARENE), hcl);
            }
            if (hasOre(ORE_RESIN)) {
                slimeknights.tconstruct.library.TinkerRegistry.registerTableCasting(new slimeknights.tconstruct.library.smeltery.CastingRecipe(
                        new ItemStack(ModItems.cyclizedRubber), slimeknights.mantle.util.RecipeMatch.of(ORE_RESIN),
                        new FluidStack(ModFluids.tinTetrachloride, RESIN_SNCL4), CASTING_TIME, true, false));
            }
            if (hasOre(ORE_RUBBER)) {
                slimeknights.tconstruct.library.TinkerRegistry.registerTableCasting(new slimeknights.tconstruct.library.smeltery.CastingRecipe(
                        new ItemStack(ModItems.cyclizedRubber), slimeknights.mantle.util.RecipeMatch.of(ORE_RUBBER),
                        new FluidStack(ModFluids.tinTetrachloride, RUBBER_SNCL4), CASTING_TIME, true, false));
            }
            YouranMod.LOG.info("已加匠魂的负胶链配方（冶炼炉合金、浇铸台）");
        }
    }

    /** 沉浸原油精馏塔：杂酚油 → 芳烃 + 甲酚 */
    static final class Ip {
        static void register() {
            int added = 0;
            for (String name : CREOSOTES) {
                FluidStack creosote = FluidRegistry.getFluidStack(name, CREOSOTE_IN);
                if (creosote == null) {
                    continue;
                }
                flaxbeard.immersivepetroleum.api.crafting.DistillationRecipe.addRecipe(new FluidStack[]{
                                new FluidStack(ModFluids.arene, CREOSOTE_ARENE), new FluidStack(ModFluids.cresol, CREOSOTE_CRESOL)},
                        new ItemStack[0], creosote, CREOSOTE_ENERGY, 1, new float[0]);
                added++;
            }
            YouranMod.LOG.info("已加沉浸原油精馏塔配方：{} 种杂酚油 → 芳烃 + 甲酚", added);
        }
    }
}
