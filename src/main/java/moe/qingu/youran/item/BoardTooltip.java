package moe.qingu.youran.item;

import moe.qingu.youran.api.pcb.BoardNbt;
import moe.qingu.youran.api.pcb.RomContent;
import moe.qingu.youran.logic.litho.ChipModel;
import moe.qingu.youran.logic.pcb.BoardType;
import net.minecraft.client.resources.I18n;
import net.minecraft.client.util.ITooltipFlag;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;

import java.util.List;

/** 主板、汉卡的悬停说明：装了哪些芯片（按住 Shift 看每片的品质） */
@SideOnly(Side.CLIENT)
final class BoardTooltip {
    private BoardTooltip() {
    }

    static void lines(NBTTagCompound b, List<String> lines, ITooltipFlag flag) {
        BoardType t = BoardNbt.type(b);
        if (t == null) {
            return;
        }
        boolean detail = flag.isAdvanced() || net.minecraft.client.gui.GuiScreen.isShiftKeyDown();
        if (BoardNbt.cpu(t) != null) {
            lines.add(I18n.format(BoardNbt.hasCpu(b) ? "youranmod.board.part.yes" : "youranmod.board.part.no", I18n.format("youranmod.chip.model." + BoardNbt.cpu(t).id)));
            if (detail && BoardNbt.hasCpu(b)) {
                chip(b.getCompoundTag(BoardNbt.CPU), lines);
            }
        }
        if (BoardNbt.romMask(t) != null) {
            if (BoardNbt.hasRom(b)) {
                NBTTagCompound rom = b.getCompoundTag(BoardNbt.ROM);
                NBTTagCompound content = BoardNbt.romContent(b);
                String what = rom.getBoolean(BoardNbt.ROM_EPROM) ? I18n.format("youranmod.chip.model.2764")
                        : I18n.format("youranmod.chip.model." + BoardNbt.romMask(t).id);
                lines.add(I18n.format("youranmod.board.part.rom", what, content == null ? I18n.format("youranmod.eprom.blank")
                        : content.hasKey(RomContent.TAG_MASK) ? I18n.format("youranmod.board.rom.mask")
                        : I18n.format("youranmod.eprom.content", content.getInteger(RomContent.TAG_LEN),
                        String.format(java.util.Locale.ROOT, "%04X", content.getInteger(RomContent.TAG_SUM)))));
                if (detail) {
                    chip(rom, lines);
                }
            } else {
                lines.add(I18n.format("youranmod.board.part.no", I18n.format("youranmod.board.rom")));
            }
        }
        if (BoardNbt.ramCount(t) > 0) {
            lines.add(I18n.format("youranmod.board.part.ram", BoardNbt.ram(b), BoardNbt.ramCount(t)));
            if (detail) {
                NBTTagList l = b.getTagList(BoardNbt.RAM, 10);
                for (int i = 0; i < l.tagCount(); i++) {
                    chip(l.getCompoundTagAt(i), lines);
                }
            }
        }
        for (ChipModel m : BoardNbt.fonts(t)) {
            NBTTagList l = b.getTagList(BoardNbt.FONT, 10);
            NBTTagCompound found = null;
            for (int i = 0; i < l.tagCount(); i++) {
                if (m.id.equals(l.getCompoundTagAt(i).getString(ChipModel.TAG))) {
                    found = l.getCompoundTagAt(i);
                }
            }
            lines.add(I18n.format(found != null ? "youranmod.board.part.yes" : "youranmod.board.part.no", I18n.format("youranmod.chip.model." + m.id)));
            if (detail && found != null) {
                chip(found, lines);
            }
        }
        if (!detail) {
            lines.add(I18n.format("youranmod.board.shift"));
        }
    }

    /** 一片的简要：序列号、报废或最大频率 */
    private static void chip(NBTTagCompound c, List<String> lines) {
        String q = c.getBoolean(ChipNbt.SCRAPPED) ? I18n.format("youranmod.chip.scrap." + c.getString(ChipNbt.SCRAP_REASON))
                : I18n.format("youranmod.chip.max_freq", ItemChip.mhz(c.getInteger(ChipNbt.MAX_FREQ)));
        lines.add("  " + c.getString(ChipNbt.SERIAL) + "  " + q);
    }
}
