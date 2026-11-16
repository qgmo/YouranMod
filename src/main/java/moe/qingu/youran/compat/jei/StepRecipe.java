package moe.qingu.youran.compat.jei;

import mezz.jei.api.ingredients.IIngredients;
import mezz.jei.api.ingredients.VanillaTypes;
import mezz.jei.api.recipe.IRecipeWrapper;
import net.minecraft.client.Minecraft;
import net.minecraft.item.ItemStack;
import net.minecraftforge.fluids.FluidStack;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 只展示用的一步加工（服主 11-16：能做但原来 JEI 里查不到的步骤）：几个物品输入（每个可以有几个候选，一起轮换）、最多一种流体输入、
 * 物品输出，底下几行说明。不对应任何真实的配方表，机器实际怎么做由各自的 Mixin / 流体能力决定。
 * 说明只写定性的（谁比谁好、会不会），不写最佳配比和概率。
 */
final class StepRecipe implements IRecipeWrapper {
    private static final int TEXT_COLOR = 0x404040;

    final List<List<ItemStack>> inputs;
    @Nullable
    final FluidStack inputFluid;
    final List<List<ItemStack>> outputs;
    private final List<String> notes;

    StepRecipe(List<List<ItemStack>> inputs, @Nullable FluidStack inputFluid, List<List<ItemStack>> outputs, List<String> notes) {
        this.inputs = inputs;
        this.inputFluid = inputFluid;
        this.outputs = outputs;
        this.notes = notes;
    }

    static List<ItemStack> one(ItemStack stack) {
        return Collections.singletonList(stack);
    }

    static List<List<ItemStack>> list(List<ItemStack>... lists) {
        return new ArrayList<>(java.util.Arrays.asList(lists));
    }

    @Override
    public void getIngredients(IIngredients ingredients) {
        if (!inputs.isEmpty()) {
            ingredients.setInputLists(VanillaTypes.ITEM, inputs);
        }
        if (inputFluid != null) {
            ingredients.setInput(VanillaTypes.FLUID, inputFluid);
        }
        if (!outputs.isEmpty()) {
            ingredients.setOutputLists(VanillaTypes.ITEM, outputs);
        }
    }

    @Override
    public void drawInfo(Minecraft minecraft, int recipeWidth, int recipeHeight, int mouseX, int mouseY) {
        int y = StepCategories.NOTES_Y;
        for (String note : notes) {
            for (String line : minecraft.fontRenderer.listFormattedStringToWidth(note, recipeWidth)) {
                minecraft.fontRenderer.drawString(line, 0, y, TEXT_COLOR);
                y += FluidItemRecipe.NOTE_LINE_HEIGHT;
            }
        }
    }
}
