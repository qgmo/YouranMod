package moe.qingu.youran.logic.litho;

import moe.qingu.youran.logic.purity.Purity;
import moe.qingu.youran.logic.purity.WaferMold;

/**
 * 氦氖激光管（服主 11-16，633 nm 红光，激光对准用）的数据与公式（纯逻辑）。存原始量：玻璃管（种类、铸模、纯度，同汞灯那根）、
 * 氦（千分之一个，记的是 {@link #stamp} 那一刻的量）、氖（个）、记氦量的时刻（世界总刻数；不知道时 −1）。
 *
 * <ul>
 *     <li>充气（服主 11-16）：IC2 固体装罐机每次放一个氦或氖元素，一根管最多 {@value DuvNumbers#HENE_MAX_ELEMENTS} 个（合计）；</li>
 *     <li>配比（服主 11-16）：最佳 10 氦 : 1 氖，同比例放大也算最佳；没有氖不出光，偏得越多越弱（公式 Claude 定，见 {@link #output}）；</li>
 *     <li>漏氦（服主 11-16 定方向，数值 Claude 定）：氦穿过玻璃慢慢漏掉，按半衰期指数减少；石英管最快，金铸模的玻璃管最慢
 *     （{@link #halfLifeTicks}）。放着不用也漏，所以存“那一刻的量 + 那一刻”，用的时候按现在的时刻现算。氖不漏。</li>
 * </ul>
 */
public final class HeNeSpec {
    public final LampGlass glass;
    /** 低档玻璃管的铸模（石英管为 null；没有记录按粘土） */
    public final WaferMold mold;
    public final int nines;
    /** {@link #stamp} 那一刻的氦（千分之一个） */
    public final int heliumMilli;
    public final int neon;
    /** 记氦量的时刻（世界总刻数）；−1 表示不知道（当作就是现在，不算漏） */
    public final long stamp;

    public HeNeSpec(LampGlass glass, WaferMold mold, int nines, int heliumMilli, int neon, long stamp) {
        this.glass = glass == null ? LampGlass.GLASS : glass;
        this.mold = this.glass == LampGlass.GLASS ? mold : null;
        this.nines = Purity.sanitize(nines);
        this.heliumMilli = Math.max(0, heliumMilli);
        this.neon = Math.max(0, neon);
        this.stamp = stamp;
    }

    /** 用这根玻璃管刚装出来的空管 */
    public static HeNeSpec empty(LampGlass glass, WaferMold mold, int nines) {
        return new HeNeSpec(glass, mold, nines, 0, 0, -1);
    }

    /** 这根管的氦半衰期（tick） */
    public long halfLifeTicks() {
        if (glass == LampGlass.QUARTZ) {
            return DuvNumbers.HENE_HALF_LIFE_QUARTZ;
        }
        return mold == WaferMold.GOLD ? DuvNumbers.HENE_HALF_LIFE_GOLD : DuvNumbers.HENE_HALF_LIFE_CLAY;
    }

    /** {@code now} 时的氦（千分之一个，带小数） */
    public double heliumMilliAt(long now) {
        if (stamp < 0 || now <= stamp || heliumMilli == 0) {
            return heliumMilli;
        }
        return heliumMilli * Math.pow(0.5, (now - stamp) / (double) halfLifeTicks());
    }

    /** {@code now} 时的氦（个，带小数） */
    public double heliumAt(long now) {
        return heliumMilliAt(now) / DuvNumbers.MILLI;
    }

    /** 把漏掉的算进去、时刻记成 {@code now} */
    public HeNeSpec settled(long now) {
        return new HeNeSpec(glass, mold, nines, (int) Math.floor(heliumMilliAt(now)), neon, now);
    }

    /** {@code now} 时管里一共几个元素（氦按整数个算，漏掉的一部分不占位置） */
    public int elementsAt(long now) {
        return (int) Math.ceil(heliumMilliAt(now) / DuvNumbers.MILLI - 1e-9) + neon;
    }

    /** 还能再放一个元素 */
    public boolean hasRoom(long now) {
        return elementsAt(now) < DuvNumbers.HENE_MAX_ELEMENTS;
    }

    /** 放进一个氦元素（先算上漏掉的） */
    public HeNeSpec plusHelium(long now) {
        HeNeSpec s = settled(now);
        return new HeNeSpec(glass, mold, nines, s.heliumMilli + DuvNumbers.MILLI, neon, now);
    }

    /** 放进一个氖元素 */
    public HeNeSpec plusNeon(long now) {
        HeNeSpec s = settled(now);
        return new HeNeSpec(glass, mold, nines, s.heliumMilli, neon + 1, now);
    }

    /** {@code now} 时的输出强度（0–1） */
    public double output(long now) {
        return output(heliumAt(now), neon);
    }

    /** 配比 → 输出（0–1）：q = 氦 ÷ (10 × 氖)，输出 = exp(−k × ln(q)²)；没有氖、没有氦为 0 */
    public static double output(double helium, double neon) {
        if (neon <= 0 || helium <= 0) {
            return 0;
        }
        double q = helium / (DuvNumbers.HENE_BEST_RATIO * neon);
        double l = Math.log(q);
        return Math.exp(-DuvNumbers.HENE_RATIO_K * l * l);
    }
}
