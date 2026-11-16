package moe.qingu.youran.mixin.immersiveengineering;

import blusunrize.immersiveengineering.api.crafting.BlueprintCraftingRecipe;
import blusunrize.immersiveengineering.common.blocks.metal.TileEntityMultiblockMetal;
import moe.qingu.youran.compat.immersiveengineering.LampAssembly;
import moe.qingu.youran.compat.immersiveengineering.LampAssemblyPatched;
import net.minecraft.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.Collections;
import java.util.List;

/**
 * 自动工程师装配台：每个处理过程只装一套输入（沉浸工程本来就是 {@code consumeInputs(query, 1)}），做完时蓝图“汞灯”的产物
 * 换成带这套输入里那根玻璃管数据与气体的灯（{@link LampAssembly}）。别的处理过程照原来的走。
 */
@Mixin(value = TileEntityMultiblockMetal.MultiblockProcess.class, remap = false)
public abstract class MultiblockProcessOutputMixin implements LampAssemblyPatched {
    @Inject(method = "getRecipeItemOutputs", at = @At("HEAD"), cancellable = true)
    private void youran$lampOutput(TileEntityMultiblockMetal multiblock, CallbackInfoReturnable<List<ItemStack>> cir) {
        Object self = this;
        if (!(self instanceof TileEntityMultiblockMetal.MultiblockProcessInWorld)) {
            return;
        }
        TileEntityMultiblockMetal.MultiblockProcessInWorld<?> p = (TileEntityMultiblockMetal.MultiblockProcessInWorld<?>) self;
        if (LampAssembly.isLampRecipe(p.recipe)) {
            cir.setReturnValue(Collections.singletonList(LampAssembly.output((BlueprintCraftingRecipe) p.recipe, p.inputItems)));
        }
    }
}
