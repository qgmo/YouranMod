package moe.qingu.youran.compat.immersiveengineering;

import blusunrize.immersiveengineering.api.crafting.BottlingMachineRecipe;
import blusunrize.immersiveengineering.api.crafting.RefineryRecipe;
import moe.qingu.youran.registry.ModFluids;
import moe.qingu.youran.YouranMod;
import moe.qingu.youran.compat.alchemistry.AlchemistryCompat;
import moe.qingu.youran.registry.ModItems;
import net.minecraft.item.ItemStack;
import net.minecraftforge.fluids.FluidRegistry;
import net.minecraftforge.fluids.FluidStack;

/** 加进沉浸工程机器的配方。只有装了沉浸工程时才会被加载（{@code recipe.CompatRecipes} 先查） */
public final class IeRecipes {
    /** 匠魂的熔融钴 */
    static final String MOLTEN_COBALT = "cobalt";

    private IeRecipes() {
    }

    /** 乙醇（沉浸工程）、氧气（IC2） */
    static final String ETHANOL = "ethanol";
    static final String OXYGEN = "ic2oxygen";

    /**
     * 炼油厂：500 mB 乙醇 + 325 mB 氧气 → 500 mB 乙酸（服主 11-14 定的数）。炼油厂每 tick 做一次配方，这个量就是每 tick 的量；
     * 每次的电耗 80 同沉浸工程自己的生物柴油配方。要装了 IC2（氧气）。
     */
    public static void registerRefinery() {
        FluidStack ethanol = FluidRegistry.getFluidStack(ETHANOL, 500);
        FluidStack oxygen = FluidRegistry.getFluidStack(OXYGEN, 325);
        if (ethanol == null || oxygen == null) {
            YouranMod.LOG.info("没有流体 {} 或 {}，不加沉浸工程炼油厂的乙酸配方", ETHANOL, OXYGEN);
            return;
        }
        RefineryRecipe.addRecipe(new FluidStack(ModFluids.aceticAcid, 500), ethanol, oxygen, 80);
        RefineryRecipe found = RefineryRecipe.findRecipe(ethanol, oxygen);
        if (found == null || found.output.getFluid() != ModFluids.aceticAcid) {
            YouranMod.LOG.error("沉浸工程炼油厂的乙酸配方没生效：同样的输入找到的是 {}", found == null ? null : found.output);
        } else {
            YouranMod.LOG.info("已加沉浸工程炼油厂配方：500 mB {} + 325 mB {} → 500 mB 乙酸", ETHANOL, OXYGEN);
        }
    }

    /** 灌装机：1 磁粉 + 10 mB 熔融钴 → 1 钴改性磁粉（同装罐机富集模式的那条） */
    public static void registerCobaltMagnetic() {
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

    /**
     * 灌装机：1 纤维素 + 188 mB 乙酸 → 1 醋酸纤维素（装了炼金化学时）。装罐机里是 375 mB + 2 → 2，
     * 但灌装机一次只放一个物品进去，按一个算是 187.5 mB，取整为 188（不比装罐机便宜）。
     */
    public static void registerCelluloseAcetate() {
        ItemStack cellulose = AlchemistryCompat.stack(AlchemistryCompat.CELLULOSE, 1);
        ItemStack acetate = AlchemistryCompat.stack(AlchemistryCompat.CELLULOSE_ACETATE, 1);
        if (cellulose.isEmpty() || acetate.isEmpty()) {
            YouranMod.LOG.info("炼金化学里没有纤维素或醋酸纤维素，不加沉浸工程灌装机的醋酸纤维素配方");
            return;
        }
        FluidStack acid = new FluidStack(ModFluids.aceticAcid, 188);
        BottlingMachineRecipe.addRecipe(acetate, cellulose, acid);
        BottlingMachineRecipe found = BottlingMachineRecipe.findRecipe(cellulose, acid);
        if (found == null || !ItemStack.areItemsEqual(found.output, acetate)) {
            YouranMod.LOG.error("沉浸工程灌装机的醋酸纤维素配方没生效：同样的输入找到的是 {}", found == null ? null : found.output);
        } else {
            YouranMod.LOG.info("已加沉浸工程灌装机配方：纤维素 + 188 mB 乙酸 → 醋酸纤维素");
        }
    }
}
