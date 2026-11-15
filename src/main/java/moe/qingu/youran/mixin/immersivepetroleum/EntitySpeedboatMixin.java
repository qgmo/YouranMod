package moe.qingu.youran.mixin.immersivepetroleum;

import flaxbeard.immersivepetroleum.common.entity.EntitySpeedboat;
import moe.qingu.youran.logic.ip.BoatFuel;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraftforge.fluids.FluidStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 快艇的油量只能在 [0, {@code getMaxFuel()}] 里（审查 IP-M1）。沉浸原油原来 {@code setContainedFluid} 照收不误，
 * 客户端报负数的耗油量就能把油量加到容量以上，再用空桶一桶桶抽出来。所有改油量的地方（耗油包、桶加油抽油、
 * 读档、从物品放出来）最后都经过 {@code setContainedFluid}，在这里夹住。
 *
 * <p>读档（{@code readEntityFromNBT}）先读油量、后读升级，读油量时还不知道有没有油箱升级（容量 16000 还是 8000），
 * 所以读档中途只夹下界，读完升级后再按真正的容量夹一次，带油箱升级的船读档不会少油。
 */
@Mixin(value = EntitySpeedboat.class, remap = false)
public abstract class EntitySpeedboatMixin {
    @Unique
    private boolean youran$reading;

    @Shadow
    public abstract int getMaxFuel();

    @Shadow
    public abstract FluidStack getContainedFluid();

    @Shadow
    public abstract void setContainedFluid(FluidStack stack);

    @ModifyVariable(method = "setContainedFluid", at = @At("HEAD"), argsOnly = true)
    private FluidStack youran$clampFuel(FluidStack stack) {
        if (stack == null) {
            return null;
        }
        int clamped = BoatFuel.clamp(stack.amount, youran$reading ? Integer.MAX_VALUE : getMaxFuel());
        return clamped == stack.amount ? stack : new FluidStack(stack, clamped);
    }

    @Inject(method = "readEntityFromNBT", remap = true, at = @At("HEAD"))
    private void youran$beginRead(NBTTagCompound compound, CallbackInfo ci) {
        youran$reading = true;
    }

    @Inject(method = "readEntityFromNBT", remap = true, at = @At("RETURN"))
    private void youran$endRead(NBTTagCompound compound, CallbackInfo ci) {
        youran$reading = false;
        setContainedFluid(getContainedFluid());
    }
}
