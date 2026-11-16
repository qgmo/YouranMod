package moe.qingu.youran.recipe;

import moe.qingu.youran.YouranMod;
import moe.qingu.youran.compat.alchemistry.AlchemistryCompat;
import moe.qingu.youran.item.LampNbt;
import moe.qingu.youran.item.ItemLampPart;
import moe.qingu.youran.logic.litho.LampGlass;
import moe.qingu.youran.logic.litho.LampSpec;
import moe.qingu.youran.logic.litho.LithoNumbers;
import moe.qingu.youran.logic.purity.Purity;
import moe.qingu.youran.logic.purity.WaferMold;
import moe.qingu.youran.registry.ModBlocks;
import moe.qingu.youran.registry.ModItems;
import net.minecraft.inventory.InventoryCrafting;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.crafting.IRecipe;
import net.minecraft.util.ResourceLocation;
import net.minecraftforge.fluids.FluidRegistry;
import net.minecraftforge.fluids.FluidStack;
import net.minecraftforge.fml.common.registry.ForgeRegistries;
import net.minecraftforge.oredict.ShapedOreRecipe;
import net.minecraftforge.registries.IForgeRegistry;

/**
 * 汞灯、光源、对准台的配方，集中在这一个类里（方便改）。
 *
 * <p><b>已定</b>（服主 11-16）：
 * <ul>
 *     <li>玻璃管（低档）：匠魂熔融玻璃浇进手柄铸模（金 / 粘土都认，记进 NBT），浇铸台出一根，{@value LithoNumbers#GLASS_TUBE_CASTING} mB；</li>
 *     <li>石英管：二氧化硅粉进沉浸工程电弧炉 → 1 个 9；石英管再进一遍多一个 9；</li>
 *     <li>钨棒：钨锭进沉浸工程金属冲压机（杆模）或 IC2 金属成型机（挤压）；</li>
 *     <li>掺钍阴极棒：IC2 固体装罐机，钨棒当“容器” + 1 炼金化学钍元素；</li>
 *     <li>汞灯：沉浸工程工程师装配台，新蓝图“汞灯”：玻璃管 + 1 阴极棒 + 1 钨棒（阳极）+ 1 氩或氙（炼金化学元素）+ 2 中压接线器
 *     （或 2 根 IC2 二层绝缘金质导线）+ 1 小撮钼粉 →
 *     没有汞的汞灯；一次做一个，玻璃管的数据（种类、纯度、铸模）与气体带到灯上（Mixin，见 {@code compat.immersiveengineering.LampAssembly}）；</li>
 *     <li>补汞：IC2 装罐机、沉浸工程灌装机（按流体容器灌，{@code item.LampMercuryHandler}）或匠魂浇铸台（{@link MercuryCasting}），补到 10 mB；</li>
 *     <li>光源方块：汞灯 + 1 沉浸工程探照灯（无序）→ 光源方块，汞灯的数据全带过去；光源方块单放一格 → 汞灯 + 探照灯，数据带回来；</li>
 *     <li>对准台：设计稿 2.6 的有序配方，正中换成沉浸工程工程师装配台或 IC2 工业工作台，正下方 IC2 电路板或沉浸工程电路板（都是两种都认）。</li>
 * </ul>
 * <p>另外（服主 11-16）：沉浸原油精馏塔分离 IC2 空气出液氩（{@link Ip}）；沉浸工程挤压机挤 IC2 枯竭燃料棒出液氙
 * （{@code compat.immersiveengineering.XenonSqueezing}）；炼金化学雾化器 500 mB 液氩 / 液氙 → 8 氩 / 氙元素，液化器反过来。
 * <p>汞灯蓝图本身的合成服主没有指定，照沉浸工程自己的蓝图配方的样子定了一版（交付文档里列着）。
 */
