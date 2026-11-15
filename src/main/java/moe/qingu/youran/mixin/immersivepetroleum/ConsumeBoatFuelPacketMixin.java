package moe.qingu.youran.mixin.immersivepetroleum;

import flaxbeard.immersivepetroleum.api.energy.FuelHandler;
import flaxbeard.immersivepetroleum.common.entity.EntitySpeedboat;
import moe.qingu.youran.compat.immersivepetroleum.IpRejects;
import moe.qingu.youran.logic.ip.BoatFuel;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraftforge.fluids.FluidStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 快艇耗油包（{@code ConsumeBoatFuelPacket}，客户端每 tick 报一次烧掉多少）在主线程处理时先查量（审查 IP-M1）：
 * 负数（反向加油）和超过加速耗量（每 tick 耗量 × {@value BoatFuel#BOOST_FACTOR}）的不收，船的油量不变。
 * 沉浸原油已经把处理放进主线程（{@code DoSync.run}），这里跟着在主线程查。
 */
@Mixin(targets = "flaxbeard.immersivepetroleum.common.network.ConsumeBoatFuelPacket$DoSync", remap = false)
public abstract class ConsumeBoatFuelPacketMixin {
    @Shadow
    private EntityPlayer p;
    @Shadow
    private int amount;

    @Inject(method = "run", at = @At("HEAD"), cancellable = true)
    private void youran$checkAmount(CallbackInfo ci) {
        if (p == null) {
            return;
        }
        Entity riding = p.getRidingEntity();
        if (!(riding instanceof EntitySpeedboat)) {
            return;
        }
        FluidStack fluid = ((EntitySpeedboat) riding).getContainedFluid();
        int perTick = fluid == null ? 0 : FuelHandler.getBoatFuelUsedPerTick(fluid.getFluid());
        if (fluid != null && !BoatFuel.acceptsConsumed(amount, perTick)) {
            IpRejects.reject(p.getName(), "快艇耗油包", "耗油量 " + amount + " mB（这种燃料每 tick " + perTick + " mB）");
            ci.cancel();
        }
    }
}
