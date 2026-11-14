package moe.qingu.youran.compat.immersiveengineering;

import blusunrize.immersiveengineering.api.crafting.BottlingMachineRecipe;
import moe.qingu.youran.YouranMod;
import moe.qingu.youran.registry.ModItems;
import net.minecraft.item.ItemStack;
import net.minecraftforge.fluids.FluidRegistry;
import net.minecraftforge.fluids.FluidStack;

/** 加进沉浸工程机器的配方。只有装了沉浸工程（和匠魂）时才会被加载（{@code YouranMod#postInit} 先查） */
public final class IeRecipes {
    /** 匠魂的熔融钴 */
    static final String MOLTEN_COBALT = "cobalt";

    private IeRecipes() {
    }

    /** 灌装机：1 磁粉 + 10 mB 熔融钴 → 1 钴改性磁粉（同装罐机富集模式的那条） */
    public static void register() {
        FluidStack cobalt = FluidRegistry.getFluidStack(MOLTEN_COBALT, 10);
        if (cobalt == null) {
            YouranMod.LOG.error("没有流体 {}，不加沉浸工程灌装机的钴改性磁粉配方", MOLTEN_COBALT);
            return;
        }
        ItemStack input = new ItemStack(ModItems.magnetiteDust);
        BottlingMachineRecipe.addRecipe(new ItemStack(ModItems.cobaltMagneticDust), input, cobalt);
        // 用灌装机自己找配方的办法核对一遍（同一种输入可能已有别的配方排在前面）
        BottlingMachineRecipe found = BottlingMachineRecipe.findRecipe(input, cobalt);
        if (found == null || found.output.getItem() != ModItems.cobaltMagneticDust) {
            YouranMod.LOG.error("沉浸工程灌装机的钴改性磁粉配方没生效：同样的输入找到的是 {}", found == null ? null : found.output);
        } else {
            YouranMod.LOG.info("已加沉浸工程灌装机配方：磁粉 + 10 mB {} → 钴改性磁粉", MOLTEN_COBALT);
        }
    }
}
