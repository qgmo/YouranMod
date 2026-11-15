package moe.qingu.youran.compat.immersiveengineering;

import blusunrize.immersiveengineering.api.crafting.MixerRecipe;
import net.minecraftforge.fluids.FluidStack;

/**
 * 输入、输出流体不一样多的搅拌机配方（如 3000 mB 盐酸 + 1 硅粉 → 1000 mB 三氯氢硅）。
 *
 * <p>沉浸工程的搅拌机本来按 1:1 换流体：每个配方一共抽 {@code fluidAmount}（= 输出的量）mB 输入，抽多少就出多少，
 * 处理时间是输出的量（tick）。这里照它的时间与电耗规则（输出 1000 mB 就是 1000 tick），
 * 由 {@code mixin.immersiveengineering.MultiblockProcessMixerMixin} 把每次抽的量乘上 {@link #inputPerOutput}、
 * 出的量按累计除回来（{@code logic.reaction.FluidRatio}）。JEI 里显示的是真的输入、输出量。
 */
public class RatioMixerRecipe extends MixerRecipe {
    /** 每出 1 mB 要几 mB 输入 */
    public final int inputPerOutput;

    /**
     * @param fluidOutput 一次做出的流体（量就是一次的输出）
     * @param fluidInput  一次要的流体（量必须是输出的整数倍）
     */
    public RatioMixerRecipe(FluidStack fluidOutput, FluidStack fluidInput, Object[] itemInputs, int energy) {
        super(fluidOutput, fluidInput, itemInputs, energy);
        if (fluidOutput.amount <= 0 || fluidInput.amount < fluidOutput.amount || fluidInput.amount % fluidOutput.amount != 0) {
            throw new IllegalArgumentException("输入的量要是输出的整数倍：" + fluidInput.amount + " / " + fluidOutput.amount);
        }
        this.inputPerOutput = fluidInput.amount / fluidOutput.amount;
    }
}
