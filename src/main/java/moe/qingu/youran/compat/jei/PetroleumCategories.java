package moe.qingu.youran.compat.jei;

import mezz.jei.api.IGuiHelper;
import mezz.jei.api.gui.IDrawable;
import mezz.jei.api.gui.IGuiFluidStackGroup;
import mezz.jei.api.gui.IRecipeLayout;
import mezz.jei.api.ingredients.IIngredients;
import mezz.jei.api.recipe.IRecipeCategory;
import moe.qingu.youran.YouranMod;
import net.minecraft.client.resources.I18n;
import net.minecraft.item.ItemStack;
import net.minecraft.util.ResourceLocation;

import javax.annotation.Nullable;

/** 沉浸原油的分类。只有装了沉浸原油时才会被加载（只用它的贴图） */
final class PetroleumCategories {
    private PetroleumCategories() {
    }

    static IRecipeCategory<?> distillationTower(IGuiHelper gui, String uid, ItemStack icon) {
        return new DistillationTowerCategory(gui, uid, icon);
    }

    /**
     * 精馏塔提纯：沉浸原油自己没有接 JEI，精馏塔的原油配方也没有页面。这里照沉浸工程的 JEI 页（炼油厂等）的做法画：
     * 机器界面的贴图裁出来当背景，罐子套上界面里的刻度覆盖图。输入罐在塔身里，输出罐在右边（位置、大小同
     * {@code GuiDistillationTower}）。
     */
    private static final class DistillationTowerCategory implements IRecipeCategory<FluidItemRecipe> {
        private static final ResourceLocation GUI = new ResourceLocation("immersivepetroleum", "textures/gui/distillation.png");
        /** 界面贴图里塔身加右边那块面板 */
        private static final int CROP_X = 54;
        private static final int CROP_Y = 0;
        private static final int CROP_W = 122;
        private static final int CROP_H = 76;
        private static final int WIDTH = 160;
        private static final int HEIGHT = CROP_H + FluidItemRecipe.NOTES_GAP + FluidItemRecipe.NOTE_LINES * FluidItemRecipe.NOTE_LINE_HEIGHT;
        private static final int IN_X = 62;
        private static final int OUT_X = 112;
        private static final int TANK_Y = 21;
        private static final int TANK_W = 16;
        private static final int TANK_H = 47;

        private final String uid;
        private final CropBackground background;
        private final IDrawable tankOverlay;
        @Nullable
        private final IDrawable icon;

        DistillationTowerCategory(IGuiHelper gui, String uid, ItemStack iconStack) {
            this.uid = uid;
            this.background = new CropBackground(gui.createDrawable(GUI, CROP_X, CROP_Y, CROP_W, CROP_H), WIDTH, HEIGHT);
            // 界面里罐子上的刻度（177,31 起 20×51，四边各出去 2 格）
            this.tankOverlay = gui.createDrawable(GUI, 177, 31, 20, 51, -2, -2, -2, -2);
            this.icon = iconStack.isEmpty() ? null : gui.createDrawableIngredient(iconStack);
        }

        @Override
        public String getUid() {
            return uid;
        }

        @Override
        public String getTitle() {
            return I18n.format("youranmod.jei.distillation_tower");
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
        public void setRecipe(IRecipeLayout layout, FluidItemRecipe recipe, IIngredients ingredients) {
            IGuiFluidStackGroup fluids = layout.getFluidStacks();
            // 容量取进出较大的 4 倍：同沉浸工程炼油厂的 JEI 页
            int capacity = Math.max(recipe.inputFluid == null ? 0 : recipe.inputFluid.amount,
                    recipe.outputFluid == null ? 0 : recipe.outputFluid.amount) * 4;
            int dx = background.padX() - CROP_X;
            if (recipe.inputFluid != null) {
                fluids.init(0, true, IN_X + dx, TANK_Y - CROP_Y, TANK_W, TANK_H, capacity, false, tankOverlay);
                fluids.set(0, recipe.inputFluid);
            }
            if (recipe.outputFluid != null) {
                // 一次出几种流体：同一个罐里自下而上叠着（精馏塔的输出罐是多流体罐），一种一个格子，按量分高度
                java.util.List<net.minecraftforge.fluids.FluidStack> outs = new java.util.ArrayList<>();
                outs.add(recipe.outputFluid);
                outs.addAll(recipe.extraOutputFluids);
                int total = 0;
                for (net.minecraftforge.fluids.FluidStack f : outs) {
                    total += f.amount;
                }
                int y = TANK_Y - CROP_Y + TANK_H;
                for (int i = 0; i < outs.size(); i++) {
                    int h = outs.size() == 1 ? TANK_H : Math.max(4, TANK_H * outs.get(i).amount / total);
                    y -= h;
                    fluids.init(1 + i, false, OUT_X + dx, y, TANK_W, h, outs.get(i).amount, false, i == 0 ? tankOverlay : null);
                    fluids.set(1 + i, outs.get(i));
                }
            }
            // 按概率掉出的物品放在罐子右边
            if (!recipe.outputItems.isEmpty()) {
                layout.getItemStacks().init(0, false, OUT_X + dx + TANK_W + 6, TANK_Y - CROP_Y + 10);
                layout.getItemStacks().set(0, recipe.outputItems);
            }
        }
    }
}
