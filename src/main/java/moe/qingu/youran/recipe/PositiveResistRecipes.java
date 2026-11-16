package moe.qingu.youran.recipe;

import moe.qingu.youran.YouranMod;
import moe.qingu.youran.compat.alchemistry.AlchemistryCompat;
import moe.qingu.youran.fluid.FluidVariants;
import moe.qingu.youran.logic.litho.ResistType;
import moe.qingu.youran.logic.reaction.FluidItemReaction;
import moe.qingu.youran.logic.reaction.ItemIngredient;
import moe.qingu.youran.logic.resist.PositiveDevelop;
import moe.qingu.youran.logic.resist.ResistNumbers;
import moe.qingu.youran.registry.ModFluids;
import moe.qingu.youran.registry.ModItems;
import net.minecraft.inventory.InventoryCrafting;
import net.minecraft.item.ItemStack;
import net.minecraft.item.crafting.IRecipe;
import net.minecraft.item.crafting.Ingredient;
import net.minecraft.util.NonNullList;
import net.minecraft.util.ResourceLocation;
import net.minecraftforge.fluids.FluidRegistry;
import net.minecraftforge.fluids.FluidStack;
import net.minecraftforge.fluids.FluidUtil;
import net.minecraftforge.fluids.capability.IFluidHandlerItem;
import net.minecraftforge.oredict.OreIngredient;
import net.minecraftforge.oredict.ShapelessOreRecipe;
import net.minecraftforge.registries.IForgeRegistry;

import java.util.ArrayList;
import java.util.List;

/**
 * 正胶线（服主 11-16，光刻.md“正胶”）：
 * <pre>
 * 杂酚油 → 芳烃 + 甲酚 + 10% 一个萘（沉浸原油精馏塔；在 {@link ResistRecipes} 里）
 * 萘 + 辅料 2 硫磺粉 + 辅料 4 氢氧根 或 2 碳酸钾（炼金化学）→ 萘酚磺酸粉（沉浸工程电弧炉）
 * 萘酚磺酸粉 + 叠氮化钠粉 + 一桶水（或类水流体）→ 重氮萘醌磺酸粉 + 空桶（工作台无序；沉浸工程装配机用罐里的水顶替水桶）
 * 重氮萘醌磺酸粉 + 1000 mB 氯气 → 重氮萘醌磺酰氯粉（IC2 装罐机、沉浸工程灌装机）
 * 1000 mB 酚醛树脂 + 重氮萘醌磺酰氯粉 → 1000 mB 光刻胶（NBT 正胶）（IC2 装罐机、沉浸工程搅拌机）
 * </pre>
 * 用矿物词典认材料：萘 {@code crystalNaphthalene}、三种粉 {@code dustNaphtholSulfonicAcid}、{@code dustDiazonaphthoquinoneSulfonicAcid}、
 * {@code dustDiazonaphthoquinoneSulfonylChloride}。别的模组都是软依赖。数量见 {@link ResistNumbers}。
 */
public final class PositiveResistRecipes {
    static final String ORE_NAPHTHALENE = "crystalNaphthalene";
    static final String ORE_NAPHTHOL = "dustNaphtholSulfonicAcid";
    static final String ORE_DIAZO = "dustDiazonaphthoquinoneSulfonicAcid";
    static final String ORE_SULFONYL = "dustDiazonaphthoquinoneSulfonylChloride";
    static final String ORE_SULFUR = "dustSulfur";
    static final String PHENOLIC_RESIN = "phenolic_resin";

    private PositiveResistRecipes() {
    }

    // ================= 装罐机反应（两端，配方注册事件里由 CannerReactions 调）=================

    static List<FluidItemReaction<ItemStack, ReactionItem>> cannerReactions() {
        List<FluidItemReaction<ItemStack, ReactionItem>> l = new ArrayList<>();
        l.add(new FluidItemReaction<>(CannerReactions.CHLORINE, ResistNumbers.SULFONYL_CHLORINE, ItemIngredient.ore(ORE_DIAZO, 1),
                null, 0, new ReactionItem(YouranMod.MODID + ":dnq_sulfonyl_chloride_dust", 0, 1, null)));
        l.add(new FluidItemReaction<>(PHENOLIC_RESIN, ResistNumbers.RESIST_RESIN, ItemIngredient.ore(ORE_SULFONYL, 1),
                FluidVariants.photoresistKey(ResistType.POSITIVE), ResistNumbers.RESIST_OUT, null));
        return l;
    }

