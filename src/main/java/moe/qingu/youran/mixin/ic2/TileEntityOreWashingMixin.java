package moe.qingu.youran.mixin.ic2;

import com.google.common.base.Predicate;
import ic2.core.block.comp.Fluids;
import ic2.core.block.machine.tileentity.TileEntityOreWashing;
import moe.qingu.youran.compat.ic2.OreWashingHooks;
import moe.qingu.youran.compat.ic2.WaferWasher;
import net.minecraft.item.ItemStack;
import net.minecraftforge.fluids.Fluid;
import net.minecraftforge.fluids.FluidTank;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Arrays;
import java.util.Collection;

/**
 * IC2 洗矿机（见 {@link OreWashingHooks}）：
 * <ul>
 *     <li>构造时：流体罐的判断（{@code Fluids.fluidPredicate(水)}）与倒空容器那格收的流体都加上别的水；</li>
 *     <li>{@code operateOnce} 开头：输入是硅晶圆时把要交付的东西换成高精或报废硅晶圆。</li>
 * </ul>
 * 另外加上标记接口 {@link WaferWasher}，加洗晶圆的配方前据此确认 Mixin 生效了。
 */
@Mixin(value = TileEntityOreWashing.class, remap = false)
public abstract class TileEntityOreWashingMixin implements WaferWasher {
    @Shadow
    @Final
    protected FluidTank fluidTank;

    @Redirect(method = "<init>", at = @At(value = "INVOKE",
            target = "Lic2/core/block/comp/Fluids;fluidPredicate([Lnet/minecraftforge/fluids/Fluid;)Lcom/google/common/base/Predicate;"))
    private Predicate<Fluid> youran$tankFluids(Fluid[] fluids) {
        return Fluids.fluidPredicate(OreWashingHooks.acceptedFluids(fluids));
    }

    @Inject(method = "<init>", at = @At("RETURN"))
    private void youran$slotFluids(CallbackInfo ci) {
        ((InvSlotConsumableLiquidByListAccessor) ((TileEntityOreWashing) (Object) this).fluidSlot).youran$acceptedFluids()
                .addAll(Arrays.asList(OreWashingHooks.acceptedFluids(new Fluid[0])));
    }

    @ModifyVariable(method = "operateOnce", at = @At("HEAD"), argsOnly = true)
    private Collection<ItemStack> youran$washWafer(Collection<ItemStack> processResult) {
        return OreWashingHooks.washResult((TileEntityOreWashing) (Object) this, fluidTank, processResult);
    }
}
