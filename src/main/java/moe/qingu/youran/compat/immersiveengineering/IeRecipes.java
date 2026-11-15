package moe.qingu.youran.compat.immersiveengineering;

import blusunrize.immersiveengineering.api.crafting.ArcFurnaceRecipe;
import blusunrize.immersiveengineering.api.crafting.BottlingMachineRecipe;
import blusunrize.immersiveengineering.api.crafting.CrusherRecipe;
import blusunrize.immersiveengineering.api.crafting.IngredientStack;
import blusunrize.immersiveengineering.api.crafting.MetalPressRecipe;
import blusunrize.immersiveengineering.api.crafting.MixerRecipe;
import blusunrize.immersiveengineering.api.crafting.RefineryRecipe;
import moe.qingu.youran.fluid.FluidPurity;
import moe.qingu.youran.logic.purity.Purity;
import moe.qingu.youran.logic.purity.WaferMold;
import moe.qingu.youran.logic.purity.WaferRules;
import moe.qingu.youran.registry.ModFluids;
import moe.qingu.youran.YouranMod;
import moe.qingu.youran.compat.alchemistry.AlchemistryCompat;
import moe.qingu.youran.registry.ModItems;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.util.ResourceLocation;
import net.minecraftforge.fluids.FluidRegistry;
import net.minecraftforge.fluids.FluidStack;
import net.minecraftforge.fml.common.registry.ForgeRegistries;
import net.minecraft.util.NonNullList;
import net.minecraftforge.oredict.OreDictionary;

/** 加进沉浸工程机器的配方。只有装了沉浸工程时才会被加载（{@code recipe.CompatRecipes} 先查） */
public final class IeRecipes {
    /** 匠魂的熔融钴 */
    static final String MOLTEN_COBALT = "cobalt";

    private IeRecipes() {
    }

    /** 乙醇（沉浸工程）、氧气（IC2） */
    static final String ETHANOL = "ethanol";
    static final String OXYGEN = "ic2oxygen";

    /**
     * 炼油厂：500 mB 乙醇 + 325 mB 氧气 → 500 mB 乙酸（服主 11-14 定的数）。炼油厂每 tick 做一次配方，这个量就是每 tick 的量；
     * 每次的电耗 80 同沉浸工程自己的生物柴油配方。要装了 IC2（氧气）。
     */
    public static void registerRefinery() {
        FluidStack ethanol = FluidRegistry.getFluidStack(ETHANOL, 500);
        FluidStack oxygen = FluidRegistry.getFluidStack(OXYGEN, 325);
        if (ethanol == null || oxygen == null) {
            YouranMod.LOG.info("没有流体 {} 或 {}，不加沉浸工程炼油厂的乙酸配方", ETHANOL, OXYGEN);
            return;
        }
        RefineryRecipe.addRecipe(new FluidStack(ModFluids.aceticAcid, 500), ethanol, oxygen, 80);
        RefineryRecipe found = RefineryRecipe.findRecipe(ethanol, oxygen);
        if (found == null || found.output.getFluid() != ModFluids.aceticAcid) {
            YouranMod.LOG.error("沉浸工程炼油厂的乙酸配方没生效：同样的输入找到的是 {}", found == null ? null : found.output);
        } else {
            YouranMod.LOG.info("已加沉浸工程炼油厂配方：500 mB {} + 325 mB {} → 500 mB 乙酸", ETHANOL, OXYGEN);
        }
    }

    /** IC2 电解出来的氢气 */
    static final String HYDROGEN = "ic2hydrogen";

