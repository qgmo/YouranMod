package moe.qingu.youran.client.litho;

import moe.qingu.youran.litho.ContainerMicroscope;
import moe.qingu.youran.logic.litho.Alignment;
import moe.qingu.youran.logic.litho.LithoNumbers;
import moe.qingu.youran.net.LithoMessages;
import moe.qingu.youran.net.YouranNetwork;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.gui.inventory.GuiContainer;
import net.minecraft.client.resources.I18n;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;
import org.lwjgl.input.Keyboard;

import java.io.IOException;

/**
 * 对准显微镜（分视场）：左右两个圆形视野，各有晶圆上的十字（上一层留下的，深色实心）和掩模的十字（半透明的框）。
 * X、Y 平移两边一起动；θ 旋转让两边的十字往相反方向动。低倍看得全但不精，高倍精但视野小、十字容易跑出视野；
 * 旋钮有回差。只有操作者（先打开显微镜的人）能拧，旁人看同样的画面。
 *
 * <p>键盘：方向键 X / Y，Q / E 旋转，按住 Shift 细调，Tab 换倍率，回车完成对准。
 *
 * <p>激光对准（服主 11-16）：主界面插了个人电脑主板（激光对准控制程序）与激光源后，“激光对准”按钮在偏差进了捕获范围时可按；
 * 按不了时下面写原因（“偏差超出激光捕获范围”“控制程序不对”等）。收敛中、锁定后也在这里显示。
 */
@SideOnly(Side.CLIENT)
public class GuiMicroscope extends GuiContainer {
    static final int R = 54;
    static final int LEFT_CX = 70;
    static final int RIGHT_CX = 186;
    static final int CY = 76;
    /** 晶圆十字：臂半长、粗细（μm）；掩模的框比它宽一点 */
    static final double MARK_ARM = 30;
    static final double MARK_WIDTH = 2;
    static final double WINDOW_WIDTH = 4;

    private final ContainerMicroscope container;
    private boolean high;
    private boolean fine;

    public GuiMicroscope(ContainerMicroscope container) {
        super(container);
        this.container = container;
        xSize = 256;
        ySize = 240;
    }

    @Override
    public void initGui() {
        super.initGui();
        Keyboard.enableRepeatEvents(true);
        int y = guiTop + 146;
        int x = guiLeft + 8;
        buttonList.add(new GuiButton(0, x, y, 38, 18, "X-"));
        buttonList.add(new GuiButton(1, x + 40, y, 38, 18, "X+"));
        buttonList.add(new GuiButton(2, x + 82, y, 38, 18, "Y-"));
        buttonList.add(new GuiButton(3, x + 122, y, 38, 18, "Y+"));
        buttonList.add(new GuiButton(4, x + 164, y, 38, 18, "θ-"));
        buttonList.add(new GuiButton(5, x + 204, y, 38, 18, "θ+"));
        buttonList.add(new GuiButton(6, x, y + 22, 78, 18, ""));
        buttonList.add(new GuiButton(7, x + 82, y + 22, 78, 18, ""));
        buttonList.add(new GuiButton(8, x + 164, y + 22, 78, 18, I18n.format("youranmod.microscope.confirm")));
        buttonList.add(new GuiButton(9, x + 164, y + 44, 78, 18, I18n.format("youranmod.microscope.back")));
        buttonList.add(new GuiButton(10, x, y + 44, 160, 18, I18n.format("youranmod.microscope.laser")));
        updateLabels();
    }

    @Override
    public void onGuiClosed() {
        super.onGuiClosed();
        Keyboard.enableRepeatEvents(false);
    }

    private void updateLabels() {
        buttonList.get(6).displayString = I18n.format(fine ? "youranmod.microscope.fine" : "youranmod.microscope.coarse");
        buttonList.get(7).displayString = I18n.format(high ? "youranmod.microscope.high" : "youranmod.microscope.low");
        boolean op = container.data.getBoolean("Operator");
        for (int i = 0; i < 6; i++) {
            buttonList.get(i).enabled = op;
        }
        buttonList.get(8).enabled = op;
        NBTTagCompound laser = container.data.getCompoundTag("Laser");
        buttonList.get(10).enabled = op && laser.hasKey("Problem") && laser.getInteger("Problem") < 0
                && laser.getInteger("State") != moe.qingu.youran.logic.litho.LaserAlign.State.CONVERGING.ordinal();
    }

