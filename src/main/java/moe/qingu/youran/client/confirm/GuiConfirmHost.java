package moe.qingu.youran.client.confirm;

import net.minecraft.client.gui.GuiScreen;
import org.lwjgl.input.Keyboard;

/** 没开别的界面时放系统确认框的界面（只有这个框；关不掉，只能回应，Esc 等于拒绝） */
final class GuiConfirmHost extends GuiScreen {
    @Override
    public void drawScreen(int mouseX, int mouseY, float partialTicks) {
        ConfirmDialog d = ClientConfirms.current();
        if (d != null) {
            d.draw(mc, width, height, mouseX, mouseY);
        }
    }

    @Override
    protected void mouseClicked(int mouseX, int mouseY, int button) {
        ConfirmDialog d = ClientConfirms.current();
        if (d != null && button == 0) {
            Boolean choice = d.click(mc, width, height, mouseX, mouseY);
            if (choice != null) {
                ClientConfirms.answer(d, choice);
            }
        }
    }

    @Override
    protected void keyTyped(char typedChar, int keyCode) {
        ConfirmDialog d = ClientConfirms.current();
        if (keyCode == Keyboard.KEY_ESCAPE && d != null) {
            ClientConfirms.answer(d, false);
        }
    }

    @Override
    public boolean doesGuiPauseGame() {
        return false;
    }
}