    /**
     * 炼油厂：每 tick 10 mB 三氯氢硅 + 40 mB 氢气 → 50 mB 三氯氢硅氢气混合气（芯片线第 6 步，比例 Claude 定，见 logic.purity.WaferRules）。
     * 只有到了 9 个 9 的三氯氢硅能做；炼油厂按 NBT 比配方，所以 9–32 个 9 每种各一条，混合气的纯度同输入的三氯氢硅。要装了 IC2（氢气）。
     */
    public static void registerMixtureRefinery() {
        FluidStack hydrogen = FluidRegistry.getFluidStack(HYDROGEN, WaferRules.REFINERY_HYDROGEN);
        if (hydrogen == null) {
            YouranMod.LOG.info("没有流体 {}（IC2 的氢气），不加沉浸工程炼油厂的混合气配方", HYDROGEN);
            return;
        }
        int added = 0;
        for (int nines : WaferRules.mixtureGrades()) {
            FluidStack tcs = FluidPurity.trichlorosilane(WaferRules.REFINERY_TRICHLOROSILANE, nines);
            FluidStack mixture = FluidPurity.mixture(WaferRules.REFINERY_MIXTURE, WaferRules.mixtureNines(nines));
            RefineryRecipe.recipeList.add(new PurityRefineryRecipe(mixture, tcs, hydrogen.copy(), WaferRules.REFINERY_ENERGY,
                    nines == Purity.WAFER_GRADE_NINES));
            RefineryRecipe found = RefineryRecipe.findRecipe(hydrogen, tcs);
            if (found == null || !mixture.isFluidStackIdentical(found.output)) {
                YouranMod.LOG.error("沉浸工程炼油厂的混合气配方（{} 个 9）没生效：同样的输入找到的是 {}", nines, found == null ? null : found.output);
            } else {
                added++;
            }
        }
        RefineryRecipe below = RefineryRecipe.findRecipe(FluidPurity.trichlorosilane(WaferRules.REFINERY_TRICHLOROSILANE, Purity.WAFER_GRADE_NINES - 1), hydrogen);
        if (below != null) {
            YouranMod.LOG.error("不到 9 个 9 的三氯氢硅在炼油厂里也找到了配方：{}", below.output);
        }
        YouranMod.LOG.info("已加沉浸工程炼油厂配方：{} mB 三氯氢硅（{}–{} 个 9，{} 条）+ {} mB 氢气 → {} mB 混合气", WaferRules.REFINERY_TRICHLOROSILANE,
                Purity.WAFER_GRADE_NINES, Purity.MAX_NINES, added, WaferRules.REFINERY_HYDROGEN, WaferRules.REFINERY_MIXTURE);
    }

    /** 灌装机：1 磁粉 + 10 mB 熔融钴 → 1 钴改性磁粉（同装罐机富集模式的那条） */
    public static void registerCobaltMagnetic() {
        FluidStack cobalt = FluidRegistry.getFluidStack(MOLTEN_COBALT, 10);
        if (cobalt == null) {
            YouranMod.LOG.error("没有流体 {}，不加沉浸工程灌装机的钴改性磁粉配方", MOLTEN_COBALT);
            return;
        }
        ItemStack input = new ItemStack(ModItems.magnetiteDust);
        BottlingMachineRecipe.addRecipe(new ItemStack(ModItems.cobaltMagneticDust), input, cobalt);
        // 用灌装机自己找配方的办法核对一遍（同一种输入可能已有别的配方排在前面）
        BottlingMachineRecipe found = BottlingMachineRecipe.findRecipe(input, cobalt);
        if (found == null || found.output.getItem() != ModItems.cobaltMagneticDust) {
            YouranMod.LOG.error("沉浸工程灌装机的钴改性磁粉配方没生效：同样的输入找到的是 {}", found == null ? null : found.output);
        } else {
            YouranMod.LOG.info("已加沉浸工程灌装机配方：磁粉 + 10 mB {} → 钴改性磁粉", MOLTEN_COBALT);
        }
    }

