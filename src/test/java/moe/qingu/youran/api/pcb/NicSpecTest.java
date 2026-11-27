package moe.qingu.youran.api.pcb;

import moe.qingu.youran.item.ChipNbt;
import moe.qingu.youran.item.CreativeStandards;
import moe.qingu.youran.logic.litho.ChipModel;
import moe.qingu.youran.logic.pcb.BoardType;
import net.minecraft.nbt.NBTTagCompound;
import org.junit.jupiter.api.Test;

import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 网卡的规格（计划 3.2，服主 11-27 选 B）：芯片品质 q = 最大频率 ÷ 额定频率决定同时连接数与缓冲区；规格不存 NBT，现算 */
class NicSpecTest {
    private static final int RATED = ChipModel.NIC_CTRL.ratedKHz;

    /** 表与 079 服务的 netcard.SpecFor 一致：1.30 / 1.10 / 0.90 三条线，线上算高的一档 */
    @Test
    void tableMatchesFirmware() {
        assertEquals(20000, RATED, "额定时钟 20 MHz（计划 3.1）");
        assertEquals(new NicSpec(8, 4096, 4096), NicSpec.forFrequency(26000, RATED));
        assertEquals(new NicSpec(6, 2048, 2048), NicSpec.forFrequency(25999, RATED));
        assertEquals(new NicSpec(6, 2048, 2048), NicSpec.forFrequency(22000, RATED));
        assertEquals(new NicSpec(4, 2048, 2048), NicSpec.forFrequency(21999, RATED));
        assertEquals(new NicSpec(4, 2048, 2048), NicSpec.forFrequency(18000, RATED));
        assertEquals(new NicSpec(2, 1024, 1024), NicSpec.forFrequency(17999, RATED));
        assertEquals(new NicSpec(2, 1024, 1024), NicSpec.forFrequency(15000, RATED), "0.75");
        assertEquals(new NicSpec(2, 1024, 1024), NicSpec.forFrequency(1, RATED), "不到 0.75 的（指标有浮动）也是最低一档");
        assertEquals(new NicSpec(8, 4096, 4096), NicSpec.forFrequency(40000, RATED), "超过 1.5 的也是最高一档");
        assertEquals(NicSpec.lowest(), NicSpec.forFrequency(0, RATED));
    }

    /** 规格都在卡能做到的范围里（079 那边 Spec.Check：连接 1–8，缓冲 256–16384） */
    @Test
    void withinFirmwareLimits() {
        for (int f = 0; f <= 40000; f += 250) {
            NicSpec s = NicSpec.forFrequency(f, RATED);
            assertTrue(s.conns >= 1 && s.conns <= 8, s.toString());
            assertTrue(s.rxBytes >= 256 && s.rxBytes <= 16384 && s.txBytes >= 256 && s.txBytes <= 16384, s.toString());
        }
    }

    private static NBTTagCompound chip(int maxFreq, boolean scrapped) {
        NBTTagCompound t = new NBTTagCompound();
        t.setString(ChipModel.TAG, ChipModel.NIC_CTRL.id);
        t.setString(ChipNbt.SERIAL, "NIC_CTRL-1");
        t.setBoolean(ChipNbt.SCRAPPED, scrapped);
        if (!scrapped) {
            t.setInteger(ChipNbt.MAX_FREQ, maxFreq);
        }
        return t;
    }

    private static NBTTagCompound card(NBTTagCompound chip) {
        return BoardNbt.install(BoardNbt.empty(BoardType.ISA), Collections.singletonList(new BoardNbt.Part(ChipModel.NIC_CTRL, chip)));
    }

    /** 板子上的芯片现算；没装芯片、芯片报废的卡不能当网卡用（null） */
    @Test
    void fromBoard() {
        assertEquals(new NicSpec(4, 2048, 2048), NicSpec.of(card(chip(19000, false))));
        assertEquals(new NicSpec(8, 4096, 4096), NicSpec.of(card(chip(28000, false))));
        assertNull(NicSpec.of(card(chip(0, true))), "报废的芯片");
        assertNull(NicSpec.of(BoardNbt.empty(BoardType.ISA)), "没装芯片");
        assertNull(NicSpec.of(BoardNbt.empty(BoardType.HANZI)), "不是 ISA 扩展卡");
        assertNull(NicSpec.of(null));
    }

    /** 创造物品栏的标准网卡（q = 1.3）是最高一档 */
    @Test
    void creativeStandardIsTopTier() {
        NBTTagCompound b = CreativeStandards.card(BoardType.ISA);
        assertTrue(BoardNbt.complete(b));
        assertEquals(new NicSpec(8, 4096, 4096), NicSpec.of(b));
    }

    /** ISA 扩展卡只装一颗控制芯片：别的芯片、第二颗都装不上；装好的数据带着型号 */
    @Test
    void isaBoardTakesOneController() {
        NBTTagCompound b = card(chip(20000, false));
        assertNotNull(b);
        assertTrue(BoardNbt.complete(b));
        assertEquals(ChipModel.NIC_CTRL, BoardNbt.controllerModel(b));
        assertEquals("isa", b.getString(BoardNbt.TYPE));
        assertFalse(BoardNbt.complete(BoardNbt.empty(BoardType.ISA)));
        assertNull(BoardNbt.install(b, Collections.singletonList(new BoardNbt.Part(ChipModel.NIC_CTRL, chip(20000, false)))), "已经有一颗了");
        assertNull(BoardNbt.install(BoardNbt.empty(BoardType.ISA), BoardNbtTest.rams(1)), "不收内存");
        assertNull(BoardNbt.install(BoardNbt.empty(BoardType.ISA), Collections.singletonList(BoardNbtTest.chip(ChipModel.I8088, "x"))), "不收 CPU");
        assertNull(BoardNbt.install(BoardNbt.empty(BoardType.PC), Collections.singletonList(new BoardNbt.Part(ChipModel.NIC_CTRL, chip(20000, false)))),
                "主板不收网卡控制芯片");
    }
}
