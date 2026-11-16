package moe.qingu.youran.compat.immersiveengineering;

import blusunrize.immersiveengineering.api.crafting.MixerRecipe;
import net.minecraftforge.fluids.FluidStack;

/**
 * 输入、输出流体不一样多的搅拌机配方（如 3000 mB 盐酸 + 1 硅粉 → 1000 mB 三氯氢硅；1000 mB 氯气 + 4 小撮锡粉 → 64 mB 四氯化锡）。
 *
 * <p>沉浸工程的搅拌机本来按 1:1 换流体：每个配方一共抽 {@code fluidAmount}（= 输出的量）mB 输入，抽多少就出多少，
 * 处理时间是输出的量（tick）。这里照它的时间与电耗规则（输出 1000 mB 就是 1000 tick），
 * 由 {@code mixin.immersiveengineering.MultiblockProcessMixerMixin} 把每步抽的量按比例放大、出的量按累计换回来
 * （{@code logic.reaction.FluidRatio}）。JEI 里显示的是真的输入、输出量。
 */
public class RatioMixerRecipe extends MixerRecipe {
    /** 一次的输入、输出量（mB） */
    public final int inputAmount;
    public final int outputAmount;

    /**
     * @param fluidOutput 一次做出的流体（量就是一次的输出）
     * @param fluidInput  一次要的流体（量不少于输出）
     */
    public RatioMixerRecipe(FluidStack fluidOutput, FluidStack fluidInput, Object[] itemInputs, int energy) {
        super(fluidOutput, fluidInput, itemInputs, energy);
        if (fluidOutput.amount <= 0 || fluidInput.amount < fluidOutput.amount) {
            throw new IllegalArgumentException("输入的量不能比输出少：" + fluidInput.amount + " / " + fluidOutput.amount);
        }
        this.inputAmount = fluidInput.amount;
        this.outputAmount = fluidOutput.amount;
    }
}