public final class LithoRecipes {
    /** 匠魂“手柄”部件：铸模 NBT {PartType:"tconstruct:tool_rod"}（中文“手柄铸模”） */
    static final String TOOL_ROD = "tconstruct:tool_rod";
    /** 石英管电弧炉：时间、电耗（同粗硅） */
    static final int ARC_TIME = 400;
    static final int ARC_ENERGY = 512;
    /** 通用材料一律按矿物词典（服主 11-16）：钨锭 */
    static final String TUNGSTEN_INGOT = "ingotTungsten";

    static boolean hasOre(String ore) {
        return net.minecraftforge.oredict.OreDictionary.doesOreNameExist(ore) && !net.minecraftforge.oredict.OreDictionary.getOres(ore, false).isEmpty();
    }

    /** 沉浸工程蓝图的种类名：汞灯 */
    public static final String LAMP_BLUEPRINT = "mercury_lamp";

    private LithoRecipes() {
    }

    // ================= 工作台（配方注册事件）=================

    /** 工作台配方（{@link BuiltinRecipes} 在配方注册事件里调用） */
    public static void registerCrafting(IForgeRegistry<IRecipe> registry) {
        // 汞灯蓝图（Claude 定的一版）：钨棒 / 玻璃管 / 钨棒；蓝色染料 ×3；纸 ×3（照沉浸工程自己的蓝图配方的样子）
        net.minecraft.item.Item blueprint = ForgeRegistries.ITEMS.getValue(new ResourceLocation("immersiveengineering", "blueprint"));
        if (blueprint != null) {
            ItemStack bp = new ItemStack(blueprint);
            net.minecraft.nbt.NBTTagCompound tag = new net.minecraft.nbt.NBTTagCompound();
            tag.setString("blueprint", LAMP_BLUEPRINT);
            bp.setTagCompound(tag);
            ResourceLocation id = new ResourceLocation(YouranMod.MODID, "blueprint_mercury_lamp");
            registry.register(new ShapedOreRecipe(id, bp, "RTR", "DDD", "PPP", 'R', new ItemStack(ModItems.tungstenRod),
                    'T', new ItemStack(ModItems.glassTube, 1, net.minecraftforge.oredict.OreDictionary.WILDCARD_VALUE), 'D', "dyeBlue",
                    'P', new ItemStack(net.minecraft.init.Items.PAPER)).setRegistryName(id));
        }
        // 光源方块（服主 11-16）：汞灯 + 1 沉浸工程探照灯 → 光源方块，汞灯的数据全部带过去；光源方块单放一格 → 汞灯 + 探照灯，数据带回来
        ItemStack floodlight = ieFloodlight();
        if (!floodlight.isEmpty()) {
            ResourceLocation light = new ResourceLocation(YouranMod.MODID, "mercury_light_source");
            registry.register(new CopyLampShapeless(light, new ItemStack(ModBlocks.mercuryLightSource), new ItemStack(ModItems.mercuryLamp), floodlight)
                    .setRegistryName(light));
            registry.register(new SplitLightSource(floodlight).setRegistryName(new ResourceLocation(YouranMod.MODID, "mercury_light_source_split")));
        }
        // 对准台（服主 11-16）：钢板 / 玻璃板 / 钢板；钢制机械零件 / 工作台 / 钢制机械零件；钢板 / 电路板 / 钢板。
        // 正中两种都认：沉浸工程工程师装配台（wooden_device0:2）或 IC2 工业工作台（ic2:te:88）
        java.util.List<ItemStack> benches = new java.util.ArrayList<>();
        Item ieBench = ForgeRegistries.ITEMS.getValue(new ResourceLocation("immersiveengineering", "wooden_device0"));
        if (ieBench != null) {
            benches.add(new ItemStack(ieBench, 1, 2));
        }
        Item ic2Te = ForgeRegistries.ITEMS.getValue(new ResourceLocation("ic2", "te"));
        if (ic2Te != null) {
            benches.add(new ItemStack(ic2Te, 1, 88));
        }
        Item component = ForgeRegistries.ITEMS.getValue(new ResourceLocation("immersiveengineering", "material"));
        // 正下方的电路板也两种都认（服主 11-16）：IC2 电路板（矿物词典 circuitBasic）或沉浸工程电路板（material:27，沉浸工程没给它登记矿物词典）
        java.util.List<net.minecraft.item.crafting.Ingredient> circuitParts = new java.util.ArrayList<>();
        circuitParts.add(new net.minecraftforge.oredict.OreIngredient("circuitBasic"));
        if (component != null) {
            circuitParts.add(net.minecraft.item.crafting.Ingredient.fromStacks(new ItemStack(component, 1, 27)));
        }
        net.minecraft.item.crafting.Ingredient circuits = new net.minecraftforge.common.crafting.CompoundIngredient(circuitParts) {
        };
        if (!benches.isEmpty() && component != null) {
            ResourceLocation table = new ResourceLocation(YouranMod.MODID, "alignment_table");
            registry.register(new ShapedOreRecipe(table, new ItemStack(ModBlocks.alignmentTable),
                    "SGS", "CHC", "SBS", 'S', "plateSteel", 'G', "paneGlass", 'C', new ItemStack(component, 1, 9) /* 钢制机械零件：沉浸工程没登记矿物词典 */,
                    'H', net.minecraft.item.crafting.Ingredient.fromStacks(benches.toArray(new ItemStack[0])),
                    'B', circuits)
                    .setRegistryName(table));
        }
    }

