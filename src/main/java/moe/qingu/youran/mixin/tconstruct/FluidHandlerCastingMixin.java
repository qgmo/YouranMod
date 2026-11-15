package moe.qingu.youran.mixin.tconstruct;

import net.minecraftforge.fluids.FluidStack;
import net.minecraftforge.fluids.FluidTank;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import slimeknights.tconstruct.library.fluid.FluidHandlerCasting;
import slimeknights.tconstruct.library.fluid.FluidTankAnimated;
import slimeknights.tconstruct.smeltery.tileentity.TileCasting;

/**
 * 匠魂浇铸台、浇铸盆收带 NBT 的流体（芯片线第 8 步：混合气带纯度）。
 *
 * <p>匠魂开始一次浇注时把罐里的流体设成 {@code new FluidStack(流体, 0)}（不带 NBT），再往里灌；
 * Forge 的罐子按 NBT 比流体，带 NBT 的就一滴也灌不进去（试灌时也一样）。这里只管带 NBT 的流体：
 * 照原来的步骤走，只是起始的空流体带上要灌进来的流体的 NBT。不带 NBT 的流体照原来的走。
 */
@Mixin(value = FluidHandlerCasting.class, remap = false)
public abstract class FluidHandlerCastingMixin {
    @Shadow
    @Final
    private FluidTankAnimated tank;
    @Shadow
    @Final
    private TileCasting tileCasting;

    @Inject(method = "fill", at = @At("HEAD"), cancellable = true)
    private void youran$fillWithTag(FluidStack resource, boolean doFill, CallbackInfoReturnable<Integer> cir) {
        if (resource == null || resource.tag == null || tileCasting.isStackInSlot(1) || tank.getFluidAmount() != 0) {
            return;
        }
        int capacity = tileCasting.initNewCasting(resource.getFluid(), doFill);
        if (capacity <= 0) {
            return;
        }
        FluidStack start = new FluidStack(resource, 0);
        if (doFill) {
            tank.setCapacity(capacity);
            tank.setFluid(start);
            cir.setReturnValue(tank.fill(resource, true));
        } else {
            cir.setReturnValue(new FluidTank(start, capacity).fill(resource, false));
        }
    }
}
