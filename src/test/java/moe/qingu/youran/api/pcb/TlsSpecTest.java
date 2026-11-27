package moe.qingu.youran.api.pcb;

import moe.qingu.youran.item.ChipNbt;
import moe.qingu.youran.item.CreativeStandards;
import moe.qingu.youran.logic.litho.ChipModel;
import moe.qingu.youran.logic.pcb.BoardType;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import org.junit.jupiter.api.Test;

import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 加密卡的规格（计划 3.3）：加密协处理器的品质 q = 最大频率 ÷ 额定频率决定每秒握手数、同时会话数、加解密吞吐；规格不存 NBT，现算 */
class TlsSpecTest {
    private static final int RATED = ChipModel.TLS_COPROC.ratedKHz;

    /** 表与 079 服务的 tlscard.SpecFor 一致：1.30 / 1.10 / 0.90 三条线，线上算高的一档 */
    @Test
    void tableMatchesFirmware() {
        assertEquals(16000, RATED, "额定时钟 16 MHz（计划 3.1）");
        assertEquals(new TlsSpec(8, 8, 131072), TlsSpec.forFrequency(20800, RATED));
        assertEquals(new TlsSpec(4, 6, 65536), TlsSpec.forFrequency(20799, RATED));
        assertEquals(new TlsSpec(4, 6, 65536), TlsSpec.forFrequency(17600, RATED));
        assertEquals(new TlsSpec(2, 4, 32768), TlsSpec.forFrequency(17599, RATED));
        assertEquals(new TlsSpec(2, 4, 32768), TlsSpec.forFrequency(14400, RATED));
        assertEquals(new TlsSpec(1, 2, 16384), TlsSpec.forFrequency(14399, RATED));
        assertEquals(new TlsSpec(1, 2, 16384), TlsSpec.forFrequency(12000, RATED), "0.75");
        assertEquals(TlsSpec.lowest(), TlsSpec.forFrequency(0, RATED));
        assertEquals(new TlsSpec(8, 8, 131072), TlsSpec.forFrequency(40000, RATED));
    }

    /** 规格都在卡能做到的范围里（079 那边 Spec.Check：握手 1–64，会话 1–8，吞吐 1 KB–1 MB 每秒） */
    @Test
    void withinFirmwareLimits() {
        for (int f = 0; f <= 40000; f += 250) {
            TlsSpec s = TlsSpec.forFrequency(f, RATED);
            assertTrue(s.handshakes >= 1 && s.handshakes <= 64 && s.sessions >= 1 && s.sessions <= 8, s.toString());
            assertTrue(s.bytesPerSecond >= 1024 && s.bytesPerSecond <= 1 << 20, s.toString());
        }
    }

    private static NBTTagCompound chip(int maxFreq, boolean scrapped) {
        NBTTagCompound t = new NBTTagCompound();
        t.setString(ChipModel.TAG, ChipModel.TLS_COPROC.id);
        t.setString(ChipNbt.SERIAL, "TLS_COPROC-1");
        t.setBoolean(ChipNbt.SCRAPPED, scrapped);
        if (!scrapped) {
            t.setInteger(ChipNbt.MAX_FREQ, maxFreq);
        }
        return t;
    }

    private static NBTTagCompound card(NBTTagCompound chip) {
        return BoardNbt.install(BoardNbt.empty(BoardType.ISA), Collections.singletonList(new BoardNbt.Part(ChipModel.TLS_COPROC, chip)));
    }

    /** 同一种 ISA 扩展卡光板装加密协处理器就是加密卡：规格现算；装网卡控制芯片的、没装芯片、芯片报废的不能当加密卡用 */
    @Test
    void fromBoard() {
        NBTTagCompound b = card(chip(15000, false));
        assertNotNull(b);
        assertTrue(BoardNbt.complete(b));
        assertEquals(ChipModel.TLS_COPROC, BoardNbt.controllerModel(b));
        assertEquals(new TlsSpec(2, 4, 32768), TlsSpec.of(b));
        assertNull(NicSpec.of(b), "加密卡不是网卡");
        assertNull(TlsSpec.of(CreativeStandards.card(BoardType.ISA)), "网卡不是加密卡");
        assertNull(TlsSpec.of(card(chip(0, true))), "报废的芯片");
        assertNull(TlsSpec.of(BoardNbt.empty(BoardType.ISA)));
        assertNull(TlsSpec.of(null));
        assertNull(BoardNbt.install(b, Collections.singletonList(new BoardNbt.Part(ChipModel.NIC_CTRL, chip(15000, false)))), "一块卡只装一颗控制芯片");
    }

    /** 创造物品栏的标准加密卡（q = 1.3）是最高一档 */
    @Test
    void creativeStandardIsTopTier() {
        NBTTagCompound b = CreativeStandards.card(BoardType.ISA, ChipModel.TLS_COPROC);
        assertTrue(BoardNbt.complete(b));
        assertEquals(new TlsSpec(8, 8, 131072), TlsSpec.of(b));
    }

    /** 卡号在 NBT 的 CardId（没分配时 0） */
    @Test
    void cardIdKey() {
        assertEquals("CardId", TlsSpec.CARD_ID);
        assertEquals(0, TlsSpec.cardId(ItemStack.EMPTY));
    }
}
