package moe.qingu.youran.recipe;

import moe.qingu.youran.YouranMod;
import moe.qingu.youran.compat.alchemistry.AlchemistryCompat;
import moe.qingu.youran.fluid.FluidVariants;
import moe.qingu.youran.logic.litho.ResistType;
import moe.qingu.youran.logic.pcb.BoardType;
import moe.qingu.youran.logic.pcb.PcbNumbers;
import moe.qingu.youran.logic.pcb.PcbStage;
import moe.qingu.youran.logic.reaction.FluidItemReaction;
import moe.qingu.youran.logic.reaction.ItemIngredient;
import moe.qingu.youran.registry.ModFluids;
import moe.qingu.youran.registry.ModItems;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.crafting.IRecipe;
import net.minecraft.util.ResourceLocation;
import net.minecraftforge.fluids.FluidRegistry;
import net.minecraftforge.fluids.FluidStack;
import net.minecraftforge.fml.common.registry.ForgeRegistries;
import net.minecraftforge.oredict.ShapedOreRecipe;
import net.minecraftforge.registries.IForgeRegistry;

import java.util.ArrayList;
import java.util.List;

/**
 * 主板线的配方（服主 11-16，光刻.md“主板”）：
 * <pre>
 * 乙醇 + IC2 空气 → 乙醛（炼油厂、冶炼炉）；乙醛 + 氧气 → 乙酸；甲酚 + 乙醛 → 酚醛树脂
 * 纸 + 125 mB 酚醛树脂 → 酚醛基板（IC2 装罐机、沉浸工程灌装机）
 * 铜板（上）+ 酚醛基板（下）→ 空白主板（工作台；或 IC2 固体装罐机把铜板压在基板上）
 * 空白主板 + 10 mB 光刻胶 → 涂胶主板（装罐机、灌装机；负胶正胶都行，变质胶也照涂，见 PcbStage）
 * 涂胶主板 + 线路底片 → 曝光后的主板（紫外擦除器）
 * 曝光后的主板 + 100 mB 三氯化铁水溶液 → 光板（装罐机、灌装机；显影并进蚀刻）
 * 光板 + 螺丝刀 → 打好孔的光板（工作台，25% 打坏成报废主板）；光板 → 打好孔的光板（金属冲压机 + 冲孔模具，不会坏）
 * 报废主板 → 108 mB 熔融铜（匠魂冶炼炉）
 * 1 铁粉 + 3000 mB 氯气 → 1 三氯化铁粉（装罐机；沉浸工程那边是灌装机，混合器出不了固体）
 * 1 三氯化铁粉 + 1000 mB 水（或蒸馏水、矿泉水、纯净水）→ 1000 mB 三氯化铁水溶液（混合器；装罐机也行）
 * 1 三氯化铁粉 ⇄ 16 个炼金化学三氯化铁化合物（溶解机 / 化合机）
 * 冲孔模具：工程师装配台“金属冲压模具”蓝图，5 钢板 + 1 工程师锤（或剪线钳，原版模具用的是剪线钳）
 * </pre>
 * 别的模组都是软依赖。数量见 {@link PcbNumbers}。
 */
public final class PcbRecipes {
    /** 能配三氯化铁水溶液的水：原版水、IC2 蒸馏水、本模组矿泉水、意志坚定的纯净水（有哪个算哪个） */
    static final String[] WATERS = {"water", "ic2distilled_water", "fuild_mineral_water", "purified_water"};
    static final String ETHANOL = "ethanol";
    static final String AIR = "ic2air";
    static final String OXYGEN = "ic2oxygen";
    static final String SCREWDRIVER = "scp:screwdriver";

    private PcbRecipes() {
    }

    static String id(Item item) {
        return item.getRegistryName().toString();
    }

    // ================= 装罐机反应（两端，配方注册事件里由 CannerReactions 调）=================

