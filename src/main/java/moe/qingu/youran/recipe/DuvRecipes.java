package moe.qingu.youran.recipe;

import moe.qingu.youran.YouranMod;
import moe.qingu.youran.fluid.FluidVariants;
import moe.qingu.youran.logic.litho.DuvNumbers;
import moe.qingu.youran.logic.litho.ResistType;
import net.minecraftforge.fluids.FluidRegistry;
import net.minecraftforge.fluids.FluidStack;

/**
 * DUV 一档的机器配方里不归光源蓝图、装罐机钩子管的部分（{@link LithoRecipes#postInit} 调用）：
 * <ul>
 *     <li>改良正胶（服主 11-16）：1000 mB 正胶 + 10 mB IC2 UU 物质 → 1000 mB 改良正胶。沉浸工程这边用<b>炼油厂</b>（两种流体进、一种出）：
 *     混合器只收一种流体，罐里同时有两种时它会把底下那种往外排；IC2 装罐机那条见 {@code compat.ic2.UuDosingHooks}。</li>
 * </ul>
 */
public final class DuvRecipes {
    private DuvRecipes() {
    }

    static void registerIe() {
        FluidStack uu = FluidRegistry.getFluidStack("ic2uu_matter" /* IC2 的 UU 物质 */, DuvNumbers.IMPROVED_UU_MB);
        if (uu == null || moe.qingu.youran.registry.ModFluids.photoresist == null) {
            YouranMod.LOG.info("没有 IC2 的 UU 物质，不加沉浸工程炼油厂的改良正胶配方");
            return;
        }
        blusunrize.immersiveengineering.api.crafting.RefineryRecipe.addRecipe(
                FluidVariants.photoresist(ResistType.IMPROVED_POSITIVE, DuvNumbers.IMPROVED_RESIST_MB),
                FluidVariants.photoresist(ResistType.POSITIVE, DuvNumbers.IMPROVED_RESIST_MB), uu, DuvNumbers.IMPROVED_REFINERY_ENERGY);
        YouranMod.LOG.info("已加沉浸工程炼油厂配方：{} mB 正胶 + {} mB UU 物质 → 改良正胶", DuvNumbers.IMPROVED_RESIST_MB, DuvNumbers.IMPROVED_UU_MB);
    }
}
