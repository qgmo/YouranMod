package moe.qingu.youran.compat.crafttweaker;

import crafttweaker.CraftTweakerAPI;
import crafttweaker.IAction;
import crafttweaker.annotations.ZenRegister;
import crafttweaker.api.item.IIngredient;
import crafttweaker.api.item.IItemStack;
import crafttweaker.api.liquid.ILiquidStack;
import crafttweaker.api.minecraft.CraftTweakerMC;
import crafttweaker.api.oredict.IOreDictEntry;
import moe.qingu.youran.logic.reaction.FluidItemReaction;
import moe.qingu.youran.logic.reaction.ItemIngredient;
import moe.qingu.youran.recipe.CannerReactions;
import moe.qingu.youran.recipe.ReactionItem;
import net.minecraft.item.ItemStack;
import stanhebben.zenscript.annotations.ZenClass;
import stanhebben.zenscript.annotations.ZenMethod;

/**
 * CraftTweaker 脚本里的 {@code mods.youranmod.CannerReaction}：增删 IC2 装罐机富集模式的反应配方（{@link CannerReactions}）。
 * 只有装了 CraftTweaker 时才会被加载（CraftTweaker 按 {@code @ZenRegister} 注解找到这个类）。
 *
 * <pre>
 * // 输入流体, 输入物品（可以是矿物词典、带数量）, 输出流体（可为 null）, 输出物品（可为 null）
 * mods.youranmod.CannerReaction.add(&lt;liquid:ic2superheated_steam&gt; * 1000, &lt;ore:dustIron&gt;, &lt;liquid:ic2hydrogen&gt; * 1000, &lt;youranmod:magnetite_dust&gt;);
 * mods.youranmod.CannerReaction.remove(&lt;youranmod:magnetite_dust&gt;);   // 按输出物品删；给流体时按输出流体删
 * mods.youranmod.CannerReaction.removeAll();
 * </pre>
 */
@ZenRegister
@ZenClass("mods.youranmod.CannerReaction")
public final class CannerReactionTweaker {
    private CannerReactionTweaker() {
    }

    @ZenMethod
    public static void add(ILiquidStack input, IIngredient item, ILiquidStack output, IItemStack outputItem) {
        FluidItemReaction<ItemStack, ReactionItem> reaction;
        try {
            reaction = toReaction(input, item, output, outputItem);
        } catch (IllegalArgumentException e) {
            CraftTweakerAPI.logError("mods.youranmod.CannerReaction.add：" + e.getMessage());
            return;
        }
        CraftTweakerAPI.apply(new IAction() {
            @Override
            public void apply() {
                CannerReactions.TABLE.add(reaction);
            }

            @Override
            public String describe() {
                return "装罐机反应配方：加入 " + reaction;
            }
        });
    }

    /** 按输出删：给物品（可以是矿物词典）时删输出物品对得上的，给流体时删输出流体是它的 */
    @ZenMethod
    public static void remove(IIngredient output) {
        if (output == null) {
            CraftTweakerAPI.logError("mods.youranmod.CannerReaction.remove：输出不能为 null");
            return;
        }
        CraftTweakerAPI.apply(new IAction() {
            @Override
            public void apply() {
                int n;
                if (output instanceof ILiquidStack) {
                    String fluid = ((ILiquidStack) output).getName();
                    n = CannerReactions.TABLE.removeIf(r -> fluid.equals(r.outputFluid()));
                } else {
                    IIngredient one = output.amount(1);
                    n = CannerReactions.TABLE.removeIf(r -> {
                        if (r.outputItem() == null) {
                            return false;
                        }
                        ItemStack stack = r.outputItem().toStack();
                        return !stack.isEmpty() && one.matches(CraftTweakerMC.getIItemStack(stack));
                    });
                }
                if (n == 0) {
                    CraftTweakerAPI.logWarning("mods.youranmod.CannerReaction.remove：没有输出是 " + output.toCommandString() + " 的反应");
                }
            }

            @Override
            public String describe() {
                return "装罐机反应配方：删掉输出是 " + output.toCommandString() + " 的";
            }
        });
    }

    @ZenMethod
    public static void removeAll() {
        CraftTweakerAPI.apply(new IAction() {
            @Override
            public void apply() {
                CannerReactions.TABLE.clear();
            }

            @Override
            public String describe() {
                return "装罐机反应配方：全部删掉";
            }
        });
    }

    static FluidItemReaction<ItemStack, ReactionItem> toReaction(ILiquidStack input, IIngredient item, ILiquidStack output, IItemStack outputItem) {
        if (input == null) {
            throw new IllegalArgumentException("输入流体不能为 null");
        }
        if (item == null) {
            throw new IllegalArgumentException("输入物品不能为 null");
        }
        ItemIngredient<ItemStack> ingredient;
        if (item instanceof IOreDictEntry) {
            ingredient = ItemIngredient.ore(((IOreDictEntry) item).getName(), item.getAmount());
        } else {
            IIngredient one = item.amount(1);
            int count = item.getAmount();
            ingredient = ItemIngredient.custom(stack -> one.matches(CraftTweakerMC.getIItemStack(stack)), count, item.toCommandString(),
                    () -> examples(one, count));
        }
        ReactionItem out = null;
        if (outputItem != null) {
            ItemStack stack = CraftTweakerMC.getItemStack(outputItem);
            out = ReactionItem.of(stack);
        }
        return new FluidItemReaction<>(input.getName(), input.getAmount(), ingredient,
                output == null ? null : output.getName(), output == null ? 0 : output.getAmount(), out);
    }

    /** CraftTweaker 配料能列出的物品（JEI 显示装罐机反应配方用），数量改成每次消耗的数量 */
    static java.util.List<ItemStack> examples(IIngredient ingredient, int count) {
        java.util.List<ItemStack> stacks = new java.util.ArrayList<>();
        for (IItemStack s : ingredient.getItems()) {
            ItemStack stack = CraftTweakerMC.getItemStack(s);
            if (!stack.isEmpty()) {
                stack = stack.copy();
                stack.setCount(count);
                stacks.add(stack);
            }
        }
        return stacks;
    }
}
