package moe.qingu.youran.mixin.immersivepetroleum;

import flaxbeard.immersivepetroleum.api.crafting.DistillationRecipe;
import moe.qingu.youran.compat.immersivepetroleum.DistillationHooks;
import net.minecraftforge.fluids.FluidStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 精馏塔找配方时，三氯氢硅由 {@link DistillationHooks} 按输入的纯度给一份提纯配方；别的流体照沉浸原油原来的找。
 * {@code findRecipe} 是精馏塔唯一找配方的地方：每 tick 开始处理、管道往输入口灌、界面放容器、读档恢复处理中的配方都经过它。
 */
@Mixin(value = DistillationRecipe.class, remap = false)
public abstract class DistillationRecipeMixin {
    @Inject(method = "findRecipe", at = @At("HEAD"), cancellable = true)
    private static void youran$trichlorosilane(FluidStack input, CallbackInfoReturnable<DistillationRecipe> cir) {
        if (DistillationHooks.handles(input)) {
            cir.setReturnValue(DistillationHooks.recipeFor(input));
        }
    }
}