    /** 沉浸工程探照灯（{@code immersiveengineering:metal_device1}，元数据 9）；没装时为空物品 */
    static ItemStack ieFloodlight() {
        Item i = ForgeRegistries.ITEMS.getValue(new ResourceLocation("immersiveengineering", "metal_device1"));
        return i == null ? ItemStack.EMPTY : new ItemStack(i, 1, 9);
    }


    /** 无序合成，产物照抄格子里汞灯的全部数据 */
    static final class CopyLampShapeless extends net.minecraftforge.oredict.ShapelessOreRecipe {
        CopyLampShapeless(ResourceLocation group, ItemStack result, Object... in) {
            super(group, result, in);
        }

        @Override
        public ItemStack getCraftingResult(InventoryCrafting inv) {
            return withLamp(super.getCraftingResult(inv), inv);
        }
    }

    /** 光源方块单放一格 → 汞灯（带着光源的数据），探照灯留在格子里 */
    static final class SplitLightSource extends net.minecraftforge.registries.IForgeRegistryEntry.Impl<IRecipe> implements IRecipe {
        private final ItemStack floodlight;

        SplitLightSource(ItemStack floodlight) {
            this.floodlight = floodlight;
        }

        private static int only(InventoryCrafting inv) {
            int found = -1;
            for (int i = 0; i < inv.getSizeInventory(); i++) {
                if (!inv.getStackInSlot(i).isEmpty()) {
                    if (found >= 0 || inv.getStackInSlot(i).getItem() != ModItems.mercuryLightSource) {
                        return -1;
                    }
                    found = i;
                }
            }
            return found;
        }

        @Override
        public boolean matches(InventoryCrafting inv, net.minecraft.world.World world) {
            return only(inv) >= 0;
        }

        @Override
        public ItemStack getCraftingResult(InventoryCrafting inv) {
            int i = only(inv);
            ItemStack lamp = new ItemStack(ModItems.mercuryLamp);
            if (i >= 0 && inv.getStackInSlot(i).hasTagCompound()) {
                lamp.setTagCompound(inv.getStackInSlot(i).getTagCompound().copy());
            }
            return lamp;
        }

        @Override
        public boolean canFit(int width, int height) {
            return width * height >= 1;
        }

        @Override
        public ItemStack getRecipeOutput() {
            return new ItemStack(ModItems.mercuryLamp);
        }

        @Override
        public net.minecraft.util.NonNullList<ItemStack> getRemainingItems(InventoryCrafting inv) {
            net.minecraft.util.NonNullList<ItemStack> left = net.minecraft.util.NonNullList.withSize(inv.getSizeInventory(), ItemStack.EMPTY);
            int i = only(inv);
            if (i >= 0) {
                left.set(i, floodlight.copy());
            }
            return left;
        }

