package moe.qingu.youran.api.chip;

import moe.qingu.youran.api.pcb.BoardNbt;
import moe.qingu.youran.item.ChipNbt;
import moe.qingu.youran.item.CreativeStandards;
import moe.qingu.youran.logic.litho.ChipModel;
import moe.qingu.youran.logic.pcb.BoardType;
import net.minecraft.nbt.NBTTagCompound;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 调频旋钮的档、实际频率封顶、运行错误率（服主 11-17） */
class CpuClockTest {
    static final ChipNumbers N = ChipNumbers.DEFAULTS;

    static NBTTagCompound board(ChipModel m, int maxKHz) {
        NBTTagCompound chip = CreativeStandards.chip(m);
        chip.setInteger(ChipNbt.MAX_FREQ, maxKHz);
        NBTTagCompound b = BoardNbt.empty(m == ChipModel.Z80 ? BoardType.COMPUTER : m == ChipModel.I8088 ? BoardType.PC : BoardType.FURNITURE);
        b.setTag(BoardNbt.CPU, chip);
        return b;
    }

    @Test
    void settingsPerModel() {
        int[] z80 = CpuClock.settings(CpuChip.nominal(ChipModel.Z80, N), N);
        assertEquals(1300, z80[0]);
        assertEquals(3200, z80[z80.length - 1]);
        // 2.1 MHz 离标准 2.106 不到半格，不要；标准在 2.0 与 2.2 之间
        assertArrayEquals(new int[]{2000, 2106, 2200}, java.util.Arrays.copyOfRange(z80, 7, 10));
        assertEquals(20, z80.length);
        int[] pc = CpuClock.settings(CpuChip.nominal(ChipModel.I8088, N), N);
        assertEquals(4773, pc[0]);
        assertEquals(5000, pc[1]);
        assertEquals(8000, pc[pc.length - 1]);
        int[] yr = CpuClock.settings(CpuChip.nominal(ChipModel.YR2000, N), N);
        assertArrayEquals(new int[]{1_600_000, 1_700_000, 1_800_000, 1_900_000, 2_000_000, 2_100_000, 2_200_000, 2_300_000, 2_400_000,
                2_500_000, 2_600_000}, yr);
    }

    @Test
    void clampAndTurn() {
        int[] z80 = CpuClock.settings(CpuChip.nominal(ChipModel.Z80, N), N);
        assertEquals(2106, CpuClock.clamp(0, z80, 2106));
        assertEquals(3200, CpuClock.clamp(9999, z80, 2106));
        assertEquals(1300, CpuClock.clamp(1, z80, 2106));
        assertEquals(2200, CpuClock.turn(2106, z80, 2106, 1));
        assertEquals(2000, CpuClock.turn(2106, z80, 2106, -1));
        assertEquals(3200, CpuClock.turn(3200, z80, 2106, 5));
    }

    @Test
    void actualIsCappedByMaxFrequency() {
        CpuChip c = CpuChip.of(board(ChipModel.Z80, 1800), ChipModel.Z80, N);
        assertEquals(1800, CpuClock.actualKHz(c, 2106));
        assertTrue(CpuClock.capped(c, 2106));
        assertEquals(1500, CpuClock.actualKHz(c, 1500));
        assertFalse(CpuClock.capped(c, 1500));
        // 标称芯片（没有主板数据）：最大频率 = 标准频率，往上调不起作用
        CpuChip nominal = CpuChip.nominal(ChipModel.Z80, N);
        assertEquals(2106, CpuClock.actualKHz(nominal, 3200));
        assertEquals(0, CpuClock.faultRate(nominal, 3200, N));
    }

    @Test
    void faultRateFollowsDeficit() {
        // 最大频率只比标准低 5%：不出错
        CpuChip ok = CpuChip.of(board(ChipModel.Z80, 2000), ChipModel.Z80, N);
        assertEquals(0, CpuClock.faultRate(ok, 3200, N));
        // 低 17.5%：x = 0.5，率 0.25 / 秒；没超过最大频率时不出错
        CpuChip mid = CpuChip.of(board(ChipModel.Z80, (int) Math.round(2106 * 0.825)), ChipModel.Z80, N);
        assertEquals(0.25, CpuClock.faultRate(mid, 2106, N), 0.01);
        assertEquals(0, CpuClock.faultRate(mid, 1700, N));
        assertEquals(0.175, CpuClock.crashShare(mid, 2106, N), 0.01);
        // 低 25% 及以上：每秒一次、三成死机
        CpuChip worst = CpuChip.of(board(ChipModel.I8088, 3500), ChipModel.I8088, N);
        assertEquals(1.0, CpuClock.faultRate(worst, 4773, N), 1e-9);
        assertEquals(0.30, CpuClock.crashShare(worst, 4773, N), 1e-9);
        assertEquals(1 - Math.exp(-0.5), CpuClock.faultChance(1.0, 0.5), 1e-9);
    }

    @Test
    void deadChip() {
        NBTTagCompound b = board(ChipModel.Z80, 2000);
        b.getCompoundTag(BoardNbt.CPU).setBoolean(ChipNbt.SCRAPPED, true);
        CpuChip c = CpuChip.of(b, ChipModel.Z80, N);
        assertTrue(c.dead);
        assertEquals(0, CpuClock.actualKHz(c, 2106));
        assertTrue(CpuChip.of(BoardNbt.empty(BoardType.COMPUTER), ChipModel.Z80, N).dead);
    }
}