    private void knob(Alignment.Axis axis, int dir, boolean fineStep) {
        if (container.data.getBoolean("Operator")) {
            YouranNetwork.CHANNEL.sendToServer(new LithoMessages.Action(container.windowId, LithoMessages.KNOB,
                    LithoMessages.knobArg(axis, dir, fineStep)));
        }
    }

    @Override
    protected void actionPerformed(GuiButton b) {
        switch (b.id) {
            case 0: knob(Alignment.Axis.X, -1, fine); break;
            case 1: knob(Alignment.Axis.X, 1, fine); break;
            case 2: knob(Alignment.Axis.Y, -1, fine); break;
            case 3: knob(Alignment.Axis.Y, 1, fine); break;
            case 4: knob(Alignment.Axis.THETA, -1, fine); break;
            case 5: knob(Alignment.Axis.THETA, 1, fine); break;
            case 6: fine = !fine; break;
            case 7: high = !high; break;
            case 8:
                YouranNetwork.CHANNEL.sendToServer(new LithoMessages.Action(container.windowId, LithoMessages.CONFIRM, 0));
                break;
            case 9:
                YouranNetwork.CHANNEL.sendToServer(new LithoMessages.Action(container.windowId, LithoMessages.OPEN_MAIN, 0));
                break;
            case 10:
                YouranNetwork.CHANNEL.sendToServer(new LithoMessages.Action(container.windowId, LithoMessages.LASER_ALIGN, 0));
                break;
            default:
                break;
        }
        updateLabels();
    }

    @Override
    protected void keyTyped(char c, int key) throws IOException {
        boolean f = GuiScreen.isShiftKeyDown() || fine;
        switch (key) {
            case Keyboard.KEY_LEFT: knob(Alignment.Axis.X, -1, f); return;
            case Keyboard.KEY_RIGHT: knob(Alignment.Axis.X, 1, f); return;
            case Keyboard.KEY_UP: knob(Alignment.Axis.Y, -1, f); return;
            case Keyboard.KEY_DOWN: knob(Alignment.Axis.Y, 1, f); return;
            case Keyboard.KEY_Q: knob(Alignment.Axis.THETA, -1, f); return;
            case Keyboard.KEY_E: knob(Alignment.Axis.THETA, 1, f); return;
            case Keyboard.KEY_TAB:
                high = !high;
                updateLabels();
                return;
            case Keyboard.KEY_RETURN:
                actionPerformed(buttonList.get(8));
                return;
            default:
                super.keyTyped(c, key);
        }
    }

    @Override
    public void updateScreen() {
        super.updateScreen();
        updateLabels();
    }

    @Override
    public void drawScreen(int mouseX, int mouseY, float partialTicks) {
        drawDefaultBackground();
        super.drawScreen(mouseX, mouseY, partialTicks);
    }

    @Override
    protected void drawGuiContainerBackgroundLayer(float partialTicks, int mouseX, int mouseY) {
        LithoDraw.panel(guiLeft, guiTop, xSize, ySize);
        NBTTagCompound d = container.data;
        field(guiLeft + LEFT_CX, guiTop + CY, d.getDouble("LX"), d.getDouble("LY"));
        field(guiLeft + RIGHT_CX, guiTop + CY, d.getDouble("RX"), d.getDouble("RY"));
    }