    static List<FluidItemReaction<ItemStack, ReactionItem>> cannerReactions() {
        List<FluidItemReaction<ItemStack, ReactionItem>> l = new ArrayList<>();
        String pcb = YouranMod.MODID + ":pcb";
        l.add(new FluidItemReaction<>("phenolic_resin", PcbNumbers.SUBSTRATE_RESIN, ItemIngredient.item("minecraft:paper", 0, 1),
                null, 0, new ReactionItem(YouranMod.MODID + ":phenolic_substrate", 0, 1, null)));
        for (ResistType r : ResistType.values()) {
            l.add(new FluidItemReaction<>(FluidVariants.photoresistKey(r, false), PcbNumbers.COAT_RESIST, ItemIngredient.item(pcb, PcbStage.BLANK, 1),
                    null, 0, new ReactionItem(pcb, PcbStage.COATED, 1, null)));
            l.add(new FluidItemReaction<>(FluidVariants.photoresistKey(r, true), PcbNumbers.COAT_RESIST, ItemIngredient.item(pcb, PcbStage.BLANK, 1),
                    null, 0, new ReactionItem(pcb, PcbStage.COATED_SPOILED, 1, null)));
        }
        for (BoardType t : BoardType.values()) {
            for (boolean spoiled : new boolean[]{false, true}) {
                int in = PcbStage.exposed(t, spoiled);
                l.add(new FluidItemReaction<>("ferric_chloride", PcbNumbers.ETCH_FECL3, ItemIngredient.item(pcb, in, 1),
                        null, 0, new ReactionItem(pcb, PcbStage.afterEtching(in), 1, null)));
            }
        }
        l.add(new FluidItemReaction<>(CannerReactions.CHLORINE, PcbNumbers.FECL3_CHLORINE, ItemIngredient.ore("dustIron", 1),
                null, 0, new ReactionItem(YouranMod.MODID + ":ferric_chloride_dust", 0, 1, null)));
        for (String water : WATERS) {
            l.add(new FluidItemReaction<>(water, PcbNumbers.FECL3_SOLUTION, ItemIngredient.ore("dustFerricChloride", 1),
                    "ferric_chloride", PcbNumbers.FECL3_SOLUTION, null));
        }
        return l;
    }

    // ================= 工作台（配方注册事件）=================

    public static void registerCrafting(IForgeRegistry<IRecipe> registry) {
        // 空白主板：上面铜板、下面酚醛基板
        ResourceLocation blank = new ResourceLocation(YouranMod.MODID, "pcb_blank");
        registry.register(new ShapedOreRecipe(blank, new ItemStack(ModItems.pcb, 1, PcbStage.BLANK), "C", "S",
                'C', "plateCopper", 'S', new ItemStack(ModItems.phenolicSubstrate)).setRegistryName(blank));
        // 手工打孔：光板 + 螺丝刀 → 打好孔的光板（螺丝刀还回，25% 打坏）
        Item screwdriver = ForgeRegistries.ITEMS.getValue(new ResourceLocation(SCREWDRIVER));
        if (screwdriver != null) {
            for (BoardType t : BoardType.values()) {
                ResourceLocation id = new ResourceLocation(YouranMod.MODID, "pcb_hand_punch_" + t.name);
                registry.register(new ScrewdriverPunch(id, new ItemStack(ModItems.pcb, 1, PcbStage.etched(t)), new ItemStack(screwdriver),
                        new ItemStack(ModItems.pcb, 1, PcbStage.punched(t)), PcbStage.SCRAP, PcbNumbers.HAND_PUNCH_BREAK));
            }
        } else {
            YouranMod.LOG.info("没有 SCP 模组的螺丝刀，不加手工打孔的配方");
        }
        // EPROM：2764 芯片本体 + 玻璃管（石英小窗）
        ResourceLocation eprom = new ResourceLocation(YouranMod.MODID, "eprom");
        registry.register(new EpromRecipe(eprom));
        // 紫外擦除器：钢板 ×3 / 铝板 铁质机械零件 铝板 / 钢板 电路板 钢板（服主 11-16）
        Item ieMaterial = ForgeRegistries.ITEMS.getValue(new ResourceLocation("immersiveengineering", "material"));
        if (ieMaterial != null) {
            ResourceLocation uv = new ResourceLocation(YouranMod.MODID, "uv_eraser");
            registry.register(new ShapedOreRecipe(uv, new ItemStack(ModItems.uvEraser), "SSS", "AMA", "SCS",
                    'S', "plateSteel", 'A', "plateAluminum", 'M', new ItemStack(ieMaterial, 1, 8) /* 铁质机械零件：沉浸工程没登记矿物词典 */,
                    'C', circuitBoards()).setRegistryName(uv));
        }
    }

    /** 电路板两种都认：IC2 电路板（矿物词典 circuitBasic）或沉浸工程电路板（material:27，没登记矿物词典） */
    public static net.minecraft.item.crafting.Ingredient circuitBoards() {
        List<net.minecraft.item.crafting.Ingredient> parts = new ArrayList<>();
        parts.add(new net.minecraftforge.oredict.OreIngredient("circuitBasic"));
        Item ieMaterial = ForgeRegistries.ITEMS.getValue(new ResourceLocation("immersiveengineering", "material"));
        if (ieMaterial != null) {
            parts.add(net.minecraft.item.crafting.Ingredient.fromStacks(new ItemStack(ieMaterial, 1, 27)));
        }
        return new net.minecraftforge.common.crafting.CompoundIngredient(parts) {
        };
    }

    // ================= 后初始化 =================

