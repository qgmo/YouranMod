package moe.qingu.youran.client.confirm;

import moe.qingu.youran.api.network.DomainNames;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.FontRenderer;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.resources.I18n;

import java.util.ArrayList;
import java.util.List;

/** 一个系统确认框的内容与画法（客户端主线程） */
public final class ConfirmDialog {
    /** 按钮出现后多久才能点 */
    static final long ARM_MILLIS = 1000;
    static final int WIDTH = 260;

    final long id;
    final String title;
    /** 显示用的域名（混用文字系统的显示 xn-- 形式） */
    final String site;
    final List<String> items;
    final long shownAt;
    final long deadline;
    private final GuiButton approve = new GuiButton(1, 0, 0, 80, 20, "");
    private final GuiButton deny = new GuiButton(2, 0, 0, 80, 20, "");

    ConfirmDialog(long id, String title, String site, List<String> items, int seconds, long now) {
        this.id = id;
        this.title = title;
        this.site = DomainNames.toDisplay(site);
        this.items = new ArrayList<>(items);
        this.shownAt = now;
        this.deadline = now + Math.max(1, seconds) * 1000L;
    }

    public String title() {
        return title;
    }

    public String site() {
        return site;
    }

    boolean expired(long now) {
        return now >= deadline;
    }

    boolean armed(long now) {
        return now - shownAt >= ARM_MILLIS;
    }

    private List<String> lines(FontRenderer f) {
        List<String> out = new ArrayList<>();
        out.add("§l" + title);
        out.add("");
        out.addAll(f.listFormattedStringToWidth(I18n.format("youranmod.confirm.site", "§1" + site + "§r"), WIDTH - 16));
        if (!items.isEmpty()) {
            out.add(I18n.format("youranmod.confirm.items"));
            for (String i : items) {
                List<String> w = f.listFormattedStringToWidth(i, WIDTH - 28);
                for (int k = 0; k < w.size(); k++) {
                    out.add((k == 0 ? " • " : "    ") + w.get(k));
                }
            }
        }
        return out;
    }

    /** 按钮那一行相对框顶的位置：标题栏 20 + 正文 + 倒计时、说明两行 */
    private int buttonsOffset(FontRenderer f) {
        return 20 + lines(f).size() * 10 + 24;
    }

    private int height(FontRenderer f) {
        return buttonsOffset(f) + 20 + 8;
    }

    /** 画（盖住整个界面：先压暗下面的，再画框） */
    void draw(Minecraft mc, int screenW, int screenH, int mouseX, int mouseY) {
        FontRenderer f = mc.fontRenderer;
        GlStateManager.pushMatrix();
        GlStateManager.translate(0, 0, 500);
        Gui.drawRect(0, 0, screenW, screenH, 0xA0000000);
        int h = height(f);
        int x = (screenW - WIDTH) / 2;
        int y = Math.max(4, (screenH - h) / 2);
        // 系统样式：深色标题栏 + 浅灰面板 + 粗边框，和网页、电脑屏幕都不一样
        Gui.drawRect(x - 2, y - 2, x + WIDTH + 2, y + h + 2, 0xFF101010);
        Gui.drawRect(x, y, x + WIDTH, y + h, 0xFFE8E8E8);
        Gui.drawRect(x, y, x + WIDTH, y + 14, 0xFF2B4F81);
        f.drawString(I18n.format("youranmod.confirm.header"), x + 6, y + 3, 0xFFFFFFFF);
        int ty = y + 20;
        for (String line : lines(f)) {
            f.drawString(line, x + 8, ty, 0xFF202020);
            ty += 10;
        }
        long now = System.currentTimeMillis();
        long left = Math.max(0, (deadline - now + 999) / 1000);
        f.drawString(I18n.format("youranmod.confirm.countdown", left), x + 8, ty + 2, 0xFF7A1F1F);
        ty += 12;
        f.drawString(I18n.format("youranmod.confirm.note"), x + 8, ty, 0xFF606060);
        layout(x, y + buttonsOffset(f));
        boolean armed = armed(now);
        approve.displayString = I18n.format("youranmod.confirm.approve");
        deny.displayString = I18n.format("youranmod.confirm.deny");
        approve.enabled = armed;
        deny.enabled = armed;
        GlStateManager.color(1, 1, 1, 1);
        approve.drawButton(mc, mouseX, mouseY, 0);
        deny.drawButton(mc, mouseX, mouseY, 0);
        GlStateManager.popMatrix();
    }

    private void layout(int x, int buttonsY) {
        approve.x = x + WIDTH / 2 - 86;
        approve.y = buttonsY;
        deny.x = x + WIDTH / 2 + 6;
        deny.y = buttonsY;
    }

    /** 点了哪里：同意 true、拒绝 false、别处 null（还不能点时也是 null） */
    Boolean click(Minecraft mc, int screenW, int screenH, int mouseX, int mouseY) {
        if (!armed(System.currentTimeMillis())) {
            return null;
        }
        int h = height(mc.fontRenderer);
        int x = (screenW - WIDTH) / 2;
        int y = Math.max(4, (screenH - h) / 2);
        layout(x, y + buttonsOffset(mc.fontRenderer));
        if (approve.mousePressed(mc, mouseX, mouseY)) {
            return true;
        }
        if (deny.mousePressed(mc, mouseX, mouseY)) {
            return false;
        }
        return null;
    }
}
