package moe.qingu.youran.logic.litho;

import moe.qingu.youran.item.ChipNbt;
import net.minecraft.nbt.NBTTagCompound;
import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 芯片型号：4164 内存（服主 11-16）与两种 CPU 走同一套品质规则，只是没有标准频率、可调范围 */
class ChipModelTest {
    @Test
    void ids() {
        assertSame(ChipModel.DRAM_4164, ChipModel.byId("4164"));
        assertSame(ChipModel.Z80, ChipModel.byId("z80"));
        assertSame(ChipModel.I8088, ChipModel.byId("8088"));
        assertTrue(ChipModel.Z80.isCpu());
        assertFalse(ChipModel.DRAM_4164.isCpu());
    }

    /**
     * 型号按物品元数据区分（服主 11-16）：每型一个元数据，不重、定了不改；除了 DUV 一档的 YR-2000（0.13 μm，要 ArF 光源 + 改良正胶）
     * 与加密协处理器（2 μm，要正胶、石英汞灯；服主 11-27），都是负胶（2.5 μm）做得了的
     */
    @Test
    void metasAreStableAndUnique() {
        java.util.Set<Integer> seen = new java.util.HashSet<>();
        for (ChipModel m : ChipModel.values()) {
            assertTrue(seen.add(m.meta), "元数据重复：" + m);
            assertSame(m, ChipModel.byMeta(m.meta));
            assertTrue(m == ChipModel.YR2000 || m == ChipModel.TLS_COPROC || m.nodeUm >= 2.5, "负胶做不了：" + m);
        }
        assertEquals(10, ChipModel.YR2000.meta);
        assertEquals(11, ChipModel.ROM_LASER_ALIGN.meta);
        assertEquals(0, ChipModel.Z80.meta);
        assertEquals(1, ChipModel.I8088.meta);
        assertEquals(2, ChipModel.DRAM_4164.meta);
        assertEquals(3, ChipModel.ROM_MONITOR.meta);
        assertEquals(4, ChipModel.ROM_BIOS.meta);
        assertEquals(5, ChipModel.EPROM_2764.meta);
        assertEquals(9, ChipModel.FONT_ROM_4.meta);
        assertEquals(null, ChipModel.byMeta(99));
        assertEquals("font_rom", ChipModel.FONT_ROM_3.layoutId());
        assertEquals("2764", ChipModel.EPROM_2764.layoutId());
        assertTrue(ChipModel.EPROM_2764.isRom());
        assertEquals(250, ChipModel.accessNs(ChipModel.ROM_BIOS.ratedKHz));
        assertEquals(450, ChipModel.accessNs(ChipModel.ROM_MONITOR.ratedKHz));
    }

    /** 网卡控制芯片（2.3.1.0 第 1 期，服主 11-27 选 A）：元数据 12，3 μm、7 层（负胶做得了，同 8088），额定时钟 20 MHz，是控制芯片不是 CPU */
    @Test
    void nicController() {
        assertEquals(12, ChipModel.NIC_CTRL.meta);
        assertSame(ChipModel.NIC_CTRL, ChipModel.byMeta(12));
        assertSame(ChipModel.NIC_CTRL, ChipModel.byId("nic_ctrl"));
        assertEquals(3.0, ChipModel.NIC_CTRL.nodeUm);
        assertEquals(7, ChipModel.NIC_CTRL.layers);
        assertEquals(20000, ChipModel.NIC_CTRL.ratedKHz);
        assertTrue(ChipModel.NIC_CTRL.isController());
        assertFalse(ChipModel.NIC_CTRL.isCpu());
        assertFalse(ChipModel.NIC_CTRL.isRom());
        assertEquals("nic_ctrl", ChipModel.NIC_CTRL.layoutId());
    }

    /** 加密协处理器（2.3.1.0 第 4 期，服主 11-27 选 A）：元数据 13，2 μm、8 层（要正胶、石英汞灯），额定时钟 16 MHz，是控制芯片 */
    @Test
    void tlsCoprocessor() {
        assertEquals(13, ChipModel.TLS_COPROC.meta);
        assertSame(ChipModel.TLS_COPROC, ChipModel.byMeta(13));
        assertSame(ChipModel.TLS_COPROC, ChipModel.byId("tls_coproc"));
        assertEquals(2.0, ChipModel.TLS_COPROC.nodeUm);
        assertEquals(8, ChipModel.TLS_COPROC.layers);
        assertEquals(16000, ChipModel.TLS_COPROC.ratedKHz);
        assertTrue(ChipModel.TLS_COPROC.isController());
        assertFalse(ChipModel.TLS_COPROC.isCpu());
        assertEquals("tls_coproc", ChipModel.TLS_COPROC.layoutId());
    }

