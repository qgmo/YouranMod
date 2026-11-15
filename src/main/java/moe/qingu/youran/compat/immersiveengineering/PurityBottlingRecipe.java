package moe.qingu.youran.compat.immersiveengineering;

import blusunrize.immersiveengineering.api.crafting.BottlingMachineRecipe;
import net.minecraft.item.ItemStack;
import net.minecraftforge.fluids.FluidStack;

/**
 * 灌装机的硅晶圆配方（芯片线第 8 步）。灌装机按流体连 NBT 一起比配方，产物也是配方里写死的，
 * 所以混合气的每种纯度各登记一条，产物带同样的纯度；JEI 里只列 9 个 9 的那条（{@link #listInJEI}）。
 */
public class PurityBottlingRecipe extends BottlingMachineRecipe {
    private final boolean listed;

    public PurityBottlingRecipe(ItemStack output, Object input, FluidStack fluidInput, boolean listed) {
        super(output, input, fluidInput);
        this.listed = listed;
    }

    @Override
    public boolean listInJEI() {
        return listed;
    }
}