    /**
     * 灌装机：1 纤维素 + 188 mB 乙酸 → 1 醋酸纤维素（装了炼金化学时）。装罐机里是 375 mB + 2 → 2，
     * 但灌装机一次只放一个物品进去，按一个算是 187.5 mB，取整为 188（不比装罐机便宜）。
     */
    public static void registerCelluloseAcetate() {
        ItemStack cellulose = AlchemistryCompat.stack(AlchemistryCompat.CELLULOSE, 1);
        ItemStack acetate = AlchemistryCompat.stack(AlchemistryCompat.CELLULOSE_ACETATE, 1);
        if (cellulose.isEmpty() || acetate.isEmpty()) {
            YouranMod.LOG.info("炼金化学里没有纤维素或醋酸纤维素，不加沉浸工程灌装机的醋酸纤维素配方");
            return;
        }
        FluidStack acid = new FluidStack(ModFluids.aceticAcid, 188);
        BottlingMachineRecipe.addRecipe(acetate, cellulose, acid);
        BottlingMachineRecipe found = BottlingMachineRecipe.findRecipe(cellulose, acid);
        if (found == null || !ItemStack.areItemsEqual(found.output, acetate)) {
            YouranMod.LOG.error("沉浸工程灌装机的醋酸纤维素配方没生效：同样的输入找到的是 {}", found == null ? null : found.output);
        } else {
            YouranMod.LOG.info("已加沉浸工程灌装机配方：纤维素 + 188 mB 乙酸 → 醋酸纤维素");
        }
    }

    /** 金属冲压机，板模具（同锭压成板，电耗 2400 同沉浸工程的板）：精制盘片 → 抛光盘片，塑料团 → 塑料片 */
    public static void registerMetalPress() {
        Item moldItem = ForgeRegistries.ITEMS.getValue(new ResourceLocation("immersiveengineering", "mold"));
        if (moldItem == null) {
            YouranMod.LOG.error("找不到沉浸工程的模具，不加金属冲压机配方");
            return;
        }
        ItemStack plateMold = new ItemStack(moldItem, 1, 0);
        press(new ItemStack(ModItems.refinedPlatter), new ItemStack(ModItems.polishedPlatter), plateMold);
        press(new ItemStack(ModItems.pvcLump), new ItemStack(ModItems.pvcSheet), plateMold);
    }

    private static void press(ItemStack input, ItemStack output, ItemStack mold) {
        MetalPressRecipe.addRecipe(output, input, mold, 2400);
        MetalPressRecipe found = MetalPressRecipe.findRecipe(mold, input);
        if (found == null || !ItemStack.areItemsEqual(found.output, output)) {
            YouranMod.LOG.error("沉浸工程金属冲压机配方（{} → {}）没生效", input.getItem().getRegistryName(), output.getItem().getRegistryName());
        }
    }

    /** 灌装机：1 聚乙烯团 + 125 mB 氯气 → 1 聚氯乙烯团（同装罐机富集模式的那条） */
    public static void registerPvc() {
        ItemStack input = new ItemStack(ModItems.polyethyleneLump);
        FluidStack chlorine = new FluidStack(ModFluids.chlorine, 125);
        BottlingMachineRecipe.addRecipe(new ItemStack(ModItems.pvcLump), input, chlorine);
        BottlingMachineRecipe found = BottlingMachineRecipe.findRecipe(input, chlorine);
        if (found == null || found.output.getItem() != ModItems.pvcLump) {
            YouranMod.LOG.error("沉浸工程灌装机的聚氯乙烯配方没生效：同样的输入找到的是 {}", found == null ? null : found.output);
        }
    }

    /** IC2 的二氧化硅粉（ic2:dust:13）；沉浸工程的焦炭（immersiveengineering:material:6）与焦炭粉（material:17） */
    static final String SILICON_DIOXIDE_DUST = "dustSiliconDioxide";
    static final String COKE = "fuelCoke";
    static final String COKE_DUST = "dustCoke";
    /** 电弧炉：时间、每 tick 电耗同沉浸工程自己的钢（铁锭 + 焦炭粉 → 钢锭：400 tick、512 Flux/tick） */
    static final int ARC_TIME = 400;
    static final int ARC_ENERGY_PER_TICK = 512;

