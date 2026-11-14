package moe.qingu.youran.mixin.ic2;

import ic2.core.block.invslot.InvSlot;
import ic2.core.block.invslot.InvSlotProcessableCanner;
import ic2.core.block.machine.tileentity.TileEntityCanner;
import moe.qingu.youran.compat.ic2.CannerReactionHooks;
import net.minecraft.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 装罐机的输入物品槽（富集模式下放添加物的那格）也收反应配方的输入物品。IC2 原来只收它自己富集配方里的添加物
 * （{@code InvSlotProcessable#accepts} 问配方表），界面放入、漏斗管道送入都经过这里。IC2 自己收的照旧收；
 * 别的模式不变（{@link CannerReactionHooks#accepts} 只在富集模式下回答）。
 */
@Mixin(value = InvSlotProcessableCanner.class, remap = false)
public abstract class InvSlotProcessableCannerMixin {
    @Inject(method = "accepts(Lnet/minecraft/item/ItemStack;)Z", at = @At("RETURN"), cancellable = true)
    private void youran$acceptReactionInput(ItemStack stack, CallbackInfoReturnable<Boolean> cir) {
        if (cir.getReturnValueZ()) {
            return;
        }
        Object base = ((InvSlot) (Object) this).base;
        if (base instanceof TileEntityCanner && CannerReactionHooks.accepts((TileEntityCanner) base, stack)) {
            cir.setReturnValue(true);
        }
    }
}
