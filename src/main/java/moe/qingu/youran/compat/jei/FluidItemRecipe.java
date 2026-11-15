package moe.qingu.youran.compat.jei;

import mezz.jei.api.ingredients.IIngredients;
import mezz.jei.api.ingredients.VanillaTypes;
import mezz.jei.api.recipe.IRecipeWrapper;
import net.minecraft.client.Minecraft;
import net.minecraft.item.ItemStack;
import net.minecraftforge.fluids.FluidStack;

import javax.annotation.Nullable;
import java.util.Collections;
import java.util.List;

/**
 * JEI 里的一条“流体 + 物品 → 流体 + 物品”配方（四样都可以没有，至少一进一出），下面可以带几行说明（处理速度等）。
 * 装罐机反应配方、太阳能蒸馏机、精馏塔提纯都用它显示（{@link FluidItemCategory}）。
 */
final class FluidItemRecipe implements IRecipeWrapper {
    /** 说明文字的颜色（JEI 里常用的深灰） */
    private static final int TEXT_COLOR = 0x404040;

    @Nullable
    final FluidStack inputFluid;
    /** 输入物品的候选（矿物词典等）；没有输入物品时为空 */
    final List<ItemStack> inputItems;
    @Nullable
    final FluidStack outputFluid;
    final ItemStack outputItem;
    private final List<String> notes;

    FluidItemRecipe(@Nullable FluidStack inputFluid, List<ItemStack> inputItems, @Nullable FluidStack outputFluid,
                    ItemStack outputItem, List<String> notes) {
        this.inputFluid = inputFluid;
        this.inputItems = inputItems;
        this.outputFluid = outputFluid;
        this.outputItem = outputItem;
        this.notes = notes;
    }

    static FluidItemRecipe fluids(FluidStack input, FluidStack output, List<String> notes) {
        return new FluidItemRecipe(input, Collections.<ItemStack>emptyList(), output, ItemStack.EMPTY, notes);
    }

    @Override
    public void getIngredients(IIngredients ingredients) {
        if (inputFluid != null) {
            ingredients.setInput(VanillaTypes.FLUID, inputFluid);
        }
        if (!inputItems.isEmpty()) {
            ingredients.setInputLists(VanillaTypes.ITEM, Collections.singletonList(inputItems));
        }
        if (outputFluid != null) {
            ingredients.setOutput(VanillaTypes.FLUID, outputFluid);
        }
        if (!outputItem.isEmpty()) {
            ingredients.setOutput(VanillaTypes.ITEM, outputItem);
        }
    }

    @Override
    public void drawInfo(Minecraft minecraft, int recipeWidth, int recipeHeight, int mouseX, int mouseY) {
        int y = FluidItemCategory.NOTES_Y;
        for (String note : notes) {
            for (String line : minecraft.fontRenderer.listFormattedStringToWidth(note, recipeWidth)) {
                minecraft.fontRenderer.drawString(line, 0, y, TEXT_COLOR);
                y += minecraft.fontRenderer.FONT_HEIGHT + 1;
            }
        }
    }
}
