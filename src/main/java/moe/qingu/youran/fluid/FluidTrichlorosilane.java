package moe.qingu.youran.fluid;

import moe.qingu.youran.logic.purity.Purity;
import net.minecraft.util.ResourceLocation;
import net.minecraftforge.fluids.Fluid;
import net.minecraftforge.fluids.FluidStack;

/**
 * 三氯氢硅（SiHCl₃）：芯片线“硅”的中间品，纯度记在 NBT 里（{@link FluidPurity}）。没有流体方块，只有通用桶。
 *
 * <p>名字后面带上纯度，如“三氯氢硅（纯度 99.9%）”：各模组的罐子界面、通用桶、JEI 显示流体名字时都问
 * {@link #getLocalizedName(FluidStack)}，这样不用一个个模组去改悬停说明。没有纯度标记的显示 90%。
 */
public class FluidTrichlorosilane extends Fluid {
    /** 带纯度的名字：参数是流体名与纯度百分数（不带百分号） */
    public static final String KEY_WITH_PURITY = "fluid.trichlorosilane.with_purity";

    public FluidTrichlorosilane(String name, ResourceLocation still, ResourceLocation flowing) {
        super(name, still, flowing);
    }

    @SuppressWarnings("deprecation")
    @Override
    public String getLocalizedName(FluidStack stack) {
        String name = super.getLocalizedName(stack);
        return net.minecraft.util.text.translation.I18n.translateToLocalFormatted(KEY_WITH_PURITY, name,
                Purity.percent(stack == null ? Purity.DEFAULT_IMPURITY : FluidPurity.impurity(stack)));
    }
}
