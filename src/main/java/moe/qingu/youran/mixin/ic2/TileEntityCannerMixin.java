package moe.qingu.youran.mixin.ic2;

import ic2.api.recipe.MachineRecipeResult;
import ic2.core.block.machine.tileentity.TileEntityCanner;
import moe.qingu.youran.compat.ic2.CannerReactionHooks;
import net.minecraft.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.Collection;

/**
 * 装罐机富集模式加上反应配方（流体 + 物品 → 流体 + 物品）；固体装罐模式下给激光管放气体元素（{@code compat.ic2.GasFillHooks}）。三处都在方法开头，不是反应配方时什么也不做、照 IC2 原来的走：
 * <ul>
 *     <li>{@code getOutput()}：每 tick 由 {@code TileEntityStandardMachine#updateEntityServer} 调用，决定能不能做。富集模式下先查反应配方表，
 *     对得上就由我们回答（能做时给出结果，输入不够、输出放不下时返回 null：不开始，做到一半时进度清零，同 IC2）；</li>
 *     <li>{@code getOutput(Object)}：把结果换成要放进物品输出槽的物品（IC2 自己的富集配方不出物品）；</li>
 *     <li>{@code operateOnce}：做完时交付。IC2 原来的写法会把输出当成 {@code FluidStack}，所以反应配方由我们交付并跳过原来的。</li>
 * </ul>
 * 只在服务端（独立服务端或单人游戏的内置服务端）被调用。
 */
@Mixin(value = TileEntityCanner.class, remap = false)
public abstract class TileEntityCannerMixin {
    @Inject(method = "getOutput()Lic2/api/recipe/MachineRecipeResult;", at = @At("HEAD"), cancellable = true)
    private void youran$reactionOutput(CallbackInfoReturnable<MachineRecipeResult<Object, Object, Object>> cir) {
        TileEntityCanner canner = (TileEntityCanner) (Object) this;
        if (canner.getMode() == TileEntityCanner.Mode.BottleSolid) {
            // 固体装罐：给 ArF 激光腔、氦氖激光管放气体元素（产物按罐子格里那一件的数据现算）
            CannerReactionHooks.Outcome gas = moe.qingu.youran.compat.ic2.GasFillHooks.getOutput(canner);
            if (gas.handled) {
                cir.setReturnValue(gas.result);
            }
            return;
        }
        if (canner.getMode() != TileEntityCanner.Mode.EnrichLiquid) {
            return;
        }
        CannerReactionHooks.Outcome outcome = CannerReactionHooks.getOutput(canner);
        if (outcome.handled) {
            cir.setReturnValue(outcome.result);
        }
    }

    @Inject(method = "getOutput(Ljava/lang/Object;)Ljava/util/Collection;", at = @At("HEAD"), cancellable = true)
    private void youran$reactionItems(Object output, CallbackInfoReturnable<Collection<ItemStack>> cir) {
        Collection<ItemStack> items = CannerReactionHooks.processResult(output);
        if (items != null) {
            cir.setReturnValue(items);
        }
    }

    @Inject(method = "operateOnce", at = @At("HEAD"), cancellable = true)
    private void youran$reactionOperate(MachineRecipeResult<Object, Object, Object> result, Collection<ItemStack> processResult, CallbackInfo ci) {
        if (CannerReactionHooks.operateOnce((TileEntityCanner) (Object) this, result, processResult)) {
            ci.cancel();
        }
    }
}
