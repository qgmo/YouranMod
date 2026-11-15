package moe.qingu.youran.compat.jei;

import moe.qingu.youran.fluid.FluidPurity;
import moe.qingu.youran.item.ItemSiliconWafer;
import moe.qingu.youran.logic.purity.WashWater;
import moe.qingu.youran.logic.purity.WaferMold;
import moe.qingu.youran.logic.purity.WaferRules;
import moe.qingu.youran.registry.ModItems;
import moe.qingu.youran.logic.purity.Purity;
import moe.qingu.youran.logic.reaction.FluidItemReaction;
import moe.qingu.youran.logic.reaction.ItemIngredient;
import moe.qingu.youran.recipe.CannerReactions;
import moe.qingu.youran.recipe.ReactionItem;
import net.minecraft.client.resources.I18n;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.util.ResourceLocation;
import net.minecraftforge.fluids.FluidRegistry;
import net.minecraftforge.fluids.FluidStack;
import net.minecraftforge.fml.common.registry.ForgeRegistries;
import net.minecraftforge.oredict.OreDictionary;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** 给 JEI 的配方：装罐机反应配方表、太阳能蒸馏机、精馏塔提纯（只用本模组与 Forge 的类，各模组的机器另见各自的类） */
final class JeiRecipes {
    /** 提纯一遍一遍列出来：从 90% 到 9 个 9 */
    static final int PURIFY_PASSES = 8;

    private JeiRecipes() {
    }

    /** 装罐机反应配方表里的全部配方；输入、输出的流体或物品不存在的（没装对应模组）跳过 */
    static List<FluidItemRecipe> cannerReactions() {
        List<FluidItemRecipe> list = new ArrayList<>();
        for (FluidItemReaction<ItemStack, ReactionItem> r : CannerReactions.TABLE.reactions()) {
            FluidStack in = FluidRegistry.getFluidStack(r.inputFluid(), r.inputAmount());
            List<ItemStack> items = stacks(r.inputItem());
            FluidStack out = r.outputFluid() == null ? null : FluidRegistry.getFluidStack(r.outputFluid(), r.outputAmount());
            ItemStack outItem = r.outputItem() == null ? ItemStack.EMPTY : r.outputItem().toStack();
            if (in == null || items.isEmpty() || (r.outputFluid() != null && out == null) || (r.outputItem() != null && outItem.isEmpty())) {
                continue;
            }
            list.add(new FluidItemRecipe(in, items, out, outItem, Collections.<String>emptyList()));
        }
        return list;
    }

    /** 配料能对上的物品（数量是每次消耗的数量） */
    static List<ItemStack> stacks(ItemIngredient<ItemStack> ingredient) {
        List<ItemStack> list = new ArrayList<>();
        if (ingredient.oreName() != null) {
            for (ItemStack s : OreDictionary.getOres(ingredient.oreName(), false)) {
                list.add(withCount(s, ingredient.count()));
            }
        } else if (ingredient.itemId() != null) {
            Item item = ForgeRegistries.ITEMS.getValue(new ResourceLocation(ingredient.itemId()));
            if (item != null) {
                list.add(new ItemStack(item, ingredient.count(), ingredient.meta()));
            }
        } else {
            for (ItemStack s : ingredient.examples()) {
                list.add(withCount(s, ingredient.count()));
            }
        }
        return list;
    }

    private static ItemStack withCount(ItemStack stack, int count) {
        ItemStack copy = stack.copy();
        copy.setCount(count);
        return copy;
    }

    /** 提纯：10 mB 1 个 9（90%）→ 9 mB 2 个 9 → … → 9 mB 9 个 9，一遍一条，每条带上同样的说明 */
    static List<FluidItemRecipe> purification(String noteKey, Object... noteArgs) {
        List<FluidItemRecipe> list = new ArrayList<>();
        int nines = Purity.DEFAULT_NINES;
        String note = I18n.format(noteKey, noteArgs);
        for (int pass = 0; pass < PURIFY_PASSES; pass++) {
            int next = Purity.distilled(nines);
            list.add(FluidItemRecipe.fluids(FluidPurity.trichlorosilane(Purity.BATCH_INPUT, nines),
                    FluidPurity.trichlorosilane(Purity.BATCH_OUTPUT, next), Collections.singletonList(note)));
            nines = next;
        }
        return list;
    }

    /** 一条流体 → 流体的配方（如水 → 蒸馏水）；流体没注册时为 null */
    @Nullable
    static FluidItemRecipe fluid(String in, int inAmount, String out, int outAmount, String noteKey, Object... noteArgs) {
        FluidStack input = FluidRegistry.getFluidStack(in, inAmount);
        FluidStack output = FluidRegistry.getFluidStack(out, outAmount);
        if (input == null || output == null) {
            return null;
        }
        return FluidItemRecipe.fluids(input, output, Collections.singletonList(I18n.format(noteKey, noteArgs)));
    }

    /**
     * 洗矿机洗硅晶圆：整合包里有的每种水一条（1000 mB 水 + 9 个 9 的硅晶圆 → 高精硅晶圆），说明里写纯净率与 9 个 9 时
     * 金、粘土铸模的报废率。
     */
    static List<FluidItemRecipe> waferWashing(List<String> waters) {
        List<FluidItemRecipe> list = new ArrayList<>();
        for (String name : waters) {
            FluidStack water = FluidRegistry.getFluidStack(name, WaferRules.WASH_WATER);
            if (water == null) {
                continue;
            }
            int rate = WashWater.rate(name);
            List<ItemStack> wafers = new ArrayList<>();
            for (WaferMold mold : WaferMold.values()) {
                wafers.add(ItemSiliconWafer.make(ModItems.siliconWafer, Purity.WAFER_GRADE_NINES, mold));
            }
            String note = I18n.format("youranmod.jei.ore_washing_wafer.note", rate,
                    percent(WaferRules.scrapChance(rate, Purity.WAFER_GRADE_NINES, WaferMold.GOLD)),
                    percent(WaferRules.scrapChance(rate, Purity.WAFER_GRADE_NINES, WaferMold.CLAY)));
            list.add(new FluidItemRecipe(water, wafers, null,
                    ItemSiliconWafer.make(ModItems.precisionSiliconWafer, Purity.WAFER_GRADE_NINES, WaferMold.GOLD), Collections.singletonList(note)));
        }
        return list;
    }

    /** 概率写成百分数，最多一位小数：0.075 →“7.5%”，0.4 →“40%” */
    static String percent(double p) {
        String s = String.format(java.util.Locale.ROOT, "%.1f", p * 100);
        if (s.endsWith(".0")) {
            s = s.substring(0, s.length() - 2);
        }
        return s + "%";
    }

    /** 按注册名与元数据取物品；没有时为空物品 */
    static ItemStack item(String id, int meta) {
        Item item = ForgeRegistries.ITEMS.getValue(new ResourceLocation(id));
        return item == null ? ItemStack.EMPTY : new ItemStack(item, 1, meta);
    }
}
