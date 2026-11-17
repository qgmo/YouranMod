package moe.qingu.youran.api.chip;

import moe.qingu.youran.api.pcb.BoardNbt;
import moe.qingu.youran.item.ChipNbt;
import moe.qingu.youran.item.CreativeStandards;
import moe.qingu.youran.logic.litho.ChipModel;
import moe.qingu.youran.logic.pcb.BoardType;
import net.minecraft.nbt.NBTTagCompound;
import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** CPU 温度、60 秒积分与耐热性、寿命与过热的后果（服主 11-17） */
class CpuThermalTest {
    static final ChipNumbers N = ChipNumbers.DEFAULTS;
    /** 平原（0.8）：22 °C */
    static final double PLAINS = N.ambientK(0.8f);
    static final double DESERT = N.ambientK(2.0f);

    static double runMinutes(CpuThermal t, double ambient, double load, double ratio, int minutes) {
        for (int i = 0; i < minutes * 60; i++) {
            t.step(N, ambient, true, load, ratio);
        }
        return t.tempK();
    }

    @Test
    void ambientFromBiome() {
        assertEquals(295.15, PLAINS, 1e-4);
        assertEquals(325.15, DESERT, 1e-4);
    }

    @Test
    void heatsWithLoadAndFrequency() {
        CpuThermal idle = new CpuThermal();
        CpuThermal busy = new CpuThermal();
        CpuThermal fast = new CpuThermal();
        runMinutes(idle, PLAINS, 0, 1, 10);
        runMinutes(busy, PLAINS, 1, 1, 10);
        runMinutes(fast, PLAINS, 1, 1.5, 10);
        assertEquals(PLAINS + 8, idle.tempK(), 0.01);
        assertEquals(PLAINS + 35, busy.tempK(), 0.01);
        assertEquals(PLAINS + 52.5, fast.tempK(), 0.01);
        // 60 秒积分：到稳态时 = 60 × 温度
        assertEquals(60 * busy.tempK(), busy.integralKs(), 0.5);
    }

    @Test
    void coolsTowardAmbient() {
        CpuThermal t = new CpuThermal();
        runMinutes(t, PLAINS, 1, 1, 10);
        for (int i = 0; i < 30; i++) {
            t.step(N, PLAINS, false, 0, 0);
        }
        // 一个时间常数后剩 e^-1
        assertEquals(PLAINS + 35 / Math.E, t.tempK(), 0.05);
        t.coolFor(N, PLAINS, 3600);
        assertEquals(PLAINS, t.tempK(), 0.01);
        assertEquals(60 * PLAINS, t.integralKs(), 0.5);
    }

    @Test
    void standardChipOverheatsInDesertNotInPlains() {
        int tol = CreativeStandards.chip(ChipModel.Z80).getInteger(ChipNbt.HEAT);
        assertEquals(21150, tol);
        CpuThermal plains = new CpuThermal();
        runMinutes(plains, PLAINS, 1, 1.3, 10);
        assertFalse(plains.overheated(tol));
        CpuThermal desert = new CpuThermal();
        boolean tripped = false;
        for (int i = 0; i < 600 && !tripped; i++) {
            desert.step(N, DESERT, true, 1, 1);
            tripped = desert.overheated(tol);
        }
        assertTrue(tripped);
        assertEquals(1, desert.heatLevel(tol, DESERT), 0.01);
        // 给人看的热量：环境温度时 0，到耐热性时 1
        CpuThermal cold = new CpuThermal();
        cold.reset(PLAINS);
        assertEquals(0, cold.heatLevel(tol, PLAINS), 1e-9);
        cold.reset(tol / 60.0 - 0.001);
        assertEquals(1, cold.heatLevel(tol, PLAINS), 1e-3);
        assertEquals(1, cold.heatLevel(17000, PLAINS), 1e-9);
        // 沙漠里空转不过热
        CpuThermal idle = new CpuThermal();
        runMinutes(idle, DESERT, 0, 1, 10);
        assertFalse(idle.overheated(tol));
    }

    @Test
    void savesAndLoads() {
        CpuThermal t = new CpuThermal();
        for (int i = 0; i < 45; i++) {
            t.step(N, PLAINS, true, i % 2, 1);
        }
        NBTTagCompound tag = t.write(new NBTTagCompound());
        CpuThermal r = new CpuThermal();
        r.read(tag);
        assertEquals(t.tempK(), r.tempK(), 1e-9);
        assertEquals(t.integralKs(), r.integralKs(), 0.1);
        t.step(N, PLAINS, true, 1, 1);
        r.step(N, PLAINS, true, 1, 1);
        assertEquals(t.integralKs(), r.integralKs(), 0.1);
        CpuThermal none = new CpuThermal();
        none.read(new NBTTagCompound());
        assertTrue(none.unset());
    }

    static NBTTagCompound board() {
        NBTTagCompound b = BoardNbt.empty(BoardType.COMPUTER);
        b.setTag(BoardNbt.CPU, CreativeStandards.chip(ChipModel.Z80));
        return b;
    }

    @Test
    void lifetimeRunsOut() {
        NBTTagCompound b = board();
        long life = b.getCompoundTag(BoardNbt.CPU).getLong(ChipNbt.LIFETIME);
        assertFalse(ChipWear.addUsed(b, life - 1));
        assertFalse(CpuChip.of(b, ChipModel.Z80, N).dead);
        assertEquals(1, CpuChip.of(b, ChipModel.Z80, N).remainingS());
        assertTrue(ChipWear.addUsed(b, 1));
        NBTTagCompound c = b.getCompoundTag(BoardNbt.CPU);
        assertTrue(c.getBoolean(ChipNbt.SCRAPPED));
        assertEquals(ChipWear.REASON_WORN_OUT, c.getString(ChipNbt.SCRAP_REASON));
        assertTrue(CpuChip.of(b, ChipModel.Z80, N).dead);
        // 坏了以后不再记
        assertFalse(ChipWear.addUsed(b, 10));
        assertFalse(ChipWear.addUsed(null, 10));
    }

    @Test
    void overheatScrapsOrCostsLife() {
        Random always = new Random() {
            @Override
            public double nextDouble() {
                return 0;
            }
        };
        Random never = new Random() {
            @Override
            public double nextDouble() {
                return 0.99;
            }
        };
        NBTTagCompound b = board();
        long life = b.getCompoundTag(BoardNbt.CPU).getLong(ChipNbt.LIFETIME);
        // 标准芯片寿命 10500 开机小时（服主 11-17：几千到一万多），过热一次少 5%：525 小时
        assertEquals(10500L * 3600, life, 3600);
        assertEquals(ChipWear.Overheat.LIFE_LOST, ChipWear.overheat(b, N, never));
        assertEquals(Math.round(life * 0.05), b.getCompoundTag(BoardNbt.CPU).getLong(CpuChip.USED));
        assertEquals(ChipWear.Overheat.SCRAPPED, ChipWear.overheat(b, N, always));
        assertEquals(ChipWear.REASON_OVERHEAT, b.getCompoundTag(BoardNbt.CPU).getString(ChipNbt.SCRAP_REASON));
        assertEquals(ChipWear.Overheat.NONE, ChipWear.overheat(b, N, always));
        // 剩的寿命不够扣：用坏
        NBTTagCompound b2 = board();
        ChipWear.addUsed(b2, life - 10);
        assertEquals(ChipWear.Overheat.WORN_OUT, ChipWear.overheat(b2, N, never));
        assertEquals(ChipWear.Overheat.NONE, ChipWear.overheat(null, N, always));
    }
}
