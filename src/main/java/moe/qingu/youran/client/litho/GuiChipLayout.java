package moe.qingu.youran.client.litho;

import moe.qingu.youran.logic.litho.ChipModel;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.resources.I18n;
import net.minecraft.util.ResourceLocation;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;

/**
 * 芯片设计图 / 掩模的版图画面（服主 11-15：右键可以欣赏，没有实际作用）。版图是自己画的风格化像素版图
 * （{@code textures/gui/layout_<型号>.png}，128 × 128），掩模看的是铬版上的样子（{@code mask_<型号>.png}）。
 */
@SideOnly(Side.CLIENT)
public class GuiChipLayout extends GuiScreen {
    private final ChipModel model;
    private final boolean mask;

    public GuiChipLayout(ChipModel model, boolean mask) {
        this.model = model;
        this.mask = mask;
    }

    @Override
    public void drawScreen(int mouseX, int mouseY, float partialTicks) {
        drawDefaultBackground();
        int size = Math.min(256, Math.min(width - 20, height - 40));
        int x = (width - size) / 2;
        int y = (height - size) / 2 + 6;
        drawRect(x - 3, y - 3, x + size + 3, y + size + 3, 0xFF101010);
        GlStateManager.color(1F, 1F, 1F, 1F);
        mc.getTextureManager().bindTexture(new ResourceLocation("youranmod", "textures/gui/" + (mask ? "mask_" : "layout_") + model.id + ".png"));
        drawModalRectWithCustomSizedTexture(x, y, 0, 0, size, size, size, size);
        String title = I18n.format(mask ? "youranmod.layout.mask" : "youranmod.layout.design",
                I18n.format("youranmod.chip.model." + model.id), String.valueOf(model.nodeUm), model.layers);
        drawCenteredString(fontRenderer, title, width / 2, y - 14, 0xFFFFFF);
        super.drawScreen(mouseX, mouseY, partialTicks);
    }

    @Override
    public boolean doesGuiPauseGame() {
        return false;
    }
}
