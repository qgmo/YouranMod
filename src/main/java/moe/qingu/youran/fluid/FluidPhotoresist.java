package moe.qingu.youran.fluid;

import moe.qingu.youran.logic.litho.ResistType;
import net.minecraft.util.ResourceLocation;
import net.minecraftforge.fluids.Fluid;
import net.minecraftforge.fluids.FluidStack;

/**
 * 光刻胶（服主 11-15）：负胶、正胶共用这一种流体，NBT 区分（{@link FluidVariants#resist}）。名字显示种类：“光刻胶（负胶）”。
 * 没有标记的显示“光刻胶”（不该出现，光刻机也不收）；变质的（{@code Exposed:1b}）不分种类，显示“变质的光刻胶”。
 */
public class FluidPhotoresist extends Fluid {
    public FluidPhotoresist(String name, ResourceLocation still, ResourceLocation flowing) {
        super(name, still, flowing);
    }

    @Override
    public String getUnlocalizedName(FluidStack stack) {
        ResistType type = FluidVariants.resist(stack);
        if (type == null) {
            return super.getUnlocalizedName(stack);
        }
        return FluidVariants.isExposedResist(stack) ? "fluid.photoresist_exposed" : "fluid.photoresist_" + type.id;
    }

    @SuppressWarnings("deprecation")
    @Override
    public String getLocalizedName(FluidStack stack) {
        return net.minecraft.util.text.translation.I18n.translateToLocal(getUnlocalizedName(stack));
    }
}
