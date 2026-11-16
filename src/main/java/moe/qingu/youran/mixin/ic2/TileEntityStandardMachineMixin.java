package moe.qingu.youran.mixin.ic2;

import ic2.api.recipe.MachineRecipeResult;
import ic2.core.block.machine.tileentity.TileEntitySolidCanner;
import ic2.core.block.machine.tileentity.TileEntityStandardMachine;
import moe.qingu.youran.compat.ic2.CannerReactionHooks;
import moe.qingu.youran.compat.ic2.CentrifugeHooks;
import moe.qingu.youran.compat.ic2.GasFillHooks;
import net.minecraft.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.Collection;

/**
 * IC2 标准机器：
 * <ul>
 *     <li>做完一次时（{@code operateOnce} 开头）：热能离心机分铜矿按几率多出小撮钼粉（{@link CentrifugeHooks}）；</li>
 *     <li>决定能不能做时（{@code getOutput()} 开头）：固体装罐机给 ArF 激光腔、氦氖激光管放气体元素，产物按罐子格里那一件的数据现算
 *     （{@link GasFillHooks}，同流体 / 固体装罐机的“装罐”模式）。固体装罐机自己没有重写 {@code getOutput()}，所以挂在这里、按类型认；
 *     流体 / 固体装罐机重写了它，由 {@code TileEntityCannerMixin} 管。</li>
 * </ul>
 * 别的机器照原来的走。只在服务端被调用。
 */
@Mixin(value = TileEntityStandardMachine.class, remap = false)
public abstract class TileEntityStandardMachineMixin {
    @ModifyVariable(method = "operateOnce", at = @At("HEAD"), argsOnly = true)
    private Collection<ItemStack> youran$byproduct(Collection<ItemStack> processResult) {
        return CentrifugeHooks.withByproduct((TileEntityStandardMachine<?, ?, ?>) (Object) this, processResult);
    }

    @Inject(method = "getOutput()Lic2/api/recipe/MachineRecipeResult;", at = @At("HEAD"), cancellable = true)
    private void youran$gasFill(CallbackInfoReturnable<MachineRecipeResult<Object, Object, Object>> cir) {
        Object self = this;
        if (self instanceof TileEntitySolidCanner) {
            CannerReactionHooks.Outcome gas = GasFillHooks.getOutput((TileEntitySolidCanner) self);
            if (gas.handled) {
                cir.setReturnValue(gas.result);
            }
        }
    }
}
