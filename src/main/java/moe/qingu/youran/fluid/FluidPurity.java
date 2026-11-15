package moe.qingu.youran.fluid;

import moe.qingu.youran.logic.purity.Purity;
import moe.qingu.youran.registry.ModFluids;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraftforge.common.util.Constants;
import net.minecraftforge.fluids.FluidStack;

import javax.annotation.Nullable;

/**
 * 三氯氢硅的纯度记在流体的 NBT（{@code FluidStack.tag}）里：{@code {Impurity: 0.001d}}（杂质比例，见 {@link Purity}）。
 *
 * <p>没有这个标记的三氯氢硅一律按纯度 90% 算（服主 11-15：管道、罐子丢了 NBT，就当它们不干净、破坏了纯度）。
 * 反过来，纯度正好 90% 的三氯氢硅也不写标记（{@link #tagFor}）：装罐机、搅拌机做出来的和丢了标记的是同一种流体，
 * 能在同一个罐子里合并。只有提纯过的才带标记；Forge 按 NBT 区分流体，所以纯度不同的不会混到一个罐子里。
 */
public final class FluidPurity {
    /** NBT 里杂质比例的键（双精度） */
    public static final String TAG_IMPURITY = "Impurity";

    private FluidPurity() {
    }

    /** 是不是本模组的三氯氢硅 */
    public static boolean isTrichlorosilane(@Nullable FluidStack stack) {
        return stack != null && ModFluids.trichlorosilane != null && stack.getFluid() == ModFluids.trichlorosilane;
    }

    /** 标记里的杂质比例；没有标记（或标记不是数字）时按 {@link Purity#DEFAULT_IMPURITY} */
    public static double impurity(@Nullable NBTTagCompound tag) {
        if (tag == null || !tag.hasKey(TAG_IMPURITY, Constants.NBT.TAG_ANY_NUMERIC)) {
            return Purity.DEFAULT_IMPURITY;
        }
        return Purity.sanitize(tag.getDouble(TAG_IMPURITY));
    }

    /** 这份流体的杂质比例（不看流体种类） */
    public static double impurity(FluidStack stack) {
        return impurity(stack.tag);
    }

    /** 这份流体的纯度（0–1） */
    public static double purity(FluidStack stack) {
        return Purity.purity(impurity(stack));
    }

    /** 记这个杂质比例的标记；正好是默认的 90% 时为 null（不写标记） */
    @Nullable
    public static NBTTagCompound tagFor(double impurity) {
        double imp = Purity.sanitize(impurity);
        if (imp == Purity.DEFAULT_IMPURITY) {
            return null;
        }
        NBTTagCompound tag = new NBTTagCompound();
        tag.setDouble(TAG_IMPURITY, imp);
        return tag;
    }

    /** {@code amount} mB 杂质比例为 {@code impurity} 的三氯氢硅 */
    public static FluidStack trichlorosilane(int amount, double impurity) {
        return new FluidStack(ModFluids.trichlorosilane, amount, tagFor(impurity));
    }

    /** {@code amount} mB 纯度为 {@code purity}（0–1）的三氯氢硅 */
    public static FluidStack trichlorosilaneWithPurity(int amount, double purity) {
        return trichlorosilane(amount, Purity.impurityOf(purity));
    }

    /** 把 {@code input} 蒸馏一遍得到的三氯氢硅，量为 {@code amount} mB */
    public static FluidStack distilled(FluidStack input, int amount) {
        return trichlorosilane(amount, Purity.distilled(impurity(input)));
    }
}
