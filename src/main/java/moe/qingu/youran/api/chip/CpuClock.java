package moe.qingu.youran.api.chip;

import java.util.ArrayList;
import java.util.List;

/**
 * 调频（服主 11-15、11-17）：每型 CPU 自带标准频率与可调范围，玩家用电脑界面电源开关旁边的旋钮在范围里调，调了马上生效；
 * 实际频率被芯片的最大频率封顶（设得再高也上不去）；最大频率比标准频率低得多、又设得比最大频率高时，会出运行错误（{@link #faultRate}）。
 * 纯逻辑。频率单位 kHz。
 */
public final class CpuClock {
    private CpuClock() {
    }

    /**
     * 旋钮的各档（从小到大）：可调范围的两头、标准频率，加上范围里一格（{@code step}）的整数倍；离两头、标准频率不到半格的整数倍不要
     * （例：Z80 1.3、1.4 … 2.0、2.106、2.2 … 3.2 MHz；8088 4.773、5.0、5.25 … 8.0 MHz；YR-2000 1.6 … 2.6 GHz）。
     */
    public static int[] settings(int minKHz, int maxKHz, int stdKHz, int step) {
        int lo = Math.min(minKHz, stdKHz);
        int hi = Math.max(maxKHz, stdKHz);
        step = Math.max(1, step);
        List<Integer> l = new ArrayList<>();
        l.add(lo);
        for (long k = (lo / step + 1L) * step; k < hi; k += step) {
            int v = (int) k;
            if (near(v, lo, step) || near(v, hi, step) || near(v, stdKHz, step)) {
                continue;
            }
            if (stdKHz > l.get(l.size() - 1) && stdKHz < v) {
                l.add(stdKHz);
            }
            l.add(v);
        }
        if (stdKHz > l.get(l.size() - 1) && stdKHz < hi) {
            l.add(stdKHz);
        }
        if (hi > lo) {
            l.add(hi);
        }
        int[] out = new int[l.size()];
        for (int i = 0; i < out.length; i++) {
            out[i] = l.get(i);
        }
        return out;
    }

    public static int[] settings(CpuChip chip, ChipNumbers n) {
        return settings(chip.minKHz, chip.maxAdjKHz, chip.stdKHz, n.stepKHz(chip.model));
    }

    private static boolean near(int v, int at, int step) {
        return Math.abs(v - at) * 2 < step;
    }

    /** 设定值落到最近的一档（0 或没设过 = 标准频率） */
    public static int clamp(int settingKHz, int[] settings, int stdKHz) {
        if (settingKHz <= 0 || settings.length == 0) {
            return stdKHz;
        }
        int best = settings[0];
        for (int s : settings) {
            if (Math.abs((long) s - settingKHz) < Math.abs((long) best - settingKHz)) {
                best = s;
            }
        }
        return best;
    }

    /** 拧一格：{@code delta} 为正往高、为负往低，到头不动 */
    public static int turn(int settingKHz, int[] settings, int stdKHz, int delta) {
        int cur = clamp(settingKHz, settings, stdKHz);
        int i = 0;
        while (i < settings.length && settings[i] != cur) {
            i++;
        }
        i = Math.max(0, Math.min(settings.length - 1, i + delta));
        return settings[i];
    }

    /** 实际频率：设定值被最大频率封顶；芯片坏了为 0 */
    public static int actualKHz(CpuChip chip, int settingKHz) {
        if (chip.dead) {
            return 0;
        }
        return Math.max(0, Math.min(settingKHz, chip.maxKHz));
    }

    /** 设得比最大频率高、被封顶了 */
    public static boolean capped(CpuChip chip, int settingKHz) {
        return !chip.dead && settingKHz > chip.maxKHz;
    }

    /** 最大频率比标准频率低的比例（0 = 不比标准低） */
    public static double deficit(CpuChip chip) {
        if (chip.dead || chip.stdKHz <= 0) {
            return 0;
        }
        return Math.max(0, 1 - chip.maxKHz / (double) chip.stdKHz);
    }

    /** 出错的程度 x（0–1）：低的比例从 {@link ChipNumbers#faultDeficitStart} 到 {@link ChipNumbers#faultDeficitFull} 线性；没被封顶为 0 */
    public static double faultSeverity(CpuChip chip, int settingKHz, ChipNumbers n) {
        if (!capped(chip, settingKHz)) {
            return 0;
        }
        double d = deficit(chip);
        if (d <= n.faultDeficitStart) {
            return 0;
        }
        return Math.min(1, (d - n.faultDeficitStart) / (n.faultDeficitFull - n.faultDeficitStart));
    }

    /**
     * 运行错误率：CPU 每跑一秒指令平均出几次错 = {@link ChipNumbers#faultRateMax} × x²（x 见 {@link #faultSeverity}）。
     * 只在 CPU 真在跑指令时算（空转、等键时不出错）。
     */
    public static double faultRate(CpuChip chip, int settingKHz, ChipNumbers n) {
        double x = faultSeverity(chip, settingKHz, n);
        return n.faultRateMax * x * x;
    }

    /** 一次运行错误是死机的概率（其余是算错、乱写内存） */
    public static double crashShare(CpuChip chip, int settingKHz, ChipNumbers n) {
        double x = faultSeverity(chip, settingKHz, n);
        return x <= 0 ? 0 : n.crashShareMin + (n.crashShareMax - n.crashShareMin) * x;
    }

    /** 跑了 {@code busySeconds} 秒指令，至少出一次错的概率：1 − e^(−率 × 秒) */
    public static double faultChance(double rate, double busySeconds) {
        if (rate <= 0 || busySeconds <= 0) {
            return 0;
        }
        return 1 - Math.exp(-rate * busySeconds);
    }
}
