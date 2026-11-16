package moe.qingu.youran.compat.jei;

import mezz.jei.api.IGuiHelper;
import mezz.jei.api.gui.IDrawable;
import mezz.jei.api.gui.IDrawableAnimated;
import mezz.jei.api.gui.IDrawableStatic;
import mezz.jei.api.gui.IGuiFluidStackGroup;
import mezz.jei.api.gui.IGuiItemStackGroup;
import mezz.jei.api.gui.IRecipeLayout;
import mezz.jei.api.ingredients.IIngredients;
import mezz.jei.api.recipe.IRecipeCategory;
import moe.qingu.youran.YouranMod;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.I18n;
import net.minecraft.item.ItemStack;
import net.minecraft.util.ResourceLocation;

import javax.annotation.Nullable;
import java.util.List;

/**
 * 两个只展示用的加工分类（{@link StepRecipe}）：灌入容器（汞灯补汞、激光腔灌氩：IC2 装罐机的“装罐”模式、沉浸工程灌装机，
 * 都按物品的流体能力灌，没有登记成配方）与紫外擦除器（本模组的方块，没有原模组的页可照）。
 * 格子用 JEI 自带的格子、箭头用原版熔炉界面里的箭头，同 {@code FluidItemRecipe} 的说明文字。
 */
final class StepCategories {
    static final int WIDTH = 150;
    /** 说明从这一行开始 */
    static final int NOTES_Y = 40;
    static final int HEIGHT = NOTES_Y + FluidItemRecipe.NOTES_GAP + FluidItemRecipe.NOTE_LINES * FluidItemRecipe.NOTE_LINE_HEIGHT;
    private static final ResourceLocation FURNACE = new ResourceLocation("minecraft", "textures/gui/container/furnace.png");

    private StepCategories() {
    }

    private abstract static class Base implements IRecipeCategory<StepRecipe> {
        private final String uid;
        private final String titleKey;
        private final IDrawable background;
        final IDrawableStatic slot;
        private final IDrawableStatic arrowEmpty;
        private final IDrawableAnimated arrow;
        @Nullable
        private final IDrawable icon;
        private final int arrowX;
        private final int arrowY;

        Base(IGuiHelper gui, String uid, String titleKey, ItemStack iconStack, int arrowX, int arrowY) {
            this.uid = uid;
            this.titleKey = titleKey;
            this.background = gui.createBlankDrawable(WIDTH, HEIGHT);
            this.slot = gui.getSlotDrawable();
            this.arrowEmpty = gui.createDrawable(FURNACE, 79, 35, 24, 16);
            this.arrow = gui.createAnimatedDrawable(gui.createDrawable(FURNACE, 176, 14, 24, 16), 80, IDrawableAnimated.StartDirection.LEFT, false);
            this.icon = iconStack.isEmpty() ? null : gui.createDrawableIngredient(iconStack);
            this.arrowX = arrowX;
            this.arrowY = arrowY;
        }

        @Override
        public String getUid() {
            return uid;
        }

        @Override
        public String getTitle() {
            return I18n.format(titleKey);
        }

        @Override
        public String getModName() {
            return YouranMod.MODID;
        }

        @Override
        public IDrawable getBackground() {
            return background;
        }

        @Nullable
        @Override
        public IDrawable getIcon() {
            return icon;
        }

        @Override
        public void drawExtras(Minecraft minecraft) {
            arrowEmpty.draw(minecraft, arrowX, arrowY);
            arrow.draw(minecraft, arrowX, arrowY);
        }

        void items(IGuiItemStackGroup items, int index, boolean input, int x, int y, @Nullable List<ItemStack> stacks) {
            items.init(index, input, x, y);
            items.setBackground(index, slot);
            if (stacks != null) {
                items.set(index, stacks);
            }
        }
    }

    static IRecipeCategory<StepRecipe> uvEraser(IGuiHelper gui, String uid, ItemStack icon) {
        return new Base(gui, uid, "youranmod.jei.uv_eraser", icon, 82, 12) {
            @Override
            public void setRecipe(IRecipeLayout layout, StepRecipe recipe, IIngredients ingredients) {
                IGuiItemStackGroup items = layout.getItemStacks();
                // 灯、要加工的东西、底片（没有底片的活没有这一格）、输出
                items(items, 0, true, 10, 0, recipe.inputs.get(0));
                items(items, 1, true, 10, 20, recipe.inputs.get(1));
                if (recipe.inputs.size() > 2) {
                    items(items, 2, true, 46, 10, recipe.inputs.get(2));
                }
                items(items, 3, false, 118, 10, recipe.outputs.get(0));
            }
        };
    }
}