    // ================= 工作台（配方注册事件）=================

    /** 工作台无序：萘酚磺酸粉 + 叠氮化钠粉 + 一桶水（每种类水流体一条，装了才加）→ 重氮萘醌磺酸粉，桶还回来 */
    public static void registerCrafting(IForgeRegistry<IRecipe> registry) {
        int added = 0;
        for (String name : PositiveDevelop.WATERS) {
            if (!FluidRegistry.isFluidRegistered(name)) {
                continue;
            }
            ResourceLocation id = new ResourceLocation(YouranMod.MODID, "dnq_sulfonic_acid_" + name);
            registry.register(new WaterBucketRecipe(id, new ItemStack(ModItems.dnqSulfonicAcidDust), FluidRegistry.getFluid(name),
                    new OreIngredient(ORE_NAPHTHOL), Ingredient.fromStacks(new ItemStack(ModItems.sodiumAzideDust))));
            added++;
        }
        YouranMod.LOG.info("已加工作台配方：萘酚磺酸粉 + 叠氮化钠粉 + 一桶水 → 重氮萘醌磺酸粉（{} 种水）", added);
    }

    /**
     * 配料里有一桶水（或类水流体；任何装着这种流体的容器，至少 1000 mB）的无序配方：做完把容器里的水取走 1000 mB 还回来。
     * 每种水一条配方，配料里的“水桶”只有一种候选，沉浸工程装配机才认得出是流体（用罐里的水顶替水桶）。
     */
    static final class WaterBucketRecipe extends ShapelessOreRecipe {
        private final WaterContainer water;

        WaterBucketRecipe(ResourceLocation id, ItemStack output, net.minecraftforge.fluids.Fluid fluid, Ingredient... others) {
            super(id, output, ingredients(fluid, others));
            this.water = (WaterContainer) input.get(input.size() - 1);
            setRegistryName(id);
        }

        private static Object[] ingredients(net.minecraftforge.fluids.Fluid fluid, Ingredient[] others) {
            Object[] all = new Object[others.length + 1];
            System.arraycopy(others, 0, all, 0, others.length);
            all[others.length] = new WaterContainer(fluid);
            return all;
        }

        @Override
        public NonNullList<ItemStack> getRemainingItems(InventoryCrafting inv) {
            NonNullList<ItemStack> left = super.getRemainingItems(inv);
            for (int i = 0; i < inv.getSizeInventory(); i++) {
                ItemStack s = inv.getStackInSlot(i);
                if (!s.isEmpty() && water.apply(s)) {
                    ItemStack one = s.copy();
                    one.setCount(1);
                    IFluidHandlerItem handler = FluidUtil.getFluidHandler(one);
                    if (handler != null) {
                        handler.drain(ResistNumbers.DIAZO_WATER, true);
                        left.set(i, handler.getContainer());
                    }
                }
            }
            return left;
        }
    }

    /** 装着至少一桶（{@link ResistNumbers#DIAZO_WATER} mB）这种流体的容器；JEI、装配机里显示成一个装满的桶 */
    static final class WaterContainer extends Ingredient {
        private final net.minecraftforge.fluids.Fluid fluid;

        WaterContainer(net.minecraftforge.fluids.Fluid fluid) {
            super(display(fluid));
            this.fluid = fluid;
        }

        /** 显示用的装满的容器：有桶的用桶，没有桶的（如 IC2 蒸馏水）用 IC2 的流体单元 */
        private static ItemStack display(net.minecraftforge.fluids.Fluid fluid) {
            FluidStack full = new FluidStack(fluid, ResistNumbers.DIAZO_WATER);
            ItemStack bucket = FluidUtil.getFilledBucket(full);
            if (!bucket.isEmpty()) {
                return bucket;
            }
            net.minecraft.item.Item cell = net.minecraftforge.fml.common.registry.ForgeRegistries.ITEMS.getValue(new ResourceLocation("ic2", "fluid_cell"));
            if (cell != null) {
                ItemStack filled = new ItemStack(cell);
                IFluidHandlerItem handler = FluidUtil.getFluidHandler(filled);
                if (handler != null && handler.fill(full, true) == ResistNumbers.DIAZO_WATER) {
                    return handler.getContainer();
                }
            }
            return ItemStack.EMPTY;
        }

