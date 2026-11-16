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
        return reactions(CannerReactions.TABLE.reactions());
    }

    /** 装罐模式的装罐反应（流体 + 固体 → 固体）；另加上汞灯补汞、激光腔灌氩（靠物品的流体能力，没有登记成配方） */
    static List<FluidItemRecipe> cannerBottlings() {
        List<FluidItemRecipe> list = reactions(CannerReactions.BOTTLING.reactions());
        list.addAll(JeiSteps.containerFills());
        return list;
    }

    private static List<FluidItemRecipe> reactions(List<FluidItemReaction<ItemStack, ReactionItem>> reactions) {
        List<FluidItemRecipe> list = new ArrayList<>();
        for (FluidItemReaction<ItemStack, ReactionItem> r : reactions) {
            FluidStack in = moe.qingu.youran.fluid.FluidVariants.stack(r.inputFluid(), r.inputAmount());
            if (in != null && CannerReactions.MIXTURE.equals(r.inputFluid())) {
                // 混合气只有 9 个 9 起的（配方表按流体种类比，这里显示能做出来的最低纯度，JEI 才能连到炼油厂、冶炼炉）
                in = FluidPurity.mixture(r.inputAmount(), Purity.WAFER_GRADE_NINES);
            }
            List<ItemStack> items = stacks(r.inputItem());
            FluidStack out = r.outputFluid() == null ? null : moe.qingu.youran.fluid.FluidVariants.stack(r.outputFluid(), r.outputAmount());
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
     * 洗矿机洗硅晶圆：整合包里有的每种水一条（1000 mB 水 + 9 个 9 的硅晶圆 → 高精硅晶圆），说明里写水质档次
     * 与“水越干净、金铸模比粘土铸模越不容易洗坏”（定性，不写报废率）。
     */
    static List<FluidItemRecipe> waferWashing(List<String> waters) {
        List<FluidItemRecipe> list = new ArrayList<>();
        for (String name : waters) {
            FluidStack water = FluidRegistry.getFluidStack(name, WaferRules.WASH_WATER);
            if (water == null) {
                continue;
            }
            List<ItemStack> wafers = new ArrayList<>();
            for (WaferMold mold : WaferMold.values()) {
                wafers.add(ItemSiliconWafer.make(ModItems.siliconWafer, Purity.WAFER_GRADE_NINES, mold));
            }
            // 服主 11-16：不写报废率与纯净率的数字，只说水质档次和“谁比谁好”
            List<String> notes = java.util.Arrays.asList(
                    I18n.format("youranmod.jei.ore_washing_wafer.note", I18n.format("youranmod.jei.water_grade." + WashWater.grade(name))),
                    I18n.format("youranmod.jei.ore_washing_wafer.scrap"));
            // 洗出来的是高精硅晶圆，也可能是报废的（IC2 洗矿机页的输出有三格，这里用前两格）
            List<ItemStack> outputs = java.util.Arrays.asList(
                    ItemSiliconWafer.make(ModItems.precisionSiliconWafer, Purity.WAFER_GRADE_NINES, WaferMold.GOLD),
                    new ItemStack(ModItems.scrapSiliconWafer));
            list.add(new FluidItemRecipe(water, wafers, null, outputs, notes));
        }
        return list;
    }

    /** 按注册名与元数据取物品；没有时为空物品 */
    static ItemStack item(String id, int meta) {
        Item item = ForgeRegistries.ITEMS.getValue(new ResourceLocation(id));
        return item == null ? ItemStack.EMPTY : new ItemStack(item, 1, meta);
    }
}