        @Override
        public boolean isDynamic() {
            return true;
        }
    }

    static ItemStack withLamp(ItemStack out, InventoryCrafting inv) {
        for (int i = 0; i < inv.getSizeInventory(); i++) {
            ItemStack s = inv.getStackInSlot(i);
            if (!s.isEmpty() && s.getItem() == ModItems.mercuryLamp) {
                out.setTagCompound(s.hasTagCompound() ? s.getTagCompound().copy() : null);
                return out;
            }
        }
        return out;
    }

    // ================= 后初始化 =================

    public static void postInit(boolean ic2, boolean ie, boolean tic, boolean alchemistry) {
        if (ie && net.minecraftforge.fml.common.Loader.isModLoaded("immersivepetroleum")) {
            Ip.register();
        }
        // 液氙 ⇄ 炼金化学氙元素（服主 11-16）：同液氩
        if (alchemistry && moe.qingu.youran.registry.ModFluids.xenon != null) {
            AlchemistryCompat.registerAtomizerPair(new FluidStack(moe.qingu.youran.registry.ModFluids.xenon, LithoNumbers.XENON_ATOMIZER_MB),
                    AlchemistryCompat.chemical("xenon", LithoNumbers.XENON_ATOMIZER_ELEMENTS));
        }
        if (ie && ic2) {
            moe.qingu.youran.compat.immersiveengineering.XenonSqueezing.register();
        }
        // 液氩 ⇄ 炼金化学氩元素（服主 11-16）：雾化器 500 mB → 8 个，液化器反过来（同炼金化学自己的水）
        if (alchemistry && moe.qingu.youran.registry.ModFluids.argon != null) {
            AlchemistryCompat.registerAtomizerPair(new FluidStack(moe.qingu.youran.registry.ModFluids.argon, LithoNumbers.ARGON_ATOMIZER_MB),
                    AlchemistryCompat.chemical("argon", LithoNumbers.ARGON_ATOMIZER_ELEMENTS));
        }
        if (tic) {
            Tic.register();
        }
        if (ie) {
            Ie.register(alchemistry);
        }
        if (ic2 && alchemistry) {
            Ic2.register();
        }
    }

    /** 匠魂：玻璃管（已定） */
    static final class Tic {
        static void register() {
            net.minecraftforge.fluids.Fluid glass = FluidRegistry.getFluid("glass");
            Item gold = ForgeRegistries.ITEMS.getValue(new ResourceLocation("tconstruct", "cast"));
            Item clay = ForgeRegistries.ITEMS.getValue(new ResourceLocation("tconstruct", "clay_cast"));
            if (glass == null || gold == null || clay == null || !ForgeRegistries.ITEMS.containsKey(new ResourceLocation(TOOL_ROD))) {
                YouranMod.LOG.info("没有匠魂的熔融玻璃或手柄铸模，不加玻璃管的浇铸配方");
                return;
            }
            for (WaferMold mold : WaferMold.values()) {
                ItemStack cast = new ItemStack(mold == WaferMold.GOLD ? gold : clay);
                net.minecraft.nbt.NBTTagCompound tag = new net.minecraft.nbt.NBTTagCompound();
                tag.setString("PartType", TOOL_ROD);
                cast.setTagCompound(tag);
                // 粘土铸模照匠魂自己浇部件的规矩，用一次就没了；金铸模留着
                slimeknights.tconstruct.library.TinkerRegistry.registerTableCasting(new slimeknights.tconstruct.library.smeltery.CastingRecipe(
                        ModItems.glassTube.make(mold, 1), slimeknights.mantle.util.RecipeMatch.ofNBT(cast),
                        new FluidStack(glass, LithoNumbers.GLASS_TUBE_CASTING), LithoNumbers.GLASS_TUBE_CASTING_TIME, mold == WaferMold.CLAY, false));
            }
            YouranMod.LOG.info("已加匠魂浇铸台配方：{} mB 熔融玻璃 + 手柄铸模（金 / 粘土）→ 玻璃管", LithoNumbers.GLASS_TUBE_CASTING);
            if (moe.qingu.youran.registry.ModFluids.hydrargyrum != null) {
                slimeknights.tconstruct.library.TinkerRegistry.registerTableCasting(new MercuryCasting());
                YouranMod.LOG.info("已加匠魂浇铸台配方：汞灯补汞");
            }
        }
    }

