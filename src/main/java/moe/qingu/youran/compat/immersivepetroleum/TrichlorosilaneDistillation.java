package moe.qingu.youran.compat.immersivepetroleum;

import flaxbeard.immersivepetroleum.api.crafting.DistillationRecipe;
import moe.qingu.youran.fluid.FluidPurity;
import moe.qingu.youran.logic.purity.Purity;
import net.minecraft.item.ItemStack;
import net.minecraftforge.fluids.FluidStack;

/**
 * 精馏塔提纯三氯氢硅的一份配方：{@value Purity#BATCH_INPUT} mB 某个纯度 → {@value Purity#BATCH_OUTPUT} mB 多一个 9（杂质降到十分之一）。
 * 每种纯度（准确说是每种 NBT）各一份，输入带着原来的 NBT，精馏塔扣输入时才扣得对（沉浸工程的多流体罐按 NBT 区分流体）。
 * 不进 {@code DistillationRecipe.recipeList}，由 {@code mixin.immersivepetroleum.DistillationRecipeMixin} 在找配方时给出。
 *
 * <p>罐里只剩不到一份、放了一会儿（{@link DistillationHooks#IDLE_TICKS} tick）也没有新的进来时，余量当一份蒸掉，
 * 免得卡在输入罐里挡住别的纯度（精馏塔的输入罐只收和罐里同一个配方的流体）。
 *
 * <p>时间、电耗照精馏塔原油配方的规则：一次 1 tick；电耗按输入的量折算原油的（75 mB 原油 2048 Flux），
 * 10 mB 记 {@value #ENERGY} Flux，再乘上沉浸原油配置里的电耗倍率（时间倍率同样照乘）。
 */
public class TrichlorosilaneDistillation extends DistillationRecipe {
    /** 一次的电耗（倍率前）：2048 × 10 / 75 ≈ 273 */
    public static final int ENERGY = 273;
    /** 一次的时间（倍率前），同原油 */
    public static final int TIME = 1;

    /**
     * @param input  输入的三氯氢硅（只看种类与 NBT）
     * @param amount 这一份的量：通常是 {@value Purity#BATCH_INPUT} mB；罐里只剩余量时是余量（出九成，往下取整）
     */
    public TrichlorosilaneDistillation(FluidStack input, int amount) {
        super(new FluidStack[]{FluidPurity.distilled(input, Purity.outputFor(amount))}, new ItemStack[0],
                new FluidStack(input, amount), ENERGY, TIME, new float[0]);
    }
}