    /**
     * 电弧炉：1 二氧化硅粉（IC2）+ 辅料 1 焦炭或焦炭粉 → 1 炼金化学硅锭（服主 11-15，芯片线“硅”第 1 步，粗硅）。不出炉渣。
     * 要装了炼金化学；二氧化硅粉按矿物词典 dustSiliconDioxide（IC2 注册），辅料认 fuelCoke 与 dustCoke（沉浸工程注册；
     * 沉浸工程按矿物词典名的列表合成一种辅料，两种都能放进辅料槽）。
     */
    public static void registerSiliconArcFurnace() {
        ItemStack silicon = AlchemistryCompat.ingot(AlchemistryCompat.SILICON, 1);
        if (silicon.isEmpty()) {
            YouranMod.LOG.info("没有炼金化学的硅锭，不加沉浸工程电弧炉的粗硅配方");
            return;
        }
        if (OreDictionary.getOres(SILICON_DIOXIDE_DUST, false).isEmpty()) {
            YouranMod.LOG.info("矿物词典里没有 {}（没装 IC2？），不加沉浸工程电弧炉的粗硅配方", SILICON_DIOXIDE_DUST);
            return;
        }
        ArcFurnaceRecipe.addRecipe(silicon, SILICON_DIOXIDE_DUST, ItemStack.EMPTY, ARC_TIME, ARC_ENERGY_PER_TICK,
                java.util.Arrays.asList(COKE, COKE_DUST));
        ItemStack dust = OreDictionary.getOres(SILICON_DIOXIDE_DUST, false).get(0);
        for (String coke : new String[]{COKE, COKE_DUST}) {
            if (OreDictionary.getOres(coke, false).isEmpty()) {
                continue;
            }
            NonNullList<ItemStack> additives = NonNullList.withSize(4, ItemStack.EMPTY);
            additives.set(0, OreDictionary.getOres(coke, false).get(0).copy());
            ArcFurnaceRecipe found = ArcFurnaceRecipe.findRecipe(dust, additives);
            if (found == null || !ItemStack.areItemStacksEqual(found.output, silicon)) {
                YouranMod.LOG.error("沉浸工程电弧炉的粗硅配方（辅料 {}）没生效：同样的输入找到的是 {}", coke, found == null ? null : found.output);
            } else {
                YouranMod.LOG.info("已加沉浸工程电弧炉配方：二氧化硅粉 + {} → 硅锭", coke);
            }
        }
    }

    /** 电弧炉：5 硼硅酸盐渣 → 1 炼金化学硼锭 + 3 二氧化硅粉（二氧化硅粉放在炉渣格里出） */
    static final int SLAG_PER_BORON = 5;
    static final int SLAG_SILICA = 3;

    /**
     * 电弧炉：5 硼硅酸盐渣 → 1 硼锭 + 3 IC2 二氧化硅粉（服主 11-15）。电弧炉只有一个产物格，二氧化硅粉放在炉渣格（沉浸工程的炉渣格能出任意物品），
     * 时间、电耗同粗硅。要装了炼金化学（硼锭）；二氧化硅粉按矿物词典 dustSiliconDioxide（IC2），没有时只出硼锭。
     */
    public static void registerBoronArcFurnace() {
        ItemStack boron = AlchemistryCompat.ingot(AlchemistryCompat.BORON, 1);
        if (boron.isEmpty()) {
            YouranMod.LOG.info("没有炼金化学的硼锭，不加沉浸工程电弧炉的硼硅酸盐渣配方");
            return;
        }
        ItemStack silica = ItemStack.EMPTY;
        if (!OreDictionary.getOres(SILICON_DIOXIDE_DUST, false).isEmpty()) {
            silica = OreDictionary.getOres(SILICON_DIOXIDE_DUST, false).get(0).copy();
            silica.setCount(SLAG_SILICA);
        }
        ItemStack slag = new ItemStack(ModItems.borosilicateSlag, SLAG_PER_BORON);
        ArcFurnaceRecipe.addRecipe(boron, slag, silica, ARC_TIME, ARC_ENERGY_PER_TICK);
        ArcFurnaceRecipe found = ArcFurnaceRecipe.findRecipe(slag, NonNullList.withSize(4, ItemStack.EMPTY));
        if (found == null || !ItemStack.areItemStacksEqual(found.output, boron)) {
            YouranMod.LOG.error("沉浸工程电弧炉的硼硅酸盐渣配方没生效：同样的输入找到的是 {}", found == null ? null : found.output);
        } else {
            YouranMod.LOG.info("已加沉浸工程电弧炉配方：5 硼硅酸盐渣 → 硼锭 + {} 二氧化硅粉", silica.getCount());
        }
    }

