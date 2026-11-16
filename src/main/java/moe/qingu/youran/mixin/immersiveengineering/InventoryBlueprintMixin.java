package moe.qingu.youran.mixin.immersiveengineering;

import blusunrize.immersiveengineering.api.crafting.BlueprintCraftingRecipe;
import blusunrize.immersiveengineering.common.gui.InventoryBlueprint;
import moe.qingu.youran.compat.immersiveengineering.BlueprintOutputs;
import moe.qingu.youran.compat.immersiveengineering.LampAssemblyPatched;
import net.minecraft.inventory.IInventory;
import net.minecraft.item.ItemStack;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.ArrayList;
import java.util.List;

/**
 * 手动的工程师装配台：蓝图“汞灯”“主板”“扩展卡”的产物格一次只给一个，照会被扣掉的那一套输入现算（{@link BlueprintOutputs}：
 * 汞灯带上玻璃管的数据与气体，主板、汉卡带上芯片的数据）。拿走时沉浸工程按“拿了几个 ÷ 配方产量”扣输入，产量是 1，所以正好扣一套，
 * 扣的就是这里在副本上照同样的办法扣出来的那一套。
 */
@Mixin(value = InventoryBlueprint.class, remap = false)
public abstract class InventoryBlueprintMixin implements LampAssemblyPatched {
    @Shadow
    @Final
    private BlueprintCraftingRecipe[] recipes;

    @Inject(method = "updateOutputs", at = @At("TAIL"))
    private void youran$oneLamp(IInventory inputInventory, CallbackInfo ci) {
        List<ItemStack> inputs = null;
        IInventory self = (IInventory) this;
        for (int i = 0; i < recipes.length; i++) {
            if (!BlueprintOutputs.handles(recipes[i]) || self.getStackInSlot(i).isEmpty()) {
                continue;
            }
            if (inputs == null) {
                inputs = new ArrayList<>();
                for (int j = 1; j < inputInventory.getSizeInventory(); j++) {
                    inputs.add(inputInventory.getStackInSlot(j));
                }
            }
            self.setInventorySlotContents(i, BlueprintOutputs.output(recipes[i], BlueprintOutputs.simulateConsume(recipes[i], inputs)));
        }
    }
}
