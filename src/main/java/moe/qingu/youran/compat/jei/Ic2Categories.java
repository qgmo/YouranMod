package moe.qingu.youran.compat.jei;

import ic2.api.recipe.IBasicMachineRecipeManager;
import ic2.api.recipe.ICannerEnrichRecipeManager;
import ic2.api.recipe.Recipes;
import ic2.core.gui.GuiElement;
import ic2.core.ref.TeBlock;
import ic2.jeiIntegration.recipe.machine.CannerCategory;
import ic2.jeiIntegration.recipe.machine.DynamicCategory;
import mezz.jei.api.IGuiHelper;
import mezz.jei.api.gui.IDrawable;
import mezz.jei.api.gui.IGuiFluidStackGroup;
import mezz.jei.api.gui.IGuiItemStackGroup;
import mezz.jei.api.gui.IRecipeLayout;
import mezz.jei.api.ingredients.IIngredients;
import mezz.jei.api.recipe.IRecipeCategory;
import mezz.jei.api.recipe.IRecipeWrapper;
import moe.qingu.youran.YouranMod;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.I18n;
import net.minecraft.item.ItemStack;

import javax.annotation.Nullable;

/**
 * 照 IC2 自己的 JEI 分类画的两个分类（直接继承 IC2 的 {@code CannerCategory}、{@code DynamicCategory}，背景、格子、
 * 进度条都是 IC2 的）。只有装了 IC2 时才会被加载，插件里只在 {@code ic2()} 为真时才调这里。
 */
final class Ic2Categories {
    /** IC2 装罐机、洗矿机的流体罐容量 */
    static final int TANK_CAPACITY = 8000;

    private Ic2Categories() {
    }

    static IRecipeCategory<?> canner(IGuiHelper gui, String uid, ItemStack icon) {
        return new CannerReactionCategory(gui, uid, icon);
    }

    static IRecipeCategory<?> waferWashing(IGuiHelper gui, String uid, ItemStack icon) {
        return new WaferWashingCategory(gui, uid, icon);
    }

    /**
     * 装罐机反应：画成 IC2 装罐机富集模式那一页（左罐输入流体，中间格输入物品，右罐输出流体，右上格输出物品，底下是富集模式的图标）。
     *
     * <p>没有加进 IC2 自己的富集页：那一页只有一个输入物品格，没有输出物品格，我们的反应有产物是物品的。
     */
    private static final class CannerReactionCategory extends CannerCategory<ICannerEnrichRecipeManager> {
        /** IC2 罐子的位置（-40 + 39，-16 + 42，再往里缩 4 格是流体） */
        private static final int IN_TANK_X = 3;
        private static final int OUT_TANK_X = 81;
        private static final int TANK_Y = 30;
        /** 中间的输入物品格、右上的输出物品格（IC2 里添加剂格与成品格的位置） */
        private static final int IN_ITEM_X = 39;
        private static final int IN_ITEM_Y = 27;
        private static final int OUT_ITEM_X = 78;
        private static final int OUT_ITEM_Y = 0;

        private final String uid;
        private final IDrawable tankOverlay;
        @Nullable
        private final IDrawable icon;

        CannerReactionCategory(IGuiHelper gui, String uid, ItemStack iconStack) {
            super(CanningActivity.ENRICHING, gui);
            this.uid = uid;
            // 与 IC2 同一块刻度覆盖图（common.png 的 38,100，20×55，四边各出去 4 格盖住罐子的边）
            this.tankOverlay = gui.createDrawable(GuiElement.commonTexture, 38, 100, 20, 55, -4, -4, -4, -4);
            this.icon = iconStack.isEmpty() ? null : gui.createDrawableIngredient(iconStack);
        }

        @Override
        public String getUid() {
            return uid;
        }

        @Override
        public String getTitle() {
            return I18n.format("youranmod.jei.canner_reaction");
        }

        @Override
        public String getModName() {
            return YouranMod.MODID;
        }

        @Nullable
        @Override
        public IDrawable getIcon() {
            return icon;
        }