    public static void postInit(boolean ic2, boolean ie, boolean tic, boolean alchemistry) {
        if (ie) {
            Ie.register();
        }
        if (tic) {
            Tic.register();
            // 晾干架晒掉 EPROM 的内容（几个游戏日）
            moe.qingu.youran.compat.tconstruct.EpromDrying.register();
        }
        if (ic2) {
            Ic2.register();
        }
        if (alchemistry) {
            ItemStack dust = new ItemStack(ModItems.ferricChlorideDust);
            AlchemistryCompat.registerCombinerRecipe(dust, AlchemistryCompat.FERRIC_CHLORIDE, PcbNumbers.FECL3_COMPOUNDS);
            ItemStack compounds = AlchemistryCompat.stack(AlchemistryCompat.FERRIC_CHLORIDE, PcbNumbers.FECL3_COMPOUNDS);
            if (!compounds.isEmpty() && AlchemistryCompat.addDissolverRecipe(dust, java.util.Collections.singletonList(java.util.Collections.singletonList(
                    new moe.qingu.youran.logic.boron.Tourmaline.Portion(AlchemistryCompat.FERRIC_CHLORIDE, PcbNumbers.FECL3_COMPOUNDS, 100))))) {
                YouranMod.LOG.info("已加炼金化学溶解机配方：三氯化铁粉 → {} 三氯化铁", PcbNumbers.FECL3_COMPOUNDS);
            }
        }
    }

    /** 沉浸工程：炼油厂、灌装机、混合器、金属冲压机、冲孔模具蓝图 */
    static final class Ie {
        static void register() {
            FluidStack ethanol = FluidRegistry.getFluidStack(ETHANOL, PcbNumbers.ACETALDEHYDE_ETHANOL);
            FluidStack air = FluidRegistry.getFluidStack(AIR, PcbNumbers.ACETALDEHYDE_AIR);
            FluidStack oxygen = FluidRegistry.getFluidStack(OXYGEN, PcbNumbers.ACETIC_OXYGEN);
            int energy = PcbNumbers.REFINERY_ENERGY;
            if (ethanol != null && air != null) {
                blusunrize.immersiveengineering.api.crafting.RefineryRecipe.addRecipe(
                        new FluidStack(ModFluids.acetaldehyde, PcbNumbers.ACETALDEHYDE_OUT), ethanol, air, energy);
            }
            if (oxygen != null) {
                blusunrize.immersiveengineering.api.crafting.RefineryRecipe.addRecipe(new FluidStack(ModFluids.aceticAcid, PcbNumbers.ACETIC_OUT),
                        new FluidStack(ModFluids.acetaldehyde, PcbNumbers.ACETIC_ACETALDEHYDE), oxygen, energy);
            }
            blusunrize.immersiveengineering.api.crafting.RefineryRecipe.addRecipe(new FluidStack(ModFluids.phenolicResin, PcbNumbers.RESIN_OUT),
                    new FluidStack(ModFluids.cresol, PcbNumbers.RESIN_CRESOL), new FluidStack(ModFluids.acetaldehyde, PcbNumbers.RESIN_ACETALDEHYDE), energy);
            // 灌装机
            blusunrize.immersiveengineering.api.crafting.BottlingMachineRecipe.addRecipe(new ItemStack(ModItems.phenolicSubstrate),
                    new ItemStack(net.minecraft.init.Items.PAPER), new FluidStack(ModFluids.phenolicResin, PcbNumbers.SUBSTRATE_RESIN));
            for (ResistType r : ResistType.values()) {
                blusunrize.immersiveengineering.api.crafting.BottlingMachineRecipe.addRecipe(new ItemStack(ModItems.pcb, 1, PcbStage.COATED),
                        new ItemStack(ModItems.pcb, 1, PcbStage.BLANK), FluidVariants.photoresist(r, PcbNumbers.COAT_RESIST, false));
                blusunrize.immersiveengineering.api.crafting.BottlingMachineRecipe.addRecipe(new ItemStack(ModItems.pcb, 1, PcbStage.COATED_SPOILED),
                        new ItemStack(ModItems.pcb, 1, PcbStage.BLANK), FluidVariants.photoresist(r, PcbNumbers.COAT_RESIST, true));
            }
            for (BoardType t : BoardType.values()) {
                for (boolean spoiled : new boolean[]{false, true}) {
                    int in = PcbStage.exposed(t, spoiled);
                    blusunrize.immersiveengineering.api.crafting.BottlingMachineRecipe.addRecipe(new ItemStack(ModItems.pcb, 1, PcbStage.afterEtching(in)),
                            new ItemStack(ModItems.pcb, 1, in), new FluidStack(ModFluids.ferricChloride, PcbNumbers.ETCH_FECL3));
                }
            }
            blusunrize.immersiveengineering.api.crafting.BottlingMachineRecipe.addRecipe(new ItemStack(ModItems.ferricChlorideDust), "dustIron",
                    new FluidStack(ModFluids.chlorine, PcbNumbers.FECL3_CHLORINE));
            // 混合器：三氯化铁粉 + 水 → 三氯化铁水溶液（1:1 体积，原版混合器就行）
            int waters = 0;
            for (String w : WATERS) {
                FluidStack water = FluidRegistry.getFluidStack(w, PcbNumbers.FECL3_SOLUTION);
                if (water != null) {
                    blusunrize.immersiveengineering.api.crafting.MixerRecipe.addRecipe(new FluidStack(ModFluids.ferricChloride, PcbNumbers.FECL3_SOLUTION),
                            water, new Object[]{"dustFerricChloride"}, PcbNumbers.MIXER_ENERGY);
                    waters++;
                }
            }
            // 冲孔模具（服主 11-16：金属冲压模具蓝图的第 9 种，材料同原版）
            Item tool = ForgeRegistries.ITEMS.getValue(new ResourceLocation("immersiveengineering", "tool"));
            if (tool != null) {
                java.util.List<ItemStack> tools = java.util.Arrays.asList(new ItemStack(tool, 1, 0), new ItemStack(tool, 1, 1));
                blusunrize.immersiveengineering.api.crafting.BlueprintCraftingRecipe.addRecipe("molds", new ItemStack(ModItems.punchMold),
                        "plateSteel", "plateSteel", "plateSteel", "plateSteel", "plateSteel",
                        new blusunrize.immersiveengineering.api.crafting.IngredientStack(tools, 1));
            }
            ItemStack mold = new ItemStack(ModItems.punchMold);
            for (BoardType t : BoardType.values()) {
                blusunrize.immersiveengineering.api.crafting.MetalPressRecipe.addRecipe(new ItemStack(ModItems.pcb, 1, PcbStage.punched(t)),
                        new ItemStack(ModItems.pcb, 1, PcbStage.etched(t)), mold, PcbNumbers.PRESS_ENERGY);
            }
            YouranMod.LOG.info("已加沉浸工程的主板线配方（炼油厂、灌装机、混合器 {} 种水、冲孔模具、金属冲压机）", waters);
        }
    }

