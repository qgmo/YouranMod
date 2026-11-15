package moe.qingu.youran.mixin.immersiveengineering;

import blusunrize.immersiveengineering.api.crafting.MixerRecipe;
import blusunrize.immersiveengineering.common.blocks.metal.TileEntityMixer;
import blusunrize.immersiveengineering.common.blocks.metal.TileEntityMultiblockMetal;
import blusunrize.immersiveengineering.common.util.Utils;
import blusunrize.immersiveengineering.common.util.inventory.MultiFluidTank;
import moe.qingu.youran.compat.immersiveengineering.MixerRatioHooks;
import moe.qingu.youran.compat.immersiveengineering.RatioMixerProcess;
import net.minecraftforge.fluids.FluidStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * 搅拌机的处理过程支持输入、输出不一样多的配方（{@code compat.immersiveengineering.RatioMixerRecipe}）。
 * {@code doProcessTick} 每步先按 {@code Utils.copyFluidStackWithAmount(输入, n)} 从罐里抽 n mB，
 * 再按 {@code Utils.copyFluidStackWithAmount(输出, 抽到的量)} 把同样多的输出装回罐里：
 * 比例配方抽的乘上比例、出的按累计除回来，出 0 mB 的那步不往罐里装（不留下 0 mB 的空条目）。别的配方照原来的走。
 *
 * 另外加上标记接口 {@link RatioMixerProcess}，加配方前据此确认 Mixin 生效了。
 *
 * <p>累计抽了多少记在这个处理过程上，不存档：读档后从 0 重新算，最多少出 1 mB。
 */
@Mixin(value = TileEntityMixer.MultiblockProcessMixer.class, remap = false)
public abstract class MultiblockProcessMixerMixin implements RatioMixerProcess {
    @Unique
    private int youran$drained;

    @Unique
    private MixerRecipe youran$recipe() {
        return (MixerRecipe) ((TileEntityMultiblockMetal.MultiblockProcess<?>) (Object) this).recipe;
    }

    @Redirect(method = "doProcessTick", at = @At(value = "INVOKE", ordinal = 0,
            target = "Lblusunrize/immersiveengineering/common/util/Utils;copyFluidStackWithAmount(Lnet/minecraftforge/fluids/FluidStack;IZ)Lnet/minecraftforge/fluids/FluidStack;"))
    private FluidStack youran$scaleDrain(FluidStack input, int amount, boolean stripPressure) {
        return Utils.copyFluidStackWithAmount(input, MixerRatioHooks.drainAmount(youran$recipe(), amount), stripPressure);
    }

    @Redirect(method = "doProcessTick", at = @At(value = "INVOKE", ordinal = 1,
            target = "Lblusunrize/immersiveengineering/common/util/Utils;copyFluidStackWithAmount(Lnet/minecraftforge/fluids/FluidStack;IZ)Lnet/minecraftforge/fluids/FluidStack;"))
    private FluidStack youran$scaleOutput(FluidStack output, int drained, boolean stripPressure) {
        int amount = MixerRatioHooks.outputAmount(youran$recipe(), youran$drained, drained);
        youran$drained += drained;
        return Utils.copyFluidStackWithAmount(output, amount, stripPressure);
    }

    @Redirect(method = "doProcessTick", at = @At(value = "INVOKE",
            target = "Lblusunrize/immersiveengineering/common/util/inventory/MultiFluidTank;fill(Lnet/minecraftforge/fluids/FluidStack;Z)I"))
    private int youran$skipEmptyFill(MultiFluidTank tank, FluidStack stack, boolean doFill) {
        return stack == null || stack.amount <= 0 ? 0 : tank.fill(stack, doFill);
    }
}
