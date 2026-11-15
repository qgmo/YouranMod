package moe.qingu.youran.mixin.immersivepetroleum;

import blusunrize.immersiveengineering.common.util.Utils;
import blusunrize.immersiveengineering.common.util.inventory.MultiFluidTank;
import flaxbeard.immersivepetroleum.common.blocks.metal.TileEntityDistillationTower;
import moe.qingu.youran.compat.immersivepetroleum.DistillationHooks;
import net.minecraftforge.fluids.FluidStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 精馏塔把输出罐的流体往管道里推时保住 NBT。沉浸原油原来的写法：每种流体先复制一份（带 NBT）推出去，
 * 推出去多少再按 {@code new FluidStack(流体, 量)}（不带 NBT）从输出罐里扣；沉浸工程的多流体罐按 NBT 区分流体，
 * 带纯度标记的三氯氢硅就扣不掉，管道里多出一份、罐里一点没少（凭空复制）。
 *
 * <p>这里记下这一轮推的是罐里的哪一份流体（第一次 {@code Utils.copyFluidStackWithAmount} 的参数），扣的时候换成带它的 NBT 的。
 * 不带 NBT 的流体（原油的产物）扣法不变。
 *
 * <p>另外记下输入罐里三氯氢硅的余量（不到一份）放了多久：放满 {@link DistillationHooks#IDLE_TICKS} tick 没变时，
 * 这一 tick 找配方允许把余量当一份蒸掉（{@link DistillationHooks#lookup}），update 结束时设回原来的场合。
 * 计时不存档，读档后重新数。
 */
@Mixin(value = TileEntityDistillationTower.class, remap = false)
public abstract class TileEntityDistillationTowerMixin {
    @Shadow
    public MultiFluidTank[] tanks;

    @Unique
    private FluidStack youran$pushing;
    @Unique
    private int youran$remainderTicks;
    @Unique
    private int youran$remainderAmount;
    @Unique
    private DistillationHooks.Lookup youran$outerLookup;

    @Inject(method = "update", remap = true, at = @At("HEAD"))
    private void youran$beforeUpdate(CallbackInfo ci) {
        FluidStack in = tanks.length > 0 ? tanks[0].getFluid() : null;
        if (DistillationHooks.isRemainder(in) && in.amount == youran$remainderAmount) {
            youran$remainderTicks++;
        } else {
            youran$remainderTicks = 0;
            youran$remainderAmount = in == null ? 0 : in.amount;
        }
        youran$outerLookup = DistillationHooks.lookup(youran$remainderTicks >= DistillationHooks.IDLE_TICKS
                ? DistillationHooks.Lookup.UPDATE_REMAINDER : DistillationHooks.Lookup.UPDATE);
    }

    @Inject(method = "update", remap = true, at = @At("RETURN"))
    private void youran$afterUpdate(CallbackInfo ci) {
        DistillationHooks.lookup(youran$outerLookup == null ? DistillationHooks.Lookup.COMPARE : youran$outerLookup);
    }

    @Redirect(method = "update", remap = true, at = @At(value = "INVOKE", ordinal = 0, remap = false,
            target = "Lblusunrize/immersiveengineering/common/util/Utils;copyFluidStackWithAmount(Lnet/minecraftforge/fluids/FluidStack;IZ)Lnet/minecraftforge/fluids/FluidStack;"))
    private FluidStack youran$rememberPushing(FluidStack stack, int amount, boolean stripPressure) {
        youran$pushing = stack;
        return Utils.copyFluidStackWithAmount(stack, amount, stripPressure);
    }

    @Redirect(method = "update", remap = true, at = @At(value = "INVOKE", remap = false,
            target = "Lblusunrize/immersiveengineering/common/util/inventory/MultiFluidTank;drain(Lnet/minecraftforge/fluids/FluidStack;Z)Lnet/minecraftforge/fluids/FluidStack;"))
    private FluidStack youran$drainWithTag(MultiFluidTank tank, FluidStack resource, boolean doDrain) {
        FluidStack pushing = youran$pushing;
        youran$pushing = null;
        if (pushing != null && resource != null && pushing.tag != null && pushing.getFluid() == resource.getFluid()) {
            resource = new FluidStack(pushing, resource.amount);
        }
        return tank.drain(resource, doDrain);
    }
}