    /** 匠魂的“盘”（平底锅锅头）部件，铸模的 NBT 里记它：{PartType:"tconstruct:pan_head"} */
    static final String PAN_HEAD = "tconstruct:pan_head";
    static final String TIC_PART_TYPE = "PartType";
    /** 掺杂辅料：1 硼锭（矿物词典 ingotBoron，炼金化学注册）+ 4 萤石粉（dustGlowstone，原版） */
    static final String BORON_INGOT = "ingotBoron";
    static final String GLOWSTONE_DUST = "dustGlowstone";
    static final int GLOWSTONE_PER_MOLD = 4;

    /** 匠魂“盘”铸模：金铸模 {@code tconstruct:cast}、粘土铸模 {@code tconstruct:clay_cast}，NBT 记部件；没装匠魂时为空物品 */
    static ItemStack panCast(WaferMold mold) {
        Item cast = ForgeRegistries.ITEMS.getValue(new ResourceLocation("tconstruct", mold == WaferMold.GOLD ? "cast" : "clay_cast"));
        if (cast == null || !ForgeRegistries.ITEMS.containsKey(new ResourceLocation(PAN_HEAD))) {
            return ItemStack.EMPTY;
        }
        ItemStack stack = new ItemStack(cast);
        net.minecraft.nbt.NBTTagCompound tag = new net.minecraft.nbt.NBTTagCompound();
        tag.setString(TIC_PART_TYPE, PAN_HEAD);
        stack.setTagCompound(tag);
        return stack;
    }

    /**
     * 电弧炉：匠魂“盘”铸模（金或粘土）+ 辅料 1 硼锭 + 4 萤石粉 → 对应材质的掺杂硅晶圆铸模（服主 11-15，芯片线第 7 步）。
     * 铸模按 NBT 比（只认“盘”的铸模，别的部件的不行）；时间、电耗同粗硅（Claude 定）。要装了匠魂；硼锭没有时（没装炼金化学）不加。
     */
    public static void registerDopedMolds() {
        if (OreDictionary.getOres(BORON_INGOT, false).isEmpty()) {
            YouranMod.LOG.info("矿物词典里没有 {}（没装炼金化学？），不加沉浸工程电弧炉的掺杂硅晶圆铸模配方", BORON_INGOT);
            return;
        }
        for (WaferMold mold : WaferMold.values()) {
            ItemStack cast = panCast(mold);
            if (cast.isEmpty()) {
                YouranMod.LOG.info("没有匠魂的“盘”铸模（{}），不加沉浸工程电弧炉的掺杂硅晶圆铸模配方", mold.id);
                return;
            }
            ItemStack output = new ItemStack(ModItems.dopedWaferMold(mold));
            ArcFurnaceRecipe.addRecipe(output, new IngredientStack(cast).setUseNBT(true), ItemStack.EMPTY, ARC_TIME, ARC_ENERGY_PER_TICK,
                    BORON_INGOT, new IngredientStack(GLOWSTONE_DUST, GLOWSTONE_PER_MOLD));
            NonNullList<ItemStack> additives = NonNullList.withSize(4, ItemStack.EMPTY);
            additives.set(0, OreDictionary.getOres(BORON_INGOT, false).get(0).copy());
            additives.set(1, new ItemStack(net.minecraft.init.Items.GLOWSTONE_DUST, GLOWSTONE_PER_MOLD));
            ArcFurnaceRecipe found = ArcFurnaceRecipe.findRecipe(cast, additives);
            ItemStack otherPart = cast.copy();
            otherPart.getTagCompound().setString(TIC_PART_TYPE, "tconstruct:sword_blade");
            ArcFurnaceRecipe wrong = ArcFurnaceRecipe.findRecipe(otherPart, additives);
            if (found == null || found.output.getItem() != output.getItem() || (wrong != null && wrong.output.getItem() == output.getItem())) {
                YouranMod.LOG.error("沉浸工程电弧炉的掺杂硅晶圆铸模配方（{}）没生效：找到的是 {}，别的部件的铸模找到的是 {}", mold.id,
                        found == null ? null : found.output, wrong == null ? null : wrong.output);
            } else {
                YouranMod.LOG.info("已加沉浸工程电弧炉配方：“盘”{}铸模 + 硼锭 + {} 萤石粉 → 掺杂硅晶圆铸模", mold.id, GLOWSTONE_PER_MOLD);
            }
        }
    }