    /** 匠魂：冶炼炉合金（乙醛、乙酸、酚醛树脂）、报废主板熔成铜 */
    static final class Tic {
        static void register() {
            FluidStack ethanol = FluidRegistry.getFluidStack(ETHANOL, PcbNumbers.ACETALDEHYDE_ALLOY_ETHANOL);
            FluidStack air = FluidRegistry.getFluidStack(AIR, PcbNumbers.ACETALDEHYDE_ALLOY_AIR);
            FluidStack oxygen = FluidRegistry.getFluidStack(OXYGEN, PcbNumbers.ACETIC_ALLOY_OXYGEN);
            if (ethanol != null && air != null) {
                slimeknights.tconstruct.library.TinkerRegistry.registerAlloy(new FluidStack(ModFluids.acetaldehyde, PcbNumbers.ACETALDEHYDE_ALLOY_OUT), ethanol, air);
            }
            if (oxygen != null) {
                slimeknights.tconstruct.library.TinkerRegistry.registerAlloy(new FluidStack(ModFluids.aceticAcid, PcbNumbers.ACETIC_ALLOY_OUT),
                        new FluidStack(ModFluids.acetaldehyde, PcbNumbers.ACETIC_ALLOY_ACETALDEHYDE), oxygen);
            }
            slimeknights.tconstruct.library.TinkerRegistry.registerAlloy(new FluidStack(ModFluids.phenolicResin, PcbNumbers.RESIN_ALLOY_OUT),
                    new FluidStack(ModFluids.cresol, PcbNumbers.RESIN_ALLOY_CRESOL), new FluidStack(ModFluids.acetaldehyde, PcbNumbers.RESIN_ALLOY_ACETALDEHYDE));
            net.minecraftforge.fluids.Fluid copper = FluidRegistry.getFluid("copper");
            if (copper != null) {
                slimeknights.tconstruct.library.TinkerRegistry.registerMelting(new ItemStack(ModItems.pcb, 1, PcbStage.SCRAP), copper, PcbNumbers.SCRAP_BOARD_COPPER);
            }
            YouranMod.LOG.info("已加匠魂的主板线配方（冶炼炉合金、报废主板熔成铜）");
        }
    }

    /** IC2：固体装罐机把铜板压在酚醛基板上 → 空白主板 */
    static final class Ic2 {
        static void register() {
            ic2.api.recipe.Recipes.cannerBottle.addRecipe(ic2.api.recipe.Recipes.inputFactory.forStack(new ItemStack(ModItems.phenolicSubstrate)),
                    ic2.api.recipe.Recipes.inputFactory.forOreDict("plateCopper"), new ItemStack(ModItems.pcb, 1, PcbStage.BLANK), false);
            YouranMod.LOG.info("已加 IC2 固体装罐机配方：酚醛基板 + 铜板 → 空白主板");
        }
    }
}
