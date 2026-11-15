package moe.qingu.youran.compat.immersiveengineering;

import blusunrize.immersiveengineering.api.crafting.RefineryRecipe;
import net.minecraftforge.fluids.FluidStack;

/**
 * 炼油厂的混合气配方（芯片线第 6 步）。炼油厂按流体连 NBT 一起比配方，产物也是配方里写死的，
 * 所以每种纯度各登记一条（{@code logic.purity.WaferRules#mixtureGrades}），产物带同样的纯度；
 * JEI 里只列 9 个 9 的那条（{@link #listInJEI}），免得 24 条一样的配方挤满界面。
 */
public class PurityRefineryRecipe extends RefineryRecipe {
    private final boolean listed;

    public PurityRefineryRecipe(FluidStack output, FluidStack input0, FluidStack input1, int energy, boolean listed) {
        super(output, input0, input1, energy);
        this.listed = listed;
    }

    @Override
    public boolean listInJEI() {
        return listed;
    }
}