    /**
     * 灌装机：1000 mB 混合气 + 1 掺杂硅晶圆铸模（消耗）→ 1 硅晶圆（服主 11-15，芯片线第 8 步），晶圆带混合气的纯度与铸模材质。
     * 灌装机按 NBT 比配方，所以每种纯度（没有标记的与 9–32 个 9）、每种铸模各一条，共 50 条；JEI 里只列 9 个 9 的两条。
     */
    public static void registerWaferBottling() {
        int added = 0;
        for (WaferMold mold : WaferMold.values()) {
            ItemStack input = new ItemStack(ModItems.dopedWaferMold(mold));
            for (int nines : WaferRules.castingGrades()) {
                FluidStack fluid = FluidPurity.mixture(WaferRules.MIXTURE_PER_WAFER, nines);
                ItemStack wafer = moe.qingu.youran.item.ItemSiliconWafer.make(ModItems.siliconWafer, WaferRules.waferNines(nines), mold);
                BottlingMachineRecipe.recipeList.add(new PurityBottlingRecipe(wafer, input, fluid, nines == Purity.WAFER_GRADE_NINES));
                BottlingMachineRecipe found = BottlingMachineRecipe.findRecipe(input, fluid);
                if (found == null || !ItemStack.areItemStackTagsEqual(found.output, wafer) || found.output.getItem() != wafer.getItem()) {
                    YouranMod.LOG.error("沉浸工程灌装机的硅晶圆配方（{}，{} 个 9）没生效：找到的是 {}", mold.id, nines, found == null ? null : found.output);
                } else {
                    added++;
                }
            }
        }
        YouranMod.LOG.info("已加沉浸工程灌装机配方：{} mB 混合气 + 掺杂硅晶圆铸模 → 硅晶圆（{} 条）", WaferRules.MIXTURE_PER_WAFER, added);
    }

    /** 电弧炉：1 报废硅晶圆 → 1 炼金化学硅锭（重新走硅线），不要辅料，时间、电耗同粗硅。要装了炼金化学 */
    public static void registerScrapWafer() {
        ItemStack silicon = AlchemistryCompat.ingot(AlchemistryCompat.SILICON, 1);
        if (silicon.isEmpty()) {
            return;
        }
        ItemStack scrap = new ItemStack(ModItems.scrapSiliconWafer);
        ArcFurnaceRecipe.addRecipe(silicon, scrap, ItemStack.EMPTY, ARC_TIME, ARC_ENERGY_PER_TICK);
        ArcFurnaceRecipe found = ArcFurnaceRecipe.findRecipe(scrap, NonNullList.withSize(4, ItemStack.EMPTY));
        if (found == null || !ItemStack.areItemStacksEqual(found.output, silicon)) {
            YouranMod.LOG.error("沉浸工程电弧炉的报废硅晶圆配方没生效：找到的是 {}", found == null ? null : found.output);
        } else {
            YouranMod.LOG.info("已加沉浸工程电弧炉配方：报废硅晶圆 → 硅锭");
        }
    }

