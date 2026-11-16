package moe.qingu.youran.mixin.ic2;

import ic2.core.block.invslot.InvSlot;
import ic2.core.block.invslot.InvSlotConsumableCanner;
import ic2.core.block.machine.tileentity.TileEntityCanner;
import moe.qingu.youran.compat.ic2.CannerReactionHooks;
import net.minecraft.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 装罐机的“罐子”格在“装罐”模式下也收装罐反应的固体（流体 + 固体 → 固体，{@link CannerReactionHooks#acceptsBottling}）。
 * IC2 原来在这个模式只收流体容器；收的照旧收，别的模式不变。界面放入、漏斗管道送入都经过这里。
 */
@Mixin(value = InvSlotConsumableCanner.class, remap = false)
public abstract class InvSlotConsumableCannerMixin {
    @Inject(method = "accepts(Lnet/minecraft/item/ItemStack;)Z", at = @At("RETURN"), cancellable = true)
    private void youran$acceptBottlingSolid(ItemStack stack, CallbackInfoReturnable<Boolean> cir) {
        if (cir.getReturnValueZ()) {
            return;
        }
        Object base = ((InvSlot) (Object) this).base;
        if (base instanceof TileEntityCanner && CannerReactionHooks.acceptsBottling((TileEntityCanner) base, stack)) {
            cir.setReturnValue(true);
        }
    }
}
