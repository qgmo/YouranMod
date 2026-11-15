package moe.qingu.youran.compat.ic2;

import ic2.api.recipe.ICannerEnrichRecipeManager;
import ic2.api.recipe.MachineRecipe;
import ic2.api.recipe.MachineRecipeResult;
import ic2.core.block.machine.tileentity.TileEntityCanner;
import ic2.core.util.StackUtil;
import moe.qingu.youran.YouranMod;
import moe.qingu.youran.logic.reaction.FluidItemReaction;
import moe.qingu.youran.logic.reaction.ReactionPlan;
import moe.qingu.youran.recipe.CannerReactions;
import moe.qingu.youran.recipe.ReactionItem;
import net.minecraft.item.ItemStack;
import net.minecraftforge.fluids.FluidRegistry;
import net.minecraftforge.fluids.FluidStack;

import javax.annotation.Nullable;
import java.util.Collection;
import java.util.Collections;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 装罐机富集模式里反应配方的做法，由 {@code mixin.ic2} 调用。只有装了 IC2 时才会被加载。
 *
 * <p>照 IC2 原来的规则：耗电、处理时间、进度条、超频升级由 {@code TileEntityStandardMachine} 管，这里只回答“这一 tick 能不能做、
 * 做的是什么”（{@link #getOutput}）与“做完时怎么交付”（{@link #operateOnce}）。{@code getOutput()} 返回 null 时 IC2 不开始，
 * 做到一半时进度清零（同它自己的配方输出满了的情况）。
 *
 * <p>输入槽、输入罐的扣减交给 IC2 自己（{@code InvSlotProcessableCanner#consume} → {@code setInput}），
 * 所以“调整后的输入”用 IC2 富集配方同样的 {@link ICannerEnrichRecipeManager.RawInput}。
 * 输出的流体只进流体输出罐，不往“罐子”槽里的空容器装：装满的容器也要放进物品输出槽，和输出物品抢同一格。
 */
public final class CannerReactionHooks {
    /** 找不到的输出（物品或流体没注册）只记一次日志 */
    private static final Set<String> MISSING = ConcurrentHashMap.newKeySet();

    private CannerReactionHooks() {
    }

    /** {@link #getOutput} 的结果：handled 为 false 时照 IC2 原来的走 */
    public static final class Outcome {
        static final Outcome NOT_HANDLED = new Outcome(false, null);
        static final Outcome IDLE = new Outcome(true, null);

        public final boolean handled;
        @Nullable
        public final MachineRecipeResult<Object, Object, Object> result;

        Outcome(boolean handled, @Nullable MachineRecipeResult<Object, Object, Object> result) {
            this.handled = handled;
            this.result = result;
        }
    }

    /** 富集模式下这一 tick 要不要做反应配方（调用方已确认是富集模式） */
    public static Outcome getOutput(TileEntityCanner canner) {
        ItemStack additive = canner.inputSlot.get();
        FluidStack tank = canner.inputTank.getFluid();
        if (tank == null || tank.amount <= 0 || StackUtil.isEmpty(additive)) {
            return Outcome.NOT_HANDLED;
        }
        ReactionPlan<ItemStack, ReactionItem> plan = CannerReactions.TABLE.plan(tank.getFluid().getName(), tank.amount, additive,
                r -> outputsFit(canner, r));
        if (plan.status() == ReactionPlan.Status.NO_MATCH) {
            return Outcome.NOT_HANDLED;
        }
        if (plan.status() != ReactionPlan.Status.READY) {
            return Outcome.IDLE;
        }
        FluidItemReaction<ItemStack, ReactionItem> r = plan.reaction();
        FluidStack left = tank.copy();
        left.amount -= r.inputAmount();
        ICannerEnrichRecipeManager.RawInput adjusted = new ICannerEnrichRecipeManager.RawInput(left.amount > 0 ? left : null,
                StackUtil.copyShrunk(additive, r.inputItem().count()));
        ItemStack made = r.outputItem() == null ? ItemStack.EMPTY : r.outputItem().toStack(tank.copy(), additive.copy());
        CannerReactionOutput output = new CannerReactionOutput(outputFluid(r), made);
        return new Outcome(true, new MachineRecipeResult<>(new MachineRecipe<Object, Object>(r, output), adjusted));
    }

    /** 物品输出槽、流体输出罐都放得下（IC2 的规则：{@code InvSlotOutput#canAdd}、{@code FluidTank#fill} 模拟） */
    private static boolean outputsFit(TileEntityCanner canner, FluidItemReaction<ItemStack, ReactionItem> r) {
        if (r.outputItem() != null) {
            ItemStack item = outputItem(r);
            if (item.isEmpty() || !canner.outputSlot.canAdd(item)) {
                return false;
            }
        }
        if (r.outputFluid() != null) {
            FluidStack fluid = outputFluid(r);
            return fluid != null && canner.outputTank.fill(fluid, false) == fluid.amount;
        }
        return true;
    }

    private static ItemStack outputItem(FluidItemReaction<ItemStack, ReactionItem> r) {
        if (r.outputItem() == null) {
            return ItemStack.EMPTY;
        }
        ItemStack stack = r.outputItem().toStack();
        if (stack.isEmpty() && MISSING.add("item:" + r.outputItem().itemId())) {
            YouranMod.LOG.error("装罐机反应配方的输出物品 {} 不存在，这条反应不会开始：{}", r.outputItem().itemId(), r);
        }
        return stack;
    }

    @Nullable
    private static FluidStack outputFluid(FluidItemReaction<ItemStack, ReactionItem> r) {
        if (r.outputFluid() == null) {
            return null;
        }
        FluidStack stack = FluidRegistry.getFluidStack(r.outputFluid(), r.outputAmount());
        if (stack == null && MISSING.add("fluid:" + r.outputFluid())) {
            YouranMod.LOG.error("装罐机反应配方的输出流体 {} 没有注册，这条反应不会开始：{}", r.outputFluid(), r);
        }
        return stack;
    }

    /** {@code TileEntityCanner#getOutput(Object)}：反应配方的输出给物品输出槽的那部分；不是反应配方时为 null */
    @Nullable
    public static Collection<ItemStack> processResult(Object output) {
        return output instanceof CannerReactionOutput ? ((CannerReactionOutput) output).items() : null;
    }

    /**
     * {@code TileEntityCanner#operateOnce}：做完一次反应配方时交付，返回 true；不是反应配方时返回 false（照 IC2 原来的走）。
     * 照 {@code TileEntityStandardMachine#operateOnce}：先扣输入、再放物品（{@code processResult} 已经过升级的
     * {@code onProcessEnd}），最后把流体装进输出罐。
     */
    public static boolean operateOnce(TileEntityCanner canner, MachineRecipeResult<Object, Object, Object> result,
                                      Collection<ItemStack> processResult) {
        if (!(result.getOutput() instanceof CannerReactionOutput)) {
            return false;
        }
        canner.inputSlot.consume(result);
        canner.outputSlot.add(processResult == null ? Collections.<ItemStack>emptyList() : processResult);
        FluidStack fluid = ((CannerReactionOutput) result.getOutput()).fluid();
        if (fluid != null) {
            canner.outputTank.fill(fluid, true);
        }
        return true;
    }

    /** 富集模式下输入物品槽收不收这个物品（IC2 自己的富集配方不收时再问反应配方表） */
    public static boolean accepts(TileEntityCanner canner, ItemStack stack) {
        if (canner.getMode() != TileEntityCanner.Mode.EnrichLiquid || StackUtil.isEmpty(stack)) {
            return false;
        }
        FluidStack tank = canner.inputTank.getFluid();
        String fluid = tank == null || tank.amount <= 0 ? null : tank.getFluid().getName();
        return CannerReactions.TABLE.accepts(fluid, stack);
    }
}