    /** 普通电脑网卡驱动 ROM（2.3.1.0 第 5 期，计划附录 26）：元数据 14，8 KB 掩模 ROM，3 μm、6 层（负胶做得了，同 BIOS ROM 那一类），250 ns */
    @Test
    void z80NetDriverRom() {
        assertEquals(14, ChipModel.ROM_Z80NET.meta);
        assertSame(ChipModel.ROM_Z80NET, ChipModel.byMeta(14));
        assertSame(ChipModel.ROM_Z80NET, ChipModel.byId("z80net_rom"));
        assertEquals(3.0, ChipModel.ROM_Z80NET.nodeUm);
        assertEquals(6, ChipModel.ROM_Z80NET.layers);
        assertTrue(ChipModel.ROM_Z80NET.isRom());
        assertEquals(250, ChipModel.accessNs(ChipModel.ROM_Z80NET.ratedKHz));
        assertEquals("z80net_rom", ChipModel.ROM_Z80NET.layoutId());
    }

    /** 额定 150 ns ≈ 6.67 MHz */
    @Test
    void ramRatedAccessTime() {
        assertEquals(150, ChipModel.accessNs(ChipModel.DRAM_4164.ratedKHz));
        assertEquals(0, ChipModel.accessNs(0));
    }

    /** 负胶做得了 4164，指标围绕额定频率 */
    @Test
    void ramMakeableWithNegativeResist() {
        ChipQuality.Inputs in = ChipQualityTest.good();
        in.model = ChipModel.DRAM_4164;
        assertTrue(ChipQuality.effectiveResolution(in) <= ChipModel.DRAM_4164.nodeUm);
        Random r = new Random(3);
        int ok = 0;
        for (int i = 0; i < 500; i++) {
            ChipQuality.Result res = ChipQuality.evaluate(in, r);
            if (!res.scrapped()) {
                ok++;
                assertTrue(res.maxKHz > ChipModel.DRAM_4164.ratedKHz * 0.6 && res.maxKHz < ChipModel.DRAM_4164.ratedKHz * 1.8);
            }
        }
        assertTrue(ok > 0);
    }

    /** 内存芯片的 NBT 只有最大频率，没有标准频率、可调范围；CPU 有 */
    @Test
    void ramNbtHasNoCpuFrequencies() {
        ChipQuality.Inputs in = ChipQualityTest.good();
        in.waferNines = 30;
        in.solvent = 1;
        in.alignPermille = 1000;
        in.lampBrightness = 1;
        in.model = ChipModel.DRAM_4164;
        Random r = new Random(1);
        ChipQuality.Result res = ChipQuality.evaluate(in, r);
        for (int i = 0; i < 100 && res.scrapped(); i++) {
            res = ChipQuality.evaluate(in, r);
        }
        assertFalse(res.scrapped());
        NBTTagCompound ram = ChipNbt.tag(ChipModel.DRAM_4164, "4164-0", res, new NBTTagCompound());
        assertEquals("4164", ram.getString(ChipModel.TAG));
        assertTrue(ram.hasKey(ChipNbt.MAX_FREQ));
        assertFalse(ram.hasKey(ChipNbt.STD_FREQ));
        assertFalse(ram.hasKey(ChipNbt.MIN_FREQ));
        assertFalse(ram.hasKey(ChipNbt.MAX_ADJ_FREQ));
        NBTTagCompound cpu = ChipNbt.tag(ChipModel.Z80, "Z80-0", res, new NBTTagCompound());
        assertEquals(ChipModel.Z80.ratedKHz, cpu.getInteger(ChipNbt.STD_FREQ));
        assertEquals(ChipModel.Z80.maxAdjustKHz, cpu.getInteger(ChipNbt.MAX_ADJ_FREQ));
    }
}