    /** 沉浸工程：石英管、钨棒（冲压机）、汞灯蓝图 */
    static final class Ie {
        static void register(boolean alchemistry) {
            // 石英管：二氧化硅粉 → 1 个 9；n 个 9 → n + 1 个 9（按 NBT 比，每种纯度一条，JEI 只列头两条）
            if (!net.minecraftforge.oredict.OreDictionary.getOres("dustSiliconDioxide", false).isEmpty()) {
                blusunrize.immersiveengineering.api.crafting.ArcFurnaceRecipe.recipeList.add(new ListedArcRecipe(
                        ModItems.quartzTube.make(null, 1), "dustSiliconDioxide", true));
            }
            int passes = 0;
            for (int n = Purity.DEFAULT_NINES; n < Purity.MAX_NINES; n++) {
                ItemStack in = ModItems.quartzTube.make(null, n);
                blusunrize.immersiveengineering.api.crafting.ArcFurnaceRecipe.recipeList.add(new ListedArcRecipe(
                        ModItems.quartzTube.make(null, n + 1),
                        new blusunrize.immersiveengineering.api.crafting.IngredientStack(in).setUseNBT(true), n == Purity.DEFAULT_NINES));
                passes++;
            }
            YouranMod.LOG.info("已加沉浸工程电弧炉配方：二氧化硅粉 → 石英管（1 个 9），石英管再烧一遍多一个 9（{} 条）", passes);
            // 钨棒：金属冲压机（杆模）
            Item mold = ForgeRegistries.ITEMS.getValue(new ResourceLocation("immersiveengineering", "mold"));
            if (hasOre(TUNGSTEN_INGOT) && mold != null) {
                blusunrize.immersiveengineering.api.crafting.MetalPressRecipe.addRecipe(new ItemStack(ModItems.tungstenRod, LithoNumbers.TUNGSTEN_RODS_PER_INGOT),
                        TUNGSTEN_INGOT, new ItemStack(mold, 1, 2), 2400);
            }
            // 汞灯：工程师装配台蓝图“汞灯”（两种玻璃管 × 两种气体 × 两种接线；数据由 Mixin 带过去）
            moe.qingu.youran.compat.immersiveengineering.LampAssembly.register();
            // 主板、扩展卡（汉卡）：装芯片（服主 11-16）
            moe.qingu.youran.compat.immersiveengineering.BoardAssembly.register();
        }
    }

    /** 沉浸原油精馏塔：IC2 空气 → IC2 氧气 + 液氩（服主 11-16，不出氮） */
    static final class Ip {
        static void register() {
            FluidStack air = FluidRegistry.getFluidStack("ic2air", LithoNumbers.AIR_IN);
            FluidStack oxygen = FluidRegistry.getFluidStack("ic2oxygen", LithoNumbers.AIR_OXYGEN);
            if (air == null || oxygen == null || moe.qingu.youran.registry.ModFluids.argon == null) {
                YouranMod.LOG.info("没有 IC2 的空气或氧气，不加精馏塔的空气分离配方");
                return;
            }
            flaxbeard.immersivepetroleum.api.crafting.DistillationRecipe.addRecipe(new FluidStack[]{oxygen,
                            new FluidStack(moe.qingu.youran.registry.ModFluids.argon, LithoNumbers.AIR_ARGON)},
                    new ItemStack[0], air, LithoNumbers.AIR_ENERGY, 1, new float[0]);
            YouranMod.LOG.info("已加沉浸原油精馏塔配方：{} mB 空气 → {} mB 氧气 + {} mB 液氩", LithoNumbers.AIR_IN, LithoNumbers.AIR_OXYGEN,
                    LithoNumbers.AIR_ARGON);
        }
    }

