package moe.qingu.youran.compat.ic2;

import ic2.core.ref.FluidName;
import moe.qingu.youran.fluid.FluidPurity;
import moe.qingu.youran.logic.purity.Purity;
import moe.qingu.youran.registry.ModFluids;
import net.minecraftforge.fluids.Fluid;
import net.minecraftforge.fluids.FluidStack;
import net.minecraftforge.fluids.FluidTank;

import javax.annotation.Nullable;
import java.util.Arrays;

/**
 * IC2 太阳能蒸馏机提纯三氯氢硅（芯片线“硅”第 4 步，服主 11-15），由 {@code mixin.ic2.TileEntitySolarDestillerMixin} 调用。
 * 只有装了 IC2 时才会被加载。
 *
 * <p>照蒸馏机原来的规则：每个周期（普通群系 72 tick、炎热 36、寒冷 144）在看得见天空（亮度 > 0.5）时处理 1 mB 输入。
 * 三氯氢硅每份要 {@value Purity#BATCH_INPUT} mB，所以攒够 {@value Purity#BATCH_INPUT} 个周期做一次：
 * 抽 {@value Purity#BATCH_INPUT} mB、出 {@value Purity#BATCH_OUTPUT} mB 多一个 9 的三氯氢硅。罐里不到一份时也照样攒周期，
 * 攒满时有多少蒸多少（出九成，往下取整，见 {@link Purity#outputFor}），余量不会卡在罐里挡住别的纯度。
 * 原来的水 → 蒸馏水不变；只是输出罐里装着三氯氢硅时不蒸水（不然水被抽掉了、蒸馏水却装不进去）。
 */
public final class SolarDestillerHooks {
    /** 蒸馏机原来的规则：天空亮度要大于这个数才工作 */
    static final float MIN_SKY_LIGHT = 0.5F;

    private SolarDestillerHooks() {
    }

    /** 输入罐、输入槽收的流体：原来的（水）再加上三氯氢硅 */
    public static Fluid[] inputFluids(Fluid[] original) {
        if (ModFluids.trichlorosilane == null || Arrays.asList(original).contains(ModFluids.trichlorosilane)) {
            return original;
        }
        Fluid[] fluids = Arrays.copyOf(original, original.length + 1);
        fluids[original.length] = ModFluids.trichlorosilane;
        return fluids;
    }

    /** 输入罐里是不是三氯氢硅（这时由 {@link #tickPurify} 处理，不走原来的水 → 蒸馏水） */
    public static boolean isPurifying(FluidTank input) {
        return FluidPurity.isTrichlorosilane(input.getFluid());
    }

    /**
     * {@code canWork()}（服务端每个周期、客户端界面画“工作中”时都问）：三氯氢硅由这里回答；
     * 装的是水但输出罐里是别的流体时回答 false；别的情况返回 null，照原来的。
     */
    @Nullable
    public static Boolean canWork(FluidTank input, FluidTank output, float skyLight) {
        if (isPurifying(input)) {
            return canPurify(input, output, skyLight);
        }
        FluidStack out = output.getFluid();
        if (out != null && out.amount > 0 && out.getFluid() != FluidName.distilled_water.getInstance()) {
            return false;
        }
        return null;
    }

    /** 这个周期能不能提纯：罐里有三氯氢硅、看得见天空、输出罐装得下这一份的产物（纯度不同的不能混在一个罐里） */
    static boolean canPurify(FluidTank input, FluidTank output, float skyLight) {
        FluidStack in = input.getFluid();
        if (!FluidPurity.isTrichlorosilane(in) || in.amount <= 0 || skyLight <= MIN_SKY_LIGHT) {
            return false;
        }
        int out = Purity.outputFor(Math.min(in.amount, Purity.BATCH_INPUT));
        if (out <= 0) {
            return true;
        }
        FluidStack product = FluidPurity.distilled(in, out);
        return output.fillInternal(product, false) == product.amount;
    }

    /**
     * 每个周期调用一次（输入罐里是三氯氢硅时）。能工作就把进度加 1，攒满 {@value Purity#BATCH_INPUT} 个周期就抽一份
     * （最多 {@value Purity#BATCH_INPUT} mB）、出九成；返回新的进度。不能工作时进度停着不清零。
     */
    public static int tickPurify(FluidTank input, FluidTank output, float skyLight, int progress) {
        if (!canPurify(input, output, skyLight)) {
            return progress;
        }
        if (progress + 1 < Purity.BATCH_INPUT) {
            return progress + 1;
        }
        FluidStack drained = input.drainInternal(Purity.BATCH_INPUT, true);
        int out = drained == null ? 0 : Purity.outputFor(drained.amount);
        if (out > 0) {
            output.fillInternal(FluidPurity.distilled(drained, out), true);
        }
        return 0;
    }
}
