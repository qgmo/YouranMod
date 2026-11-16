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
 * 装罐机反应配方、太阳能蒸馏机、洗矿机洗晶圆、精馏塔提纯都用它，各自的分类决定画成什么样（{@link Ic2Categories}、
 * {@link PetroleumCategories}、{@link SolarDistillerCategory}）。
 */
final class FluidItemRecipe implements IRecipeWrapper {
    /** 说明文字的颜色（JEI 里常用的深灰） */
    private static final int TEXT_COLOR = 0x404040;
    /** 各分类的背景底下都留这么多行给说明 */
    static final int NOTE_LINES = 4;
    /** 说明与上面的图之间留的空 */
    static final int NOTES_GAP = 4;
    /** 一行说明占的高度（字体高 9 + 行距 1） */
    static final int NOTE_LINE_HEIGHT = 10;

    @Nullable
    final FluidStack inputFluid;
    /** 输入物品的候选（矿物词典等）；没有输入物品时为空 */
    final List<ItemStack> inputItems;
    @Nullable
    final FluidStack outputFluid;
    /** 输出物品（没有为空列表） */
    final List<ItemStack> outputItems;
    private final List<String> notes;

    FluidItemRecipe(@Nullable FluidStack inputFluid, List<ItemStack> inputItems, @Nullable FluidStack outputFluid,
                    List<ItemStack> outputItems, List<String> notes) {
        this.inputFluid = inputFluid;
        this.inputItems = inputItems;
        this.outputFluid = outputFluid;
        this.outputItems = outputItems;
        this.notes = notes;
    }

    FluidItemRecipe(@Nullable FluidStack inputFluid, List<ItemStack> inputItems, @Nullable FluidStack outputFluid,
                    ItemStack outputItem, List<String> notes) {
        this(inputFluid, inputItems, outputFluid,
                outputItem.isEmpty() ? Collections.<ItemStack>emptyList() : Collections.singletonList(outputItem), notes);
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
        if (!outputItems.isEmpty()) {
            ingredients.setOutputs(VanillaTypes.ITEM, outputItems);
        }
    }

    @Override
    public void drawInfo(Minecraft minecraft, int recipeWidth, int recipeHeight, int mouseX, int mouseY) {
        int y = recipeHeight - NOTE_LINES * NOTE_LINE_HEIGHT;
        for (String note : notes) {
            for (String line : minecraft.fontRenderer.listFormattedStringToWidth(note, recipeWidth)) {
                minecraft.fontRenderer.drawString(line, 0, y, TEXT_COLOR);
                y += NOTE_LINE_HEIGHT;
            }
        }
    }
}