    /** 电弧炉配方，可以不进 JEI（按纯度多登记的那些） */
    static final class ListedArcRecipe extends blusunrize.immersiveengineering.api.crafting.ArcFurnaceRecipe {
        private final boolean listed;

        ListedArcRecipe(ItemStack output, Object input, boolean listed) {
            super(output, input, ItemStack.EMPTY, ARC_TIME, ARC_ENERGY);
            this.listed = listed;
        }

        @Override
        public boolean listInJEI() {
            return listed;
        }
    }

    /** IC2：钨棒（金属成型机挤压）、掺钍阴极棒（固体装罐机：钨棒当容器 + 钍） */
    static final class Ic2 {
        static void register() {
            if (hasOre(TUNGSTEN_INGOT)) {
                ic2.api.recipe.Recipes.metalformerExtruding.addRecipe(ic2.api.recipe.Recipes.inputFactory.forOreDict(TUNGSTEN_INGOT), null, false,
                        new ItemStack(ModItems.tungstenRod, LithoNumbers.TUNGSTEN_RODS_PER_INGOT));
            }
            ItemStack thorium = AlchemistryCompat.chemical("thorium", 1);
            if (!thorium.isEmpty()) {
                ic2.api.recipe.Recipes.cannerBottle.addRecipe(ic2.api.recipe.Recipes.inputFactory.forStack(new ItemStack(ModItems.tungstenRod)),
                        ic2.api.recipe.Recipes.inputFactory.forStack(thorium), new ItemStack(ModItems.thoriatedCathodeRod), false);
                YouranMod.LOG.info("已加 IC2 固体装罐机配方：钨棒 + 钍 → 掺钍阴极棒");
            }
        }
    }

    /**
     * 匠魂浇铸台补汞：汞灯放在台上（当“铸模”，浇完换成补满的灯），浇汞补到 {@value LithoNumbers#LAMP_MERCURY} mB，
     * 浇多少看灯里还差多少（匠魂的浇铸台按 {@code getFluidAmount()} 定容量，这里给刚对上的那盏灯差的量）。已经满的不收。
     */
    static final class MercuryCasting implements slimeknights.tconstruct.library.smeltery.ICastingRecipe {
        static boolean isLamp(ItemStack s) {
            return !s.isEmpty() && s.getItem() == ModItems.mercuryLamp && s.getCount() == 1 && LampNbt.read(s.getTagCompound()).gas != null;
        }

        @Override
        public ItemStack getResult(ItemStack cast, net.minecraftforge.fluids.Fluid fluid) {
            ItemStack out = cast.copy();
            out.setTagCompound(LampNbt.tag(LampNbt.read(cast.getTagCompound()).refilled()));
            return out;
        }

        @Override
        public FluidStack getFluid(ItemStack cast, net.minecraftforge.fluids.Fluid fluid) {
            int need = isLamp(cast) ? LampNbt.read(cast.getTagCompound()).mercurySpace() : LithoNumbers.LAMP_MERCURY;
            return new FluidStack(fluid, Math.max(1, need));
        }

        /** 浇铸台找到配方后马上按 {@link #getFluidAmount()} 定罐的容量（不带铸模参数），所以记下刚对上的那盏灯还差多少 */
        private int lastSpace = LithoNumbers.LAMP_MERCURY;

        @Override
        public boolean matches(ItemStack cast, net.minecraftforge.fluids.Fluid fluid) {
            if (fluid != moe.qingu.youran.registry.ModFluids.hydrargyrum || !isLamp(cast)) {
                return false;
            }
            int space = LampNbt.read(cast.getTagCompound()).mercurySpace();
            if (space <= 0) {
                return false;
            }
            lastSpace = space;
            return true;
        }

        @Override
        public boolean switchOutputs() {
            return false;
        }

        @Override
        public boolean consumesCast() {
            return true;
        }

        @Override
        public int getTime() {
            return LithoNumbers.MERCURY_CASTING_TIME;
        }

        @Override
        public int getFluidAmount() {
            return lastSpace;
        }
    }
}
