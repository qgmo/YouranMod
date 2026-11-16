package moe.qingu.youran.compat.immersiveengineering;

import blusunrize.immersiveengineering.api.crafting.BlueprintCraftingRecipe;
import net.minecraft.item.ItemStack;
import net.minecraft.util.NonNullList;

import javax.annotation.Nullable;
import java.util.List;

/**
 * 工程师装配台里产物要按“这一套输入”现算的蓝图（汞灯、主板、扩展卡）：装配台的配方产物本来是写死的、而且一次能做好几个，
 * 这里由 Mixin（{@code mixin.immersiveengineering.InventoryBlueprintMixin} 手动装配台、{@code MultiblockProcessOutputMixin} 自动装配台）
 * 换成一次一个、照这一套输入（会被扣掉的那几样）算出来的产物；算不出来（主板的槽已经满了等）时产物格为空，拿不了。
 */
public final class BlueprintOutputs {
    private BlueprintOutputs() {
    }

    /** 这条配方的产物要现算 */
    public static boolean handles(@Nullable Object recipe) {
        return LampAssembly.isLampRecipe(recipe) || BoardAssembly.isBoardRecipe(recipe);
    }

    /** 照会被扣掉的那一套输入（{@code consumed}，按配方的输入顺序、每样一份）算出来的产物；空物品 = 这样装不了 */
    public static ItemStack output(BlueprintCraftingRecipe recipe, List<ItemStack> consumed) {
        if (LampAssembly.isLampRecipe(recipe)) {
            return LampAssembly.output(recipe, consumed);
        }
        if (BoardAssembly.isBoardRecipe(recipe)) {
            return BoardAssembly.output(recipe, consumed);
        }
        return recipe.output.copy();
    }

    /** 手动装配台：照沉浸工程扣输入的办法在副本上扣一遍，得到会被扣掉的那一套 */
    public static List<ItemStack> simulateConsume(BlueprintCraftingRecipe recipe, List<ItemStack> inputs) {
        NonNullList<ItemStack> copy = NonNullList.create();
        for (ItemStack s : inputs) {
            copy.add(s.copy());
        }
        return recipe.consumeInputs(copy, 1);
    }
}
