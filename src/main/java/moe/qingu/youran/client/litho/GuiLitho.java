package moe.qingu.youran.client.litho;

import moe.qingu.youran.item.ItemLampPart;
import moe.qingu.youran.item.LampNbt;
import moe.qingu.youran.litho.ContainerLitho;
import moe.qingu.youran.litho.LithoStatus;
import moe.qingu.youran.logic.litho.LithoStep;
import moe.qingu.youran.net.LithoMessages;
import moe.qingu.youran.net.YouranNetwork;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.inventory.GuiContainer;
import net.minecraft.client.resources.I18n;
import net.minecraft.inventory.Slot;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraftforge.fluids.FluidStack;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;

import java.util.ArrayList;
import java.util.List;

/**
 * 光刻机主界面：五个罐（水蒸气、光刻胶、溶剂、刻蚀液、输出）与下面的容器格（放空桶等把罐倒出来），掩模槽、晶圆、辅料、外壳、芯片输出，
 * 电、工序进度、对准率、光源状态（只显示，没有灯槽）。“显微镜”按钮打开对准画面。
 */
@SideOnly(Side.CLIENT)
public class GuiLitho extends GuiContainer {
    static final String[] TANK_KEYS = {"steam", "resist", "solvent", "etchant", "output"};
    private final ContainerLitho container;

    public GuiLitho(ContainerLitho container) {
        super(container);
        this.container = container;
        xSize = ContainerLitho.WIDTH;
        ySize = ContainerLitho.HEIGHT;
    }

    @Override
    public void initGui() {
        super.initGui();
        buttonList.add(new GuiButton(0, guiLeft + 132, guiTop + 87, 100, 16, I18n.format("youranmod.litho.microscope")));
    }

    @Override
    protected void actionPerformed(GuiButton button) {
        if (button.id == 0) {
            YouranNetwork.CHANNEL.sendToServer(new LithoMessages.Action(container.windowId, LithoMessages.OPEN_MICROSCOPE, 0));
        }
    }

    @Override
    public void drawScreen(int mouseX, int mouseY, float partialTicks) {
        drawDefaultBackground();
        super.drawScreen(mouseX, mouseY, partialTicks);
        renderHoveredToolTip(mouseX, mouseY);
        NBTTagList tanks = container.data.getTagList("Tanks", 10);
        for (int i = 0; i < ContainerLitho.TANK_X.length; i++) {
            int x = guiLeft + ContainerLitho.TANK_X[i];
            int y = guiTop + ContainerLitho.TANK_Y;
            if (mouseX >= x && mouseX < x + 18 && mouseY >= y && mouseY < y + ContainerLitho.TANK_H) {
                List<String> lines = new ArrayList<>();
                lines.add(I18n.format("youranmod.litho.tank." + TANK_KEYS[i]));
                NBTTagCompound t = i < tanks.tagCount() ? tanks.getCompoundTagAt(i) : new NBTTagCompound();
                FluidStack f = FluidStack.loadFluidStackFromNBT(t);
                lines.add(f == null ? I18n.format("youranmod.litho.tank.empty", t.getInteger("Cap"))
                        : I18n.format("youranmod.litho.tank.amount", f.getLocalizedName(), f.amount, t.getInteger("Cap")));
                drawHoveringText(lines, mouseX, mouseY);
            }
        }
        int ex = guiLeft + 8;
        int ey = guiTop + 94;
        if (mouseX >= ex && mouseX < ex + 110 && mouseY >= ey && mouseY < ey + 6) {
            drawHoveringText(I18n.format("youranmod.litho.energy", container.data.getInteger("Energy"), container.data.getInteger("EnergyCap")),
                    mouseX, mouseY);
        }
    }