        @Override
        public boolean apply(ItemStack stack) {
            if (stack == null || stack.isEmpty()) {
                return false;
            }
            FluidStack contained = FluidUtil.getFluidContained(stack);
            return contained != null && contained.getFluid() == fluid && contained.amount >= ResistNumbers.DIAZO_WATER;
        }

        @Override
        public boolean isSimple() {
            return false;
        }
    }

    // ================= 后初始化 =================

    public static void postInit(boolean ie, boolean alchemistry) {
        if (ie) {
            Ie.register(alchemistry);
        }
    }

    /** 沉浸工程：电弧炉（萘酚磺酸粉）、灌装机（重氮萘醌磺酰氯粉）、搅拌机（正胶） */
    static final class Ie {
        static void register(boolean alchemistry) {
            // 电弧炉：萘 + 2 硫磺粉 + 4 氢氧根 / 2 碳酸钾
            ItemStack hydroxide = AlchemistryCompat.stack(AlchemistryCompat.HYDROXIDE, ResistNumbers.NAPHTHOL_HYDROXIDE);
            ItemStack carbonate = AlchemistryCompat.stack(AlchemistryCompat.POTASSIUM_CARBONATE, ResistNumbers.NAPHTHOL_CARBONATE);
            if (hydroxide.isEmpty() || carbonate.isEmpty()) {
                YouranMod.LOG.info("没有炼金化学的氢氧根或碳酸钾，不加沉浸工程电弧炉的萘酚磺酸粉配方");
            } else {
                for (ItemStack alkali : new ItemStack[]{hydroxide, carbonate}) {
                    blusunrize.immersiveengineering.api.crafting.ArcFurnaceRecipe.addRecipe(new ItemStack(ModItems.naphtholSulfonicAcidDust),
                            ORE_NAPHTHALENE, ItemStack.EMPTY, ResistNumbers.NAPHTHOL_ARC_TIME, ResistNumbers.NAPHTHOL_ARC_ENERGY,
                            new blusunrize.immersiveengineering.api.crafting.IngredientStack(ORE_SULFUR, ResistNumbers.NAPHTHOL_SULFUR),
                            new blusunrize.immersiveengineering.api.crafting.IngredientStack(alkali));
                }
                YouranMod.LOG.info("已加沉浸工程电弧炉配方：萘 + {} 硫磺粉 + {} 氢氧根 / {} 碳酸钾 → 萘酚磺酸粉", ResistNumbers.NAPHTHOL_SULFUR,
                        ResistNumbers.NAPHTHOL_HYDROXIDE, ResistNumbers.NAPHTHOL_CARBONATE);
            }
            // 灌装机：重氮萘醌磺酸粉 + 1000 mB 氯气 → 重氮萘醌磺酰氯粉
            blusunrize.immersiveengineering.api.crafting.BottlingMachineRecipe.addRecipe(new ItemStack(ModItems.dnqSulfonylChlorideDust),
                    ORE_DIAZO, new FluidStack(ModFluids.chlorine, ResistNumbers.SULFONYL_CHLORINE));
            // 搅拌机：酚醛树脂 + 重氮萘醌磺酰氯粉 → 正胶
            blusunrize.immersiveengineering.api.crafting.MixerRecipe.addRecipe(FluidVariants.photoresist(ResistType.POSITIVE, ResistNumbers.RESIST_OUT),
                    new FluidStack(ModFluids.phenolicResin, ResistNumbers.RESIST_RESIN), new Object[]{ORE_SULFONYL}, ResistRecipes.MIXER_ENERGY);
            YouranMod.LOG.info("已加沉浸工程的正胶线配方（电弧炉、灌装机、搅拌机）");
        }
    }
}
