package moe.qingu.youran.api.pcb;

import moe.qingu.youran.logic.litho.ChipModel;
import moe.qingu.youran.logic.pcb.BoardType;
import net.minecraft.nbt.NBTTagCompound;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 主板分几次装芯片（服主 11-16 的配置：普通电脑 Z80 + 6 颗 4164 + 监控程序 ROM，个人电脑 8088 + 20 颗 + BIOS，汉卡四片字库 ROM） */
class BoardNbtTest {
    static BoardNbt.Part chip(ChipModel m, String serial) {
        NBTTagCompound t = new NBTTagCompound();
        t.setString(ChipModel.TAG, m.id);
        t.setString("Serial", serial);
        return new BoardNbt.Part(m, t);
    }

    static List<BoardNbt.Part> rams(int n) {
        List<BoardNbt.Part> l = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            l.add(chip(ChipModel.DRAM_4164, "4164-" + i));
        }
        return l;
    }

    @Test
    void computerInSteps() {
        NBTTagCompound b = BoardNbt.empty(BoardType.COMPUTER);
        assertFalse(BoardNbt.complete(b));
        b = BoardNbt.install(b, Collections.singletonList(chip(ChipModel.Z80, "Z80-1")));
        assertNotNull(b);
        assertNull(BoardNbt.install(b, Collections.singletonList(chip(ChipModel.Z80, "Z80-2"))), "CPU 只有一个座");
        assertNull(BoardNbt.install(b, Collections.singletonList(chip(ChipModel.I8088, "8088"))), "普通电脑不收 8088");
        b = BoardNbt.install(b, rams(5));
        assertNotNull(b);
        assertNull(BoardNbt.install(b, rams(2)), "只有 6 个内存座");
        b = BoardNbt.install(b, rams(1));
        assertEquals(6, BoardNbt.ram(b));
        assertFalse(BoardNbt.complete(b));
        assertNull(BoardNbt.install(b, Collections.singletonList(chip(ChipModel.ROM_BIOS, "BIOS"))), "BIOS 掩模 ROM 不进普通电脑的 ROM 座");
        b = BoardNbt.install(b, Collections.singletonList(chip(ChipModel.ROM_MONITOR, "MON")));
        assertTrue(BoardNbt.complete(b));
        assertTrue(RomContent.genuine(BoardNbt.romContent(b), BoardNbt.genuineRom(BoardType.COMPUTER), -1, null));
        assertEquals("Z80-1", b.getCompoundTag(BoardNbt.CPU).getString("Serial"));
    }

    /** EPROM：烧了什么就记什么，空白的也能装（开机时才发现不对） */
    @Test
    void epromRom() {
        NBTTagCompound b = BoardNbt.empty(BoardType.PC);
        NBTTagCompound content = RomContent.image(new byte[]{1, 2, 3});
        b = BoardNbt.install(b, Collections.singletonList(new BoardNbt.Part(ChipModel.EPROM_2764, new NBTTagCompound(), true, content, null)));
        assertNotNull(b);
        assertEquals(3, BoardNbt.romContent(b).getInteger(RomContent.TAG_LEN));
        assertTrue(b.getCompoundTag(BoardNbt.ROM).getBoolean(BoardNbt.ROM_EPROM));
        NBTTagCompound blank = BoardNbt.install(BoardNbt.empty(BoardType.PC),
                Collections.singletonList(new BoardNbt.Part(ChipModel.EPROM_2764, new NBTTagCompound(), true, null, null)));
        assertNotNull(blank);
        assertNull(BoardNbt.romContent(blank));
        b = BoardNbt.install(b, Collections.singletonList(chip(ChipModel.I8088, "8088")));
        for (int i = 0; i < 4; i++) {
            b = BoardNbt.install(b, rams(5));
        }
        assertTrue(BoardNbt.complete(b));
        assertNull(BoardNbt.install(b, rams(1)));
    }

    @Test
    void hanzi() {
        NBTTagCompound b = BoardNbt.empty(BoardType.HANZI);
        List<BoardNbt.Part> fonts = new ArrayList<>();
        for (ChipModel m : BoardNbt.fonts(BoardType.HANZI)) {
            fonts.add(chip(m, m.id));
        }
        b = BoardNbt.install(b, fonts);
        assertNotNull(b);
        assertTrue(BoardNbt.complete(b));
        assertNull(BoardNbt.install(BoardNbt.empty(BoardType.HANZI), java.util.Arrays.asList(chip(ChipModel.FONT_ROM_1, "a"), chip(ChipModel.FONT_ROM_1, "b"))),
                "同一片字库 ROM 不能装两片");
        assertNull(BoardNbt.install(BoardNbt.empty(BoardType.HANZI), rams(1)));
    }
}
