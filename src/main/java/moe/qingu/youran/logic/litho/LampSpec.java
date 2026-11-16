package moe.qingu.youran.logic.litho;

import moe.qingu.youran.logic.purity.Purity;
import moe.qingu.youran.logic.purity.WaferMold;

/**
 * 汞灯的数据与公式（纯逻辑）。数据只存“原始量”：玻璃管种类、低档管的铸模、石英的几个 9、充的气体、已用量（曝光次数）、
 * 剩下的汞（mB）与“下一 mB 已经用了几次”；亮度、寿命都由这些现算（设计稿 3.3），不存两份。
 *
 * <p>两样东西会用完：
 * <ul>
 *     <li>灯本身（电极、管壁）：已用量到寿命就烧坏了，只能换灯；</li>
 *     <li>汞（服主 11-16）：每曝光 {@link LithoNumbers#MERCURY_EXPOSURES_PER_MB} 次用掉 1 mB，用完不亮；拿出来能补到 {@link LithoNumbers#LAMP_MERCURY} mB。</li>
 * </ul>
 */
public final class LampSpec {
    public final LampGlass glass;
    /** 低档玻璃管浇注用的铸模；石英管、没有记录时为 null（按粘土算） */
    public final WaferMold mold;
    /** 石英管的纯度（几个 9）；低档管不看 */
    public final int nines;
    /** 充的气体；还没组装成灯（只是玻璃管）时为 null，按氩算 */
    public final LampGas gas;
    /** 已用量：累计曝光次数（每曝光一层加 1） */
    public final int used;
    /** 剩下的汞（mB） */
    public final int mercury;
    /** 当前这 1 mB 汞已经用了几次曝光 */
    public final int mercuryWear;

    public LampSpec(LampGlass glass, WaferMold mold, int nines, LampGas gas, int used, int mercury, int mercuryWear) {
        this.glass = glass == null ? LampGlass.GLASS : glass;
        this.mold = this.glass == LampGlass.GLASS ? mold : null;
        this.nines = Purity.sanitize(nines);
        this.gas = gas;
        this.used = Math.max(0, used);
        this.mercury = Math.max(0, Math.min(LithoNumbers.LAMP_MERCURY, mercury));
        this.mercuryWear = Math.max(0, mercuryWear);
    }

    /** 只有玻璃管（还没组装） */
    public static LampSpec tube(LampGlass glass, WaferMold mold, int nines) {
        return new LampSpec(glass, mold, nines, null, 0, 0, 0);
    }

    /** 用这根玻璃管、这种气体新组装的灯（还没充汞） */
    public LampSpec assembled(LampGas withGas) {
        return new LampSpec(glass, mold, nines, withGas, 0, 0, 0);
    }

    /** 补汞到满 */
    public LampSpec refilled() {
        return new LampSpec(glass, mold, nines, gas, used, LithoNumbers.LAMP_MERCURY, mercuryWear);
    }

    /** 补 {@code mB} 汞（到满为止） */
    public LampSpec withMercury(int newMercury) {
        return new LampSpec(glass, mold, nines, gas, used, newMercury, mercuryWear);
    }

    /** 还能补多少 mB 汞 */
    public int mercurySpace() {
        return LithoNumbers.LAMP_MERCURY - mercury;
    }

    /** 曝光一次之后 */
    public LampSpec afterExposure() {
        int wear = mercuryWear + 1;
        int hg = mercury;
        if (wear >= LithoNumbers.MERCURY_EXPOSURES_PER_MB) {
            wear = 0;
            hg = Math.max(0, hg - 1);
        }
        return new LampSpec(glass, mold, nines, gas, used + 1, hg, wear);
    }

    int effectiveNines() {
        return Math.min(nines, LithoNumbers.QUARTZ_LAMP_MAX_NINES);
    }

    boolean xenon() {
        return gas == LampGas.XENON;
    }

    /** 新灯的亮度（千分比） */
    public int baseBrightness() {
        int b;
        if (glass == LampGlass.GLASS) {
            b = mold == WaferMold.GOLD ? LithoNumbers.GLASS_LAMP_BRIGHTNESS_GOLD : LithoNumbers.GLASS_LAMP_BRIGHTNESS_CLAY;
        } else {
            // 1 个 9 是 600，每多一个 9 离 1000 的差减半
            double gap = 1000 - LithoNumbers.QUARTZ_LAMP_BRIGHTNESS_1;
            b = (int) Math.round(1000 - gap * Math.pow(0.5, effectiveNines() - 1));
        }
        return xenon() ? Math.min(1000, b + LithoNumbers.XENON_BRIGHTNESS_BONUS) : b;
    }

    /** 寿命（曝光次数） */
    public int life() {
        int life;
        if (glass == LampGlass.GLASS) {
            life = mold == WaferMold.GOLD ? LithoNumbers.GLASS_LAMP_LIFE_GOLD : LithoNumbers.GLASS_LAMP_LIFE_CLAY;
        } else {
            life = LithoNumbers.QUARTZ_LAMP_LIFE_1 + LithoNumbers.QUARTZ_LAMP_LIFE_PER_NINE * (effectiveNines() - 1);
        }
        return xenon() ? life * LithoNumbers.XENON_LIFE_PERCENT / 100 : life;
    }

    public boolean burntOut() {
        return used >= life();
    }

    /** 现在的亮度（0–1）：随用量线性变暗，到寿命时剩 {@link LithoNumbers#LAMP_END_BRIGHTNESS}，之后烧坏为 0；没汞也是 0 */
    public double brightness() {
        if (burntOut() || mercury <= 0) {
            return 0;
        }
        double wear = used / (double) life();
        return baseBrightness() / 1000.0 * (1 - (1 - LithoNumbers.LAMP_END_BRIGHTNESS) * wear);
    }

    /** 这盏灯最细能做到多少 μm */
    public double resolutionUm() {
        return glass == LampGlass.GLASS ? LithoNumbers.GLASS_LAMP_RESOLUTION_UM : LithoNumbers.QUARTZ_LAMP_RESOLUTION_UM;
    }

    /** 曝光一层要多少 tick：基准 ÷ 亮度；不亮时返回 -1 */
    public int exposeTicks() {
        double b = brightness();
        return b <= 0 ? -1 : (int) Math.ceil(LithoNumbers.EXPOSE_TICKS / b);
    }
}