        @Override
        public void setRecipe(IRecipeLayout layout, IRecipeWrapper wrapper, IIngredients ingredients) {
            FluidItemRecipe recipe = (FluidItemRecipe) wrapper;
            IGuiFluidStackGroup fluids = layout.getFluidStacks();
            IGuiItemStackGroup items = layout.getItemStacks();
            if (recipe.inputFluid != null) {
                fluids.init(0, true, IN_TANK_X, TANK_Y, 12, 47, TANK_CAPACITY, false, tankOverlay);
                fluids.set(0, recipe.inputFluid);
            }
            if (recipe.outputFluid != null) {
                fluids.init(1, false, OUT_TANK_X, TANK_Y, 12, 47, TANK_CAPACITY, false, tankOverlay);
                fluids.set(1, recipe.outputFluid);
            }
            if (!recipe.inputItems.isEmpty()) {
                items.init(0, true, IN_ITEM_X, IN_ITEM_Y);
                items.set(0, recipe.inputItems);
            }
            if (!recipe.outputItems.isEmpty()) {
                items.init(1, false, OUT_ITEM_X, OUT_ITEM_Y);
                items.set(1, recipe.outputItems);
            }
        }
    }

    /**
     * 洗矿机洗硅晶圆：画成 IC2 洗矿机那一页（背景、格子、进度箭头、电量条都是 IC2 的），在它空着的罐子里放水，
     * 输出两格放高精与报废硅晶圆。纯净率与报废率写在页面底下。
     *
     * <p>没有加进 IC2 自己的洗矿机页：那一页不画水（原版配方只写“水 1000 mB”在配方元数据里），看不出水的区别。
     */
    private static final class WaferWashingCategory extends DynamicCategory<IBasicMachineRecipeManager> {
        /** IC2 洗矿机页：背景贴图裁出来的是 87×63，底下接说明 */
        private static final int PAGE_WIDTH = 160;
        private static final int GAUGE_TOP = 63;
        /** 罐子在界面里在 (60,20)，页面往上挪了 16 格；流体在罐里缩进 4 格 */
        private static final int TANK_X = 64;
        private static final int TANK_Y = 8;

        private final String uid;
        private final IDrawable tankOverlay;
        private final IDrawable background;
        @Nullable
        private final IDrawable icon;

        WaferWashingCategory(IGuiHelper gui, String uid, ItemStack iconStack) {
            super(TeBlock.ore_washing_plant, Recipes.oreWashing, gui);
            this.uid = uid;
            this.tankOverlay = gui.createDrawable(GuiElement.commonTexture, 38, 100, 20, 55, -4, -4, -4, -4);
            this.background = new IDrawable() {
                @Override
                public int getWidth() {
                    return PAGE_WIDTH;
                }

                @Override
                public int getHeight() {
                    return GAUGE_TOP + FluidItemRecipe.NOTES_GAP + FluidItemRecipe.NOTE_LINES * FluidItemRecipe.NOTE_LINE_HEIGHT;
                }

                @Override
                public void draw(Minecraft minecraft, int xOffset, int yOffset) {
                }
            };
            this.icon = iconStack.isEmpty() ? null : gui.createDrawableIngredient(iconStack);
        }

        @Override
        public String getUid() {
            return uid;
        }

        @Override
        public String getTitle() {
            return I18n.format("youranmod.jei.ore_washing_wafer");
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
        public void setRecipe(IRecipeLayout layout, IRecipeWrapper wrapper, IIngredients ingredients) {
            // 物品格（输入一格、输出三格）照 IC2 的写法放
            super.setRecipe(layout, wrapper, ingredients);
            FluidItemRecipe recipe = (FluidItemRecipe) wrapper;
            if (recipe.inputFluid != null) {
                IGuiFluidStackGroup fluids = layout.getFluidStacks();
                fluids.init(0, true, TANK_X, TANK_Y, 12, 47, TANK_CAPACITY, false, tankOverlay);
                fluids.set(0, recipe.inputFluid);
            }
        }
    }
}