    /** 一个视野：晶圆十字偏 (ex, ey) μm，掩模十字在正中 */
    private void field(int cx, int cy, double ex, double ey) {
        LithoDraw.circle(cx, cy, R + 3, 0xFF202020);
        // 黄光下的晶圆表面
        LithoDraw.circle(cx, cy, R, 0xFFB89A3C);
        double scale = R / (high ? LithoNumbers.FIELD_HIGH_UM : LithoNumbers.FIELD_LOW_UM);
        double wx = cx + ex * scale;
        double wy = cy + ey * scale;
        double arm = MARK_ARM * scale;
        double half = Math.max(0.5, MARK_WIDTH * scale / 2);
        int dark = 0xFF3A2A10;
        LithoDraw.rectInCircle(wx - arm, wy - half, wx + arm, wy + half, cx, cy, R, dark);
        LithoDraw.rectInCircle(wx - half, wy - arm, wx + half, wy + arm, cx, cy, R, dark);
        // 掩模十字：半透明的铬框，晶圆十字落在框正中就是对上了
        double win = Math.max(1, WINDOW_WIDTH * scale / 2);
        double armM = (MARK_ARM + 4) * scale;
        int chrome = 0x9030485A;
        double t = Math.max(1, 1.0 * scale);
        LithoDraw.rectInCircle(cx - armM, cy - win - t, cx + armM, cy - win, cx, cy, R, chrome);
        LithoDraw.rectInCircle(cx - armM, cy + win, cx + armM, cy + win + t, cx, cy, R, chrome);
        LithoDraw.rectInCircle(cx - win - t, cy - armM, cx - win, cy + armM, cx, cy, R, chrome);
        LithoDraw.rectInCircle(cx + win, cy - armM, cx + win + t, cy + armM, cx, cy, R, chrome);
    }

    /** 公差：0.1 μm 以下多写几位（YR-2000 是 ±0.065 μm） */
    static String tol(double um) {
        return um < 0.1 ? String.format(java.util.Locale.ROOT, "%.3f", um) : String.format(java.util.Locale.ROOT, "%.1f", um);
    }

    /** 激光对准一行：收敛中 / 已锁定 / 按不了的原因 */
    static String laserLine(NBTTagCompound l) {
        int state = l.getInteger("State");
        if (state == moe.qingu.youran.logic.litho.LaserAlign.State.CONVERGING.ordinal()) {
            return I18n.format("youranmod.laser_align.converging", l.getInteger("Progress") / 10);
        }
        if (state == moe.qingu.youran.logic.litho.LaserAlign.State.LOCKED.ordinal()) {
            return I18n.format("youranmod.laser_align.locked", moe.qingu.youran.item.ItemLaserCavity.gauge(l.getInteger("Strength") / 1000.0));
        }
        moe.qingu.youran.litho.LaserAligner.Problem p = moe.qingu.youran.litho.LaserAligner.Problem.byOrdinal(l.getInteger("Problem"));
        return p == null ? I18n.format("youranmod.laser_align.ready", moe.qingu.youran.item.ItemLaserCavity.gauge(l.getInteger("Strength") / 1000.0))
                : I18n.format(p.key());
    }

    @Override
    protected void drawGuiContainerForegroundLayer(int mouseX, int mouseY) {
        NBTTagCompound d = container.data;
        fontRenderer.drawString(I18n.format("youranmod.microscope.title"), 8, 6, LithoDraw.TEXT);
        fontRenderer.drawString(I18n.format("youranmod.microscope.left"), LEFT_CX - 12, CY + R + 4, LithoDraw.TEXT);
        fontRenderer.drawString(I18n.format("youranmod.microscope.right"), RIGHT_CX - 12, CY + R + 4, LithoDraw.TEXT);
        String who = d.getBoolean("Operator") ? I18n.format("youranmod.microscope.operator.you")
                : I18n.format("youranmod.microscope.operator.other", d.getString("OperatorName"));
        fontRenderer.drawString(who, xSize - 8 - fontRenderer.getStringWidth(who), 6, d.getBoolean("Operator") ? 0x206020 : 0xA04020);
        String rate = I18n.format(d.getBoolean("Aligned") ? "youranmod.microscope.rate.ok" : "youranmod.microscope.rate",
                d.getInteger("Rate") / 10.0, tol(d.getDouble("Tol")));
        fontRenderer.drawString(laserLine(d.getCompoundTag("Laser")), 8, 211, 0x2050A0);
        fontRenderer.drawString(rate, 8, 220, LithoDraw.TEXT);
        fontRenderer.drawString(I18n.format("youranmod.microscope.keys"), 8, 229, 0x707070);
    }
}
