package moe.qingu.youran.mixin.ic2;

import com.google.common.base.Predicate;
import ic2.core.block.comp.Fluids;
import ic2.core.block.machine.tileentity.TileEntitySolarDestiller;
import moe.qingu.youran.compat.ic2.SolarDestillerHooks;
import net.minecraftforge.fluids.Fluid;
import net.minecraftforge.fluids.FluidTank;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * IC2 太阳能蒸馏机也认三氯氢硅（提纯，见 {@link SolarDestillerHooks}）。它原来写死只认水、只出蒸馏水：
 * <ul>
 *     <li>构造时：输入罐的流体判断（{@code Fluids.fluidPredicate(水)}）加上三氯氢硅；输入槽（倒空容器的那格）收的流体也加上；</li>
 *     <li>{@code canWork()}：三氯氢硅由我们回答，输出罐里不是蒸馏水时不蒸水；别的照原来的；</li>
 *     <li>{@code updateEntityServer} 里每个周期问 {@code canWork()} 的地方：输入是三氯氢硅时由我们做（攒够 10 个周期出一份），
 *     返回 false 让原来的“抽 1 mB 水、出 1 mB 蒸馏水”不执行。</li>
 * </ul>
 * 提纯进度（0–9 个周期）不存档：读档后从 0 攒，只是晚一点出，不丢流体。
 */
@Mixin(value = TileEntitySolarDestiller.class, remap = false)
public abstract class TileEntitySolarDestillerMixin {
    @Shadow
    @Final
    public FluidTank inputTank;
    @Shadow
    @Final
    public FluidTank outputTank;
    @Shadow
    private float skyLight;

    @Unique
    private int youran$progress;

    @Redirect(method = "<init>", at = @At(value = "INVOKE",
            target = "Lic2/core/block/comp/Fluids;fluidPredicate([Lnet/minecraftforge/fluids/Fluid;)Lcom/google/common/base/Predicate;"))
    private Predicate<Fluid> youran$inputTankFluids(Fluid[] fluids) {
        return Fluids.fluidPredicate(SolarDestillerHooks.inputFluids(fluids));
    }

    @Inject(method = "<init>", at = @At("RETURN"))
    private void youran$inputSlotFluids(CallbackInfo ci) {
        Fluid[] fluids = SolarDestillerHooks.inputFluids(new Fluid[0]);
        ((InvSlotConsumableLiquidByListAccessor) ((TileEntitySolarDestiller) (Object) this).waterinputSlot).youran$acceptedFluids()
                .addAll(java.util.Arrays.asList(fluids));
    }

    @Inject(method = "canWork", at = @At("HEAD"), cancellable = true)
    private void youran$canWork(CallbackInfoReturnable<Boolean> cir) {
        Boolean result = SolarDestillerHooks.canWork(inputTank, outputTank, skyLight);
        if (result != null) {
            cir.setReturnValue(result);
        }
    }

    @Redirect(method = "updateEntityServer", at = @At(value = "INVOKE",
            target = "Lic2/core/block/machine/tileentity/TileEntitySolarDestiller;canWork()Z"))
    private boolean youran$work(TileEntitySolarDestiller self) {
        if (SolarDestillerHooks.isPurifying(inputTank)) {
            youran$progress = SolarDestillerHooks.tickPurify(inputTank, outputTank, skyLight, youran$progress);
            return false;
        }
        return self.canWork();
    }
}