    @Override
    protected void drawGuiContainerBackgroundLayer(float partialTicks, int mouseX, int mouseY) {
        LithoDraw.panel(guiLeft, guiTop, xSize, ySize);
        for (Slot s : inventorySlots.inventorySlots) {
            LithoDraw.sunken(guiLeft + s.xPos - 1, guiTop + s.yPos - 1, 18, 18);
        }
        NBTTagList tanks = container.data.getTagList("Tanks", 10);
        for (int i = 0; i < ContainerLitho.TANK_X.length; i++) {
            int x = guiLeft + ContainerLitho.TANK_X[i] - 1;
            int y = guiTop + ContainerLitho.TANK_Y;
            LithoDraw.sunken(x, y, 18, ContainerLitho.TANK_H);
            NBTTagCompound t = i < tanks.tagCount() ? tanks.getCompoundTagAt(i) : new NBTTagCompound();
            FluidStack f = FluidStack.loadFluidStackFromNBT(t);
            int cap = Math.max(1, t.getInteger("Cap"));
            if (f != null) {
                int filled = Math.max(1, Math.round((ContainerLitho.TANK_H - 2) * Math.min(1F, f.amount / (float) cap)));
                LithoDraw.fluid(x + 1, y + 1, 16, ContainerLitho.TANK_H - 2, f, filled);
            }
        }
        int cap = Math.max(1, container.data.getInteger("EnergyCap"));
        int ew = Math.round(108 * Math.min(1F, container.data.getInteger("Energy") / (float) cap));
        LithoDraw.sunken(guiLeft + 8, guiTop + 94, 110, 6);
        drawRect(guiLeft + 9, guiTop + 95, guiLeft + 9 + ew, guiTop + 99, 0xFFD03030);
        int req = container.data.getInteger("Required");
        if (req > 0) {
            int pw = Math.round(98 * Math.min(1F, container.data.getInteger("Progress") / (float) req));
            LithoDraw.sunken(guiLeft + 132, guiTop + 106, 100, 6);
            drawRect(guiLeft + 133, guiTop + 107, guiLeft + 133 + pw, guiTop + 111, 0xFF40A040);
        }
    }

    @Override
    protected void drawGuiContainerForegroundLayer(int mouseX, int mouseY) {
        fontRenderer.drawString(I18n.format("youranmod.litho.title"), 8, 6, LithoDraw.TEXT);
        fontRenderer.drawString(I18n.format("youranmod.litho.slot.mask"), 132, 9, LithoDraw.TEXT, false);
        NBTTagCompound d = container.data;
        LithoStep step = LithoStep.byOrdinal(d.getInteger("Step"));
        int layers = d.getInteger("Layers");
        int y = 104;
        if (step == LithoStep.IDLE) {
            fontRenderer.drawString(I18n.format("youranmod.litho.step.idle"), 8, y, LithoDraw.TEXT);
        } else {
            String name = I18n.format("youranmod.litho.step." + step.name().toLowerCase(java.util.Locale.ROOT));
            String line = step.perLayer()
                    ? I18n.format("youranmod.litho.step.layer", step.stepNumber(d.getInteger("Layer"), layers), layers * 5 + 5, name,
                    d.getInteger("Layer") + 1, layers)
                    : I18n.format("youranmod.litho.step.once", step.stepNumber(d.getInteger("Layer"), layers), layers * 5 + 5, name);
            fontRenderer.drawString(line, 8, y, LithoDraw.TEXT);
        }
        y += 10;
        fontRenderer.drawString(I18n.format(LithoStatus.byOrdinal(d.getInteger("Status")).key()), 8, y, 0x2050A0);
        y += 10;
        fontRenderer.drawString(I18n.format(d.getBoolean("Aligned") ? "youranmod.litho.align.ok" : "youranmod.litho.align.no",
                d.getInteger("Rate") / 10.0), 8, y, LithoDraw.TEXT);
        y += 10;
        if (d.hasKey("Lamp")) {
            List<String> lamp = new ArrayList<>();
            ItemLampPart.describe(LampNbt.read(d.getCompoundTag("Lamp")), true, lamp);
            fontRenderer.drawString(I18n.format("youranmod.litho.lamp", lamp.get(0)), 8, y, LithoDraw.TEXT);
            y += 10;
            fontRenderer.drawString(lamp.get(1) + "  " + lamp.get(2), 8, y, LithoDraw.TEXT);
        } else {
            fontRenderer.drawString(I18n.format("youranmod.litho.lamp.none"), 8, y, 0xA02020);
            y += 10;
        }
        y += 10;
        fontRenderer.drawString(I18n.format("youranmod.litho.remainder", d.getInteger("BoronQ") / 4.0, d.getInteger("AlQ") / 4.0)
                + "  " + I18n.format(d.getBoolean("RsInverted") ? "youranmod.litho.redstone.inverted" : "youranmod.litho.redstone.normal"),
                8, y, 0x707070);
    }
}
