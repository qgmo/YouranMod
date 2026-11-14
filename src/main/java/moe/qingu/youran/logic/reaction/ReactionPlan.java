package moe.qingu.youran.logic.reaction;

/**
 * 机器这一 tick 对反应配方表的判断结果。
 *
 * @param <S> 物品的类型
 * @param <O> 输出物品的类型
 */
public final class ReactionPlan<S, O> {
    public enum Status {
        /** 配方表里没有这种流体 + 物品的反应：照机器原来的规则走 */
        NO_MATCH,
        /** 种类对得上，但流体或物品的量不够一次：不开始（不交给机器原来的配方） */
        NOT_ENOUGH,
        /** 输入够了，但输出放不下（物品输出槽满了或放着别的东西、流体输出罐装不下）：不开始 / 暂停 */
        OUTPUT_BLOCKED,
        /** 可以做 */
        READY
    }

    private final Status status;
    private final FluidItemReaction<S, O> reaction;

    ReactionPlan(Status status, FluidItemReaction<S, O> reaction) {
        this.status = status;
        this.reaction = reaction;
    }

    public Status status() {
        return status;
    }

    /** 对得上的反应；{@link Status#NO_MATCH} 时为 null */
    public FluidItemReaction<S, O> reaction() {
        return reaction;
    }

    /** 归不归反应配方表管（不是 {@link Status#NO_MATCH}） */
    public boolean handled() {
        return status != Status.NO_MATCH;
    }
}