    /** 粉碎机电耗：同沉浸工程自己把锭粉碎成粉（3000 Flux） */
    static final int CRUSHER_ENERGY = 3000;

    /** 粉碎机：1 炼金化学硅锭 → 1 硅粉（芯片线“硅”第 2 步，同 IC2 打粉机）。要装了炼金化学 */
    public static void registerSiliconCrusher() {
        ItemStack ingot = AlchemistryCompat.ingot(AlchemistryCompat.SILICON, 1);
        if (ingot.isEmpty()) {
            YouranMod.LOG.info("没有炼金化学的硅锭，不加沉浸工程粉碎机的硅粉配方");
            return;
        }
        CrusherRecipe.addRecipe(new ItemStack(ModItems.siliconDust), ingot, CRUSHER_ENERGY);
        CrusherRecipe found = CrusherRecipe.findRecipe(ingot);
        if (found == null || found.output.getItem() != ModItems.siliconDust) {
            YouranMod.LOG.error("沉浸工程粉碎机的硅粉配方没生效：同样的输入找到的是 {}", found == null ? null : found.output);
        } else {
            YouranMod.LOG.info("已加沉浸工程粉碎机配方：硅锭 → 硅粉");
        }
    }

    /** 盐酸（SCP: Lockdown 注册） */
    static final String HYDROCHLORIC_ACID = "h_cl_fluid";
    /** 搅拌机一次的电耗：照沉浸工程的混凝土（500 mB、3200 Flux），1000 mB 记 6400 Flux */
    static final int MIXER_ENERGY = 6400;

    /**
     * 搅拌机：1 硅粉 + 3000 mB 盐酸 → 1000 mB 三氯氢硅，纯度 90%（同装罐机富集模式的那条；不带纯度标记，见 fluid.FluidPurity）。
     * 沉浸工程的搅拌机本来只能 1:1 换流体，比例由 {@link RatioMixerRecipe} 与 mixin.immersiveengineering 处理；
     * 没有 Mixin 时不加这条（不然会按 1:1 出 3000 mB）。
     */
    public static void registerTrichlorosilaneMixer(boolean mixinActive) {
        FluidStack acid = FluidRegistry.getFluidStack(HYDROCHLORIC_ACID, 3000);
        if (acid == null) {
            YouranMod.LOG.info("没有流体 {}（盐酸，SCP: Lockdown 注册），不加沉浸工程搅拌机的三氯氢硅配方", HYDROCHLORIC_ACID);
            return;
        }
        if (!mixinActive) {
            YouranMod.LOG.warn("沉浸工程搅拌机的 Mixin 没有生效，不加三氯氢硅配方（搅拌机本来只能 1:1 换流体）");
            return;
        }
        ItemStack dust = new ItemStack(ModItems.siliconDust);
        RatioMixerRecipe recipe = new RatioMixerRecipe(new FluidStack(ModFluids.trichlorosilane, 1000), acid, new Object[]{dust}, MIXER_ENERGY);
        MixerRecipe.recipeList.add(recipe);
        NonNullList<ItemStack> components = NonNullList.withSize(1, dust.copy());
        MixerRecipe found = MixerRecipe.findRecipe(acid, components);
        if (found != recipe) {
            YouranMod.LOG.error("沉浸工程搅拌机的三氯氢硅配方没生效：同样的输入找到的是 {}", found == null ? null : found.fluidOutput);
        } else {
            YouranMod.LOG.info("已加沉浸工程搅拌机配方：硅粉 + 3000 mB 盐酸 → 1000 mB 三氯氢硅");
        }
    }
}
