package moe.qingu.youran.fluid;

import moe.qingu.youran.logic.purity.Purity;
import moe.qingu.youran.registry.ModFluids;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraftforge.common.util.Constants;
import net.minecraftforge.fluids.Fluid;
import net.minecraftforge.fluids.FluidStack;

import javax.annotation.Nullable;

/**
 * 带纯度的流体（三氯氢硅、三氯氢硅氢气混合气）：纯度记在流体的 NBT（{@code FluidStack.tag}）里，
 * 存整数“几个 9”：{@code {Nines: 3}}（99.9%，见 {@link Purity}）。
 *
 * <p>没有这个标记的一律按 1 个 9（90%）算（服主 11-15：管道、罐子丢了 NBT，就当它们不干净、破坏了纯度）。
 * 反过来，纯度正好 1 个 9 的也不写标记（{@link #tagFor}）：装罐机、搅拌机做出来的和丢了标记的是同一种流体，
 * 能在同一个罐子里合并。只有提纯过的才带标记；Forge 按 NBT 区分流体，所以纯度不同的不会混到一个罐子里。
 */
public final class FluidPurity {
    /** NBT 里“几个 9”的键（整数） */
    public static final String TAG_NINES = "Nines";
    /** 纯度文字：百分数与个数（“纯度 99.9%，3 个 9”） */
    public static final String KEY_PURITY = "youranmod.purity";
    /** 纯度文字：9 太多时只写个数（“纯度 15 个 9”） */
    public static final String KEY_PURITY_LONG = "youranmod.purity.long";
    /** 带纯度的流体名字：参数是流体名与纯度文字 */
    public static final String KEY_FLUID_WITH_PURITY = "youranmod.fluid.with_purity";

    private FluidPurity() {
    }

    /** 是不是本模组的三氯氢硅 */
    public static boolean isTrichlorosilane(@Nullable FluidStack stack) {
        return stack != null && ModFluids.trichlorosilane != null && stack.getFluid() == ModFluids.trichlorosilane;
    }

    /** 是不是本模组的三氯氢硅氢气混合气 */
    public static boolean isMixture(@Nullable FluidStack stack) {
        return stack != null && ModFluids.trichlorosilaneHydrogen != null && stack.getFluid() == ModFluids.trichlorosilaneHydrogen;
    }

    /** {@code amount} mB 纯度为 {@code nines} 个 9 的三氯氢硅氢气混合气 */
    public static FluidStack mixture(int amount, int nines) {
        return withNines(ModFluids.trichlorosilaneHydrogen, amount, nines);
    }

    /** 标记里的个数；没有标记（或标记不是数字）时按 {@link Purity#DEFAULT_NINES} */
    public static int nines(@Nullable NBTTagCompound tag) {
        if (tag == null || !tag.hasKey(TAG_NINES, Constants.NBT.TAG_ANY_NUMERIC)) {
            return Purity.DEFAULT_NINES;
        }
        return Purity.sanitize(tag.getInteger(TAG_NINES));
    }

    /** 这份流体有几个 9（不看流体种类） */
    public static int nines(FluidStack stack) {
        return nines(stack.tag);
    }

    /** 记这个个数的标记；正好是默认的 1 个 9 时为 null（不写标记） */
    @Nullable
    public static NBTTagCompound tagFor(int nines) {
        int n = Purity.sanitize(nines);
        if (n == Purity.DEFAULT_NINES) {
            return null;
        }
        NBTTagCompound tag = new NBTTagCompound();
        tag.setInteger(TAG_NINES, n);
        return tag;
    }

    /** {@code amount} mB 纯度为 {@code nines} 个 9 的 {@code fluid} */
    public static FluidStack withNines(Fluid fluid, int amount, int nines) {
        return new FluidStack(fluid, amount, tagFor(nines));
    }

    /** {@code amount} mB 纯度为 {@code nines} 个 9 的三氯氢硅 */
    public static FluidStack trichlorosilane(int amount, int nines) {
        return withNines(ModFluids.trichlorosilane, amount, nines);
    }

    /** 把 {@code input} 蒸馏一遍得到的三氯氢硅，量为 {@code amount} mB */
    public static FluidStack distilled(FluidStack input, int amount) {
        return trichlorosilane(amount, Purity.distilled(nines(input)));
    }

    /** 纯度文字（按当前语言，两端都能用）：“纯度 99.9%，3 个 9”；9 太多时“纯度 15 个 9” */
    @SuppressWarnings("deprecation")
    public static String describe(int nines) {
        int n = Purity.sanitize(nines);
        if (Purity.showPercent(n)) {
            return net.minecraft.util.text.translation.I18n.translateToLocalFormatted(KEY_PURITY, Purity.percent(n), n);
        }
        return net.minecraft.util.text.translation.I18n.translateToLocalFormatted(KEY_PURITY_LONG, n);
    }

    /** 流体名字后面带上纯度：“三氯氢硅（纯度 99.9%，3 个 9）” */
    @SuppressWarnings("deprecation")
    public static String nameWithPurity(String name, @Nullable FluidStack stack) {
        int n = stack == null ? Purity.DEFAULT_NINES : nines(stack);
        return net.minecraft.util.text.translation.I18n.translateToLocalFormatted(KEY_FLUID_WITH_PURITY, name, describe(n));
    }
}
