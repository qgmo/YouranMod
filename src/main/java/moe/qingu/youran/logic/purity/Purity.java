package moe.qingu.youran.logic.purity;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * 三氯氢硅的纯度与蒸馏提纯（纯逻辑，不依赖 Minecraft）。
 *
 * <p>记的是“杂质比例”（0–1），不是纯度：纯度在 0.999999999 附近时，{@code 1 - 1e-9} 这样的双精度数已经失真，
 * 杂质比例 {@code 1e-9} 却很准。接口上需要时再换算成纯度（{@link #purity}）。
 *
 * <p>蒸馏一遍：每 {@value #BATCH_INPUT} mB 纯度 x 的三氯氢硅 → {@value #BATCH_OUTPUT} mB 纯度 x + 0.9 (1 − x) 的三氯氢硅，
 * 也就是杂质降到原来的十分之一（服主 11-15）。乘 0.1 按十进制算（{@link #distilled}），
 * 从 0.1 连蒸 8 遍正好得到 {@code 1e-9}，不会变成 {@code 1.0000000000000005E-9} 这样差一点的数，“几个 9”也就数得准。
 */
public final class Purity {
    /** 没有纯度标记时按这个算：纯度 90%（服主 11-15：相当于管道、罐子不干净，破坏了纯度） */
    public static final double DEFAULT_IMPURITY = 0.1;
    /** 蒸馏一次要的量（mB） */
    public static final int BATCH_INPUT = 10;
    /** 蒸馏一次出的量（mB） */
    public static final int BATCH_OUTPUT = 9;
    /** 显示纯度时最多保留的小数位（百分数），再往后的截掉（不四舍五入，免得多显示出一个 9） */
    static final int MAX_PERCENT_DECIMALS = 12;
    /** 数“几个 9”时最多数到这么多（杂质为 0 时也是这个数） */
    public static final int MAX_NINES = 30;

    private Purity() {
    }

    /** 把读到的杂质比例收拾成有效值：NaN 当作没有标记，小于 0 当作 0，大于 1 当作 1 */
    public static double sanitize(double impurity) {
        if (Double.isNaN(impurity)) {
            return DEFAULT_IMPURITY;
        }
        return Math.max(0.0, Math.min(1.0, impurity));
    }

    /** 纯度（0–1）= 1 − 杂质比例 */
    public static double purity(double impurity) {
        return BigDecimal.ONE.subtract(BigDecimal.valueOf(sanitize(impurity))).doubleValue();
    }

    /** 由纯度（0–1）换成杂质比例，按十进制算（纯度 0.999 → 杂质 0.001，不是 0.0010000000000000009） */
    public static double impurityOf(double purity) {
        if (Double.isNaN(purity)) {
            return DEFAULT_IMPURITY;
        }
        return sanitize(BigDecimal.ONE.subtract(BigDecimal.valueOf(purity)).doubleValue());
    }

    /** 蒸馏一遍后的杂质比例：原来的十分之一（十进制移一位） */
    public static double distilled(double impurity) {
        return BigDecimal.valueOf(sanitize(impurity)).movePointLeft(1).doubleValue();
    }

    /** 连蒸 {@code passes} 遍后的杂质比例 */
    public static double distilled(double impurity, int passes) {
        if (passes < 0) {
            throw new IllegalArgumentException("遍数不能为负：" + passes);
        }
        return BigDecimal.valueOf(sanitize(impurity)).movePointLeft(passes).doubleValue();
    }

    /** {@code volume} mB 能蒸几次（不够 {@value #BATCH_INPUT} mB 的余量留在罐里） */
    public static int batches(int volume) {
        return volume <= 0 ? 0 : volume / BATCH_INPUT;
    }

    /** {@code volume} mB 全部蒸一遍能出多少 mB（余量不算） */
    public static int distilledVolume(int volume) {
        return batches(volume) * BATCH_OUTPUT;
    }

    /**
     * 纯度的百分数文字，不带百分号：杂质 0.1 →“90”，0.001 →“99.9”，1e-9 →“99.9999999”，0 →“100”。
     * 按十进制精确算，超过 {@value #MAX_PERCENT_DECIMALS} 位小数的截掉。
     */
    public static String percent(double impurity) {
        BigDecimal p = BigDecimal.ONE.subtract(BigDecimal.valueOf(sanitize(impurity))).movePointRight(2);
        if (p.scale() > MAX_PERCENT_DECIMALS) {
            p = p.setScale(MAX_PERCENT_DECIMALS, RoundingMode.DOWN);
        }
        p = p.stripTrailingZeros();
        if (p.scale() < 0) {
            p = p.setScale(0);
        }
        return p.toPlainString();
    }

    /**
     * 纯度有“几个 9”：杂质不超过 10^-k 的最大的 k（90% 是 1 个，99.9% 是 3 个，99.9999999% 是 9 个；不到 90% 是 0 个）。
     * 杂质为 0 时返回 {@link #MAX_NINES}。
     */
    public static int nines(double impurity) {
        BigDecimal imp = BigDecimal.valueOf(sanitize(impurity));
        int k = 0;
        while (k < MAX_NINES && imp.compareTo(BigDecimal.ONE.movePointLeft(k + 1)) <= 0) {
            k++;
        }
        return k;
    }

    /** 纯度到没到 {@code n} 个 9（如 9 个 9 = 99.9999999%） */
    public static boolean atLeastNines(double impurity, int n) {
        return nines(impurity) >= n;
    }
}
