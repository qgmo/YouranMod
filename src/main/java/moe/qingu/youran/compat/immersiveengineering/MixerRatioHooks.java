package moe.qingu.youran.compat.immersiveengineering;

import blusunrize.immersiveengineering.api.crafting.MixerRecipe;
import moe.qingu.youran.logic.reaction.FluidRatio;

/** 搅拌机里 {@link RatioMixerRecipe} 的换算，由 {@code mixin.immersiveengineering.MultiblockProcessMixerMixin} 调用 */
public final class MixerRatioHooks {
    private MixerRatioHooks() {
    }

    /** 这一步要抽的输入：本来按 1:1 抽 {@code amount} mB，比例配方乘上比例 */
    public static int drainAmount(MixerRecipe recipe, int amount) {
        return recipe instanceof RatioMixerRecipe ? amount * ((RatioMixerRecipe) recipe).inputPerOutput : amount;
    }

    /** 这一步该出的量：本来抽多少出多少（{@code drained}），比例配方按累计换算 */
    public static int outputAmount(MixerRecipe recipe, int drainedBefore, int drained) {
        return recipe instanceof RatioMixerRecipe
                ? FluidRatio.output(drainedBefore, drained, ((RatioMixerRecipe) recipe).inputPerOutput) : drained;
    }
}
