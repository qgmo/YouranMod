package moe.qingu.youran.fluid;

import net.minecraft.util.ResourceLocation;
import net.minecraftforge.fluids.Fluid;
import net.minecraftforge.fluids.FluidStack;

/**
 * 芳烃（服主 11-15，沉浸原油精馏塔精炼杂酚油得到）。带叠氮基的芳烃不另注册流体，加 NBT 标记（{@link FluidVariants}），
 * 名字换成“带叠氮基的芳烃”。各模组的罐子界面、通用桶、JEI 都问 {@link #getLocalizedName(FluidStack)}。
 */
public class FluidArene extends Fluid {
    public FluidArene(String name, ResourceLocation still, ResourceLocation flowing) {
        super(name, still, flowing);
    }

    @Override
    public String getUnlocalizedName(FluidStack stack) {
        return FluidVariants.isAzideArene(stack) ? "fluid.arene_azide" : super.getUnlocalizedName(stack);
    }

    @SuppressWarnings("deprecation")
    @Override
    public String getLocalizedName(FluidStack stack) {
        return net.minecraft.util.text.translation.I18n.translateToLocal(getUnlocalizedName(stack));
    }
}
