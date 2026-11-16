package moe.qingu.youran.litho;

import net.minecraftforge.fluids.FluidStack;
import net.minecraftforge.fluids.FluidTank;

import java.util.function.Predicate;

/** 光刻机的一个罐：只收认得的流体（按 NBT 区分，同一个罐里只装一种，换光刻胶种类要先倒空） */
public class LithoTank extends FluidTank {
    private final Predicate<FluidStack> accepts;

    public LithoTank(int capacity, Predicate<FluidStack> accepts) {
        super(capacity);
        this.accepts = accepts;
    }

    @Override
    public boolean canFillFluidType(FluidStack fluid) {
        return fluid != null && accepts.test(fluid);
    }
}
