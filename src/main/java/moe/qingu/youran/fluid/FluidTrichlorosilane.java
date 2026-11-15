package moe.qingu.youran.fluid;

import net.minecraft.util.ResourceLocation;
import net.minecraftforge.fluids.Fluid;
import net.minecraftforge.fluids.FluidStack;

/**
 * 带纯度的流体：三氯氢硅（SiHCl₃，芯片线“硅”的中间品）、三氯氢硅氢气混合气。纯度记在 NBT 里（{@link FluidPurity}）。
 * 没有流体方块，只有通用桶。
 *
 * <p>名字后面带上纯度，如“三氯氢硅（纯度 99.9%，3 个 9）”：各模组的罐子界面、通用桶、JEI 显示流体名字时都问
 * {@link #getLocalizedName(FluidStack)}，这样不用一个个模组去改悬停说明。没有纯度标记的显示 1 个 9（90%）。
 */
public class FluidTrichlorosilane extends Fluid {
    public FluidTrichlorosilane(String name, ResourceLocation still, ResourceLocation flowing) {
        super(name, still, flowing);
    }

    @Override
    public String getLocalizedName(FluidStack stack) {
        return FluidPurity.nameWithPurity(super.getLocalizedName(stack), stack);
    }
}
