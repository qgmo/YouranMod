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

/**
 * IC2 太阳能蒸馏机：IC2 自己没有这台机器的 JEI 页，这里照它的界面画（同 IC2 其他机器的页：把机器界面的贴图裁出来当背景，
 * 左边横着的输入罐、右边竖着的输出罐，位置、大小同界面里的 {@code GuiSolarDestiller}）。
 * 只有装了 IC2 时才会被加载（用到的只是 IC2 的贴图，不引用它的类）。
 */
final class SolarDistillerCategory implements IRecipeCategory<FluidItemRecipe> {
    private static final ResourceLocation GUI = new ResourceLocation("ic2", "textures/gui/GUISolarDestiller.png");
    /**
     * 从界面贴图裁出来的一块（左上角与大小）：左边两格、输入罐、输出罐、右边两格。右边两格的最后 4 列另裁一小块：
     * 再往右的升级格（151 列起、43 行止）与这两格（135–153 列、63–99 行）列上重叠，一整块裁会带进升级格的下角。
     */
    private static final int CROP_X = 14;
    private static final int CROP_Y = 24;
    private static final int CROP_W = 136;
    private static final int CROP_H = 76;
    private static final int EXTRA_X = 150;
    private static final int EXTRA_Y = 63;
    private static final int EXTRA_W = 4;
    private static final int EXTRA_H = 37;
    private static final int WIDTH = 160;
    private static final int HEIGHT = CROP_H + FluidItemRecipe.NOTES_GAP + FluidItemRecipe.NOTE_LINES * FluidItemRecipe.NOTE_LINE_HEIGHT;
    /** 界面里两个罐子的位置与大小（输入罐横着 53×18，输出罐竖着 17×43） */
    private static final int IN_X = 37;
    private static final int IN_Y = 43;
    private static final int IN_W = 53;
    private static final int IN_H = 18;
    private static final int OUT_X = 115;
    private static final int OUT_Y = 55;
    private static final int OUT_W = 17;
    private static final int OUT_H = 43;

    private final String uid;
    private final CropBackground background;
    @Nullable
    private final IDrawable icon;

    SolarDistillerCategory(IGuiHelper gui, String uid, ItemStack iconStack) {
        this.uid = uid;
        this.background = new CropBackground(gui.createDrawable(GUI, CROP_X, CROP_Y, CROP_W, CROP_H),
                gui.createDrawable(GUI, EXTRA_X, EXTRA_Y, EXTRA_W, EXTRA_H), EXTRA_X - CROP_X, EXTRA_Y - CROP_Y,
                EXTRA_X + EXTRA_W - CROP_X, WIDTH, HEIGHT);
        this.icon = iconStack.isEmpty() ? null : gui.createDrawableIngredient(iconStack);
    }

    @Override
    public String getUid() {
        return uid;
    }

    @Override
    public String getTitle() {
        return I18n.format("youranmod.jei.solar_distiller");
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
        // 机器的罐子是 10000 mB，配方才 1、10 mB，照实际容量画出来像空的；放大到 4 倍，同沉浸工程的 JEI 页的做法
        int capacity = Math.max(recipe.inputFluid == null ? 0 : recipe.inputFluid.amount,
                recipe.outputFluid == null ? 0 : recipe.outputFluid.amount) * 4;
        int dx = background.padX() - CROP_X;
        if (recipe.inputFluid != null) {
            fluids.init(0, true, IN_X + dx, IN_Y - CROP_Y, IN_W, IN_H, capacity, false, null);
            fluids.set(0, recipe.inputFluid);
        }
        if (recipe.outputFluid != null) {
            fluids.init(1, false, OUT_X + dx, OUT_Y - CROP_Y, OUT_W, OUT_H, capacity, false, null);
            fluids.set(1, recipe.outputFluid);
        }
    }
}
