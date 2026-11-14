package moe.qingu.youran.logic.reaction;

import java.util.Collections;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Predicate;

/**
 * “流体 + 物品 → 流体 + 物品”的反应配方表（纯逻辑，不依赖 Minecraft）。按加入的先后找，先加的优先。
 *
 * <p>规则：
 * <ul>
 *     <li>输入流体种类与输入物品种类都对得上的第一条、且量都够的，就是要做的反应；</li>
 *     <li>种类对得上但量都不够的，算“不够”，机器不开始，也不交给机器原来的配方（免得原来的配方把这些输入用掉）；</li>
 *     <li>要做的反应输出放不下时机器不开始（做到一半放不下时暂停），也不交给原来的配方。</li>
 * </ul>
 *
 * @param <S> 物品的类型
 * @param <O> 输出物品的类型
 */
public final class ReactionTable<S, O> {
    private final StackFacts<S> facts;
    /** 机器每 tick 读，CraftTweaker 脚本在加载时改：读多写少 */
    private final List<FluidItemReaction<S, O>> reactions = new CopyOnWriteArrayList<>();

    public ReactionTable(StackFacts<S> facts) {
        this.facts = facts;
    }

    public StackFacts<S> facts() {
        return facts;
    }

    public void add(FluidItemReaction<S, O> reaction) {
        reactions.add(reaction);
    }

    /** 删掉满足条件的反应，返回删了几条 */
    public int removeIf(Predicate<FluidItemReaction<S, O>> filter) {
        int before = reactions.size();
        reactions.removeIf(filter);
        return before - reactions.size();
    }

    /** 删掉全部，返回删了几条 */
    public int clear() {
        int n = reactions.size();
        reactions.clear();
        return n;
    }

    /** 当前全部反应（只读） */
    public List<FluidItemReaction<S, O>> reactions() {
        return Collections.unmodifiableList(reactions);
    }

    /**
     * 这一 tick 怎么做。
     *
     * @param fluid       输入罐里的流体注册名；罐空时为 null
     * @param fluidAmount 输入罐里的量
     * @param stack       输入物品槽里的物品
     * @param outputsFit  这条反应的输出放不放得下（调用方按机器自己的规则判断）
     */
    public ReactionPlan<S, O> plan(String fluid, int fluidAmount, S stack, Predicate<FluidItemReaction<S, O>> outputsFit) {
        if (fluid == null || stack == null || facts.isEmpty(stack)) {
            return new ReactionPlan<>(ReactionPlan.Status.NO_MATCH, null);
        }
        FluidItemReaction<S, O> typeMatch = null;
        for (FluidItemReaction<S, O> r : reactions) {
            if (r.matches(fluid, fluidAmount, stack, facts)) {
                return new ReactionPlan<>(outputsFit.test(r) ? ReactionPlan.Status.READY : ReactionPlan.Status.OUTPUT_BLOCKED, r);
            }
            if (typeMatch == null && r.matchesType(fluid, stack, facts)) {
                typeMatch = r;
            }
        }
        if (typeMatch != null) {
            return new ReactionPlan<>(ReactionPlan.Status.NOT_ENOUGH, typeMatch);
        }
        return new ReactionPlan<>(ReactionPlan.Status.NO_MATCH, null);
    }

    /**
     * 输入物品槽收不收这个物品：有反应的输入物品是这种物品，且输入罐空着或装的正是那条反应的输入流体（不看数量，同 IC2 富集模式的规则）。
     *
     * @param fluid 输入罐里的流体注册名；罐空时为 null
     */
    public boolean accepts(String fluid, S stack) {
        if (stack == null || facts.isEmpty(stack)) {
            return false;
        }
        for (FluidItemReaction<S, O> r : reactions) {
            if ((fluid == null || r.inputFluid().equals(fluid)) && r.inputItem().matchesType(stack, facts)) {
                return true;
            }
        }
        return false;
    }
}
