package moe.qingu.youran.logic.pcb;

import moe.qingu.youran.logic.litho.LampGlass;
import moe.qingu.youran.logic.purity.WaferMold;

/**
 * 烧录速度（服主 11-16：EPROM 小窗玻璃管的档次只影响烧录速度，不影响内容）。纯逻辑，数见 {@link PcbNumbers}。
 */
public final class EpromBurn {
    private EpromBurn() {
    }

    /** 烧 1 KB 要多少 tick：金铸模 / 粘土铸模玻璃管、石英管（1 个 9 起，每多一个 9 快一些，有下限） */
    public static int ticksPerKb(LampGlass glass, WaferMold mold, int nines) {
        if (glass != LampGlass.QUARTZ) {
            return mold == WaferMold.GOLD ? PcbNumbers.BURN_TICKS_PER_KB_GLASS_GOLD : PcbNumbers.BURN_TICKS_PER_KB_GLASS_CLAY;
        }
        double t = PcbNumbers.BURN_TICKS_PER_KB_QUARTZ_1 * Math.pow(PcbNumbers.BURN_QUARTZ_PER_NINE, Math.max(0, nines - 1));
        return Math.max(PcbNumbers.BURN_TICKS_PER_KB_MIN, (int) Math.round(t));
    }

    /** 烧 {@code bytes} 字节一共要多少 tick（至少 1） */
    public static int totalTicks(int ticksPerKb, int bytes) {
        return Math.max(1, (int) Math.ceil(ticksPerKb * (double) bytes / 1024.0));
    }

    /** 烧了 {@code ticks} tick 时写进去了多少字节 */
    public static int bytesDone(int ticksPerKb, int bytes, int ticks) {
        int total = totalTicks(ticksPerKb, bytes);
        return ticks >= total ? bytes : (int) ((long) bytes * ticks / total);
    }
}
