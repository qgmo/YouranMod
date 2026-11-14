package moe.qingu.youran.logic.reaction;

import java.util.Objects;

/**
 * 一条“流体 + 物品 → 流体 + 物品”的反应。输入两样都要有；输出至少要有一样（只出流体或只出物品也行）。
 * 流体按 Forge 流体注册名比较（不看流体的 NBT），量的单位是 mB。
 *
 * @param <S> 物品的类型
 * @param <O> 输出物品的类型（调用方自己定，配方表不看它的内容）
 */
public final class FluidItemReaction<S, O> {
    private final String inputFluid;
    private final int inputAmount;
    private final ItemIngredient<S> inputItem;
    private final String outputFluid;
    private final int outputAmount;
    private final O outputItem;

    /**
     * @param outputFluid  输出流体注册名；不出流体时为 null（这时 outputAmount 不看）
     * @param outputItem   输出物品；不出物品时为 null
     */
    public FluidItemReaction(String inputFluid, int inputAmount, ItemIngredient<S> inputItem,
                             String outputFluid, int outputAmount, O outputItem) {
        if (inputFluid == null || inputFluid.isEmpty()) {
            throw new IllegalArgumentException("输入流体不能为空");
        }
        if (inputAmount <= 0) {
            throw new IllegalArgumentException("输入流体的量要大于 0：" + inputAmount);
        }
        Objects.requireNonNull(inputItem, "输入物品不能为空");
        if (outputFluid != null && outputFluid.isEmpty()) {
            outputFluid = null;
        }
        if (outputFluid != null && outputAmount <= 0) {
            throw new IllegalArgumentException("输出流体的量要大于 0：" + outputAmount);
        }
        if (outputFluid == null && outputItem == null) {
            throw new IllegalArgumentException("输出流体与输出物品至少要有一样");
        }
        this.inputFluid = inputFluid;
        this.inputAmount = inputAmount;
        this.inputItem = inputItem;
        this.outputFluid = outputFluid;
        this.outputAmount = outputFluid == null ? 0 : outputAmount;
        this.outputItem = outputItem;
    }

    public String inputFluid() {
        return inputFluid;
    }

    public int inputAmount() {
        return inputAmount;
    }

    public ItemIngredient<S> inputItem() {
        return inputItem;
    }

    /** 不出流体时为 null */
    public String outputFluid() {
        return outputFluid;
    }

    /** 不出流体时为 0 */
    public int outputAmount() {
        return outputAmount;
    }

    /** 不出物品时为 null */
    public O outputItem() {
        return outputItem;
    }

    /** 流体种类与物品种类都对得上（不看量） */
    boolean matchesType(String fluid, S stack, StackFacts<S> facts) {
        return inputFluid.equals(fluid) && inputItem.matchesType(stack, facts);
    }

    /** 种类对得上，且流体、物品的量都够一次 */
    boolean matches(String fluid, int fluidAmount, S stack, StackFacts<S> facts) {
        return inputFluid.equals(fluid) && fluidAmount >= inputAmount && inputItem.matches(stack, facts);
    }

    @Override
    public String toString() {
        return "<liquid:" + inputFluid + "> * " + inputAmount + " + " + inputItem + " -> "
                + (outputFluid == null ? "" : "<liquid:" + outputFluid + "> * " + outputAmount)
                + (outputFluid != null && outputItem != null ? " + " : "")
                + (outputItem == null ? "" : String.valueOf(outputItem));
    }
}
