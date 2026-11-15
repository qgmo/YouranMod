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
import moe.qingu.youran.fluid.FluidPurity;
import moe.qingu.youran.logic.purity.Purity;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.I18n;
import net.minecraft.item.ItemStack;
import net.minecraft.util.ResourceLocation;

import javax.annotation.Nullable;

/**
 * 一种机器的“流体 + 物品 → 流体 + 物品”配方分类：左边输入流体、输入物品，中间箭头，右边输出流体、输出物品，下面是说明。
 * 三氯氢硅的格子在悬停说明里多一行“几个 9”（名字里已经带了纯度百分数，见 {@code fluid.FluidTrichlorosilane}）。
 */
final class FluidItemCategory implements IRecipeCategory<FluidItemRecipe> {
    static final int WIDTH = 150;
    static final int HEIGHT = 50;
    /** 说明文字从这一行开始 */
    static final int NOTES_Y = 26;
    private static final int SLOTS_Y = 4;
    private static final int IN_FLUID_X = 24;
    private static final int IN_ITEM_X = 44;
    private static final int ARROW_X = 64;
    private static final int OUT_FLUID_X = 94;
    private static final int OUT_ITEM_X = 114;
    /** 原版熔炉界面里的箭头（空的与满的） */
    private static final ResourceLocation FURNACE = new ResourceLocation("minecraft", "textures/gui/container/furnace.png");

    private final String uid;
    private final String titleKey;
    private final IDrawable background;
    private final IDrawableStatic slot;
    /** 流体格子的底：流体画在 16×16 里，格子的底要往左上挪 1 格（物品格子 JEI 自己留了边） */
    private final IDrawable fluidSlot;
    private final IDrawableStatic arrowEmpty;
    private final IDrawableAnimated arrow;
    @Nullable
    private final IDrawable icon;

    FluidItemCategory(IGuiHelper gui, String uid, String titleKey, ItemStack iconStack) {
        this.uid = uid;
        this.titleKey = titleKey;
        this.background = gui.createBlankDrawable(WIDTH, HEIGHT);
        this.slot = gui.getSlotDrawable();
        this.fluidSlot = new IDrawable() {
            @Override
            public int getWidth() {
                return slot.getWidth();
            }

            @Override
            public int getHeight() {
                return slot.getHeight();
            }

            @Override
            public void draw(Minecraft minecraft, int xOffset, int yOffset) {
                slot.draw(minecraft, xOffset - 1, yOffset - 1);
            }
        };
        this.arrowEmpty = gui.createDrawable(FURNACE, 79, 35, 24, 16);
        this.arrow = gui.createAnimatedDrawable(gui.createDrawable(FURNACE, 176, 14, 24, 16), 80, IDrawableAnimated.StartDirection.LEFT, false);
        this.icon = iconStack.isEmpty() ? null : gui.createDrawableIngredient(iconStack);
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
        arrowEmpty.draw(minecraft, ARROW_X, SLOTS_Y);
        arrow.draw(minecraft, ARROW_X, SLOTS_Y);
    }

    @Override
    public void setRecipe(IRecipeLayout layout, FluidItemRecipe recipe, IIngredients ingredients) {
        IGuiFluidStackGroup fluids = layout.getFluidStacks();
        IGuiItemStackGroup items = layout.getItemStacks();
        if (recipe.inputFluid != null) {
            fluids.init(0, true, IN_FLUID_X, SLOTS_Y, 16, 16, recipe.inputFluid.amount, false, null);
            fluids.setBackground(0, fluidSlot);
            fluids.set(0, recipe.inputFluid);
        }
        if (recipe.outputFluid != null) {
            fluids.init(1, false, OUT_FLUID_X, SLOTS_Y, 16, 16, recipe.outputFluid.amount, false, null);
            fluids.setBackground(1, fluidSlot);
            fluids.set(1, recipe.outputFluid);
        }
        if (!recipe.inputItems.isEmpty()) {
            items.init(0, true, IN_ITEM_X - 1, SLOTS_Y - 1);
            items.setBackground(0, slot);
            items.set(0, recipe.inputItems);
        }
        if (!recipe.outputItem.isEmpty()) {
            items.init(1, false, OUT_ITEM_X - 1, SLOTS_Y - 1);
            items.setBackground(1, slot);
            items.set(1, recipe.outputItem);
        }
        fluids.addTooltipCallback((index, input, fluid, tooltip) -> {
            if (FluidPurity.isTrichlorosilane(fluid)) {
                int nines = Purity.nines(FluidPurity.impurity(fluid));
                tooltip.add(1, I18n.format("youranmod.jei.purity.nines", nines));
            }
        });
    }
}
