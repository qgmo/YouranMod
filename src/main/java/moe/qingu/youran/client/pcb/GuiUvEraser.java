package moe.qingu.youran.client.pcb;

import moe.qingu.youran.logic.pcb.PcbNumbers;
import moe.qingu.youran.pcb.ContainerUvEraser;
import moe.qingu.youran.pcb.TileUvEraser;
import net.minecraft.client.gui.inventory.GuiContainer;
import net.minecraft.client.resources.I18n;
import net.minecraft.inventory.Slot;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;

/** 紫外擦除器的界面：左边汞灯，中间工作格（EPROM 或涂胶主板）与底片格，右边进度、电量、状态 */
@SideOnly(Side.CLIENT)
public class GuiUvEraser extends GuiContainer {
    private final ContainerUvEraser container;

    public GuiUvEraser(ContainerUvEraser container) {
        super(container);
        this.container = container;
        xSize = 176;
        ySize = 166;
    }

    @Override
    public void drawScreen(int mouseX, int mouseY, float partialTicks) {
        drawDefaultBackground();
        super.drawScreen(mouseX, mouseY, partialTicks);
        int ex = guiLeft + 160;
        int ey = guiTop + 18;
        if (mouseX >= ex && mouseX < ex + 8 && mouseY >= ey && mouseY < ey + 50) {
            drawHoveringText(I18n.format("youranmod.uv_eraser.energy", container.value(2), PcbNumbers.UV_ENERGY_CAPACITY), mouseX, mouseY);
        } else {
            renderHoveredToolTip(mouseX, mouseY);
        }
    }

    @Override
    protected void drawGuiContainerBackgroundLayer(float partialTicks, int mouseX, int mouseY) {
        int x = guiLeft;
        int y = guiTop;
        drawRect(x, y, x + xSize, y + ySize, 0xFF8B8B8B);
        drawRect(x + 1, y + 1, x + xSize - 1, y + ySize - 1, 0xFFC6C6C6);
        for (Slot slot : inventorySlots.inventorySlots) {
            int sx = x + slot.xPos;
            int sy = y + slot.yPos;
            drawRect(sx - 1, sy - 1, sx + 17, sy + 17, 0xFF373737);
            drawRect(sx, sy, sx + 17, sy + 17, 0xFFFFFFFF);
            drawRect(sx, sy, sx + 16, sy + 16, 0xFF8B8B8B);
        }
        // 灯与工作格之间：亮着时画一道紫光
        boolean lit = container.value(0) == TileUvEraser.Status.WORKING.ordinal();
        drawRect(x + 46, y + 42, x + 76, y + 46, lit ? 0xFFB070FF : 0xFF6E6E76);
        // 进度条
        int px = x + 104;
        int py = y + 30;
        drawRect(px, py, px + 50, py + 8, 0xFF373737);
        drawRect(px + 1, py + 1, px + 1 + 48 * Math.min(1000, container.value(1)) / 1000, py + 7, 0xFF9060E0);
        // 电量
        int ex = x + 160;
        int ey = y + 18;
        drawRect(ex, ey, ex + 8, ey + 50, 0xFF373737);
        int h = 48 * Math.min(PcbNumbers.UV_ENERGY_CAPACITY, container.value(2)) / PcbNumbers.UV_ENERGY_CAPACITY;
        drawRect(ex + 1, ey + 49 - h, ex + 7, ey + 49, 0xFFD02020);
    }

    @Override
    protected void drawGuiContainerForegroundLayer(int mouseX, int mouseY) {
        fontRenderer.drawString(I18n.format("tile.uv_eraser.name"), 8, 6, 0x404040);
        fontRenderer.drawString(I18n.format("youranmod.uv_eraser.lamp"), 8, 56, 0x404040);
        fontRenderer.drawString(I18n.format("youranmod.uv_eraser.film"), 100, 54, 0x404040);
        TileUvEraser.Status[] all = TileUvEraser.Status.values();
        int s = container.value(0);
        String status = I18n.format("youranmod.uv_eraser.status." + (s >= 0 && s < all.length ? all[s] : TileUvEraser.Status.IDLE)
                .name().toLowerCase(java.util.Locale.ROOT));
        fontRenderer.drawString(status, 104, 18, 0x404040);
        fontRenderer.drawString(container.value(1) / 10 + "%", 104, 42, 0x404040);
        fontRenderer.drawString(I18n.format("container.inventory"), 8, ySize - 92, 0x404040);
    }
}
