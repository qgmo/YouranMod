package moe.qingu.youran.logic.purity;

/**
 * 芯片线的纯度与蒸馏提纯（纯逻辑，不依赖 Minecraft）。
 *
 * <p>纯度一律用“几个 9”（整数）表示（服主 11-15）：1 个 9 = 90%，2 个 9 = 99%，3 个 9 = 99.9%，9 个 9 = 99.9999999%。
 * 不用浮点数或高精度小数，比较、传递、显示都按个数，没有舍入的问题。
 *
 * <p>蒸馏一遍：每 {@value #BATCH_INPUT} mB 纯度 x 的三氯氢硅 → {@value #BATCH_OUTPUT} mB 纯度 x + 0.9 (1 − x) 的三氯氢硅，
 * 也就是杂质降到十分之一、多一个 9（服主 11-15）。最多 {@value #MAX_NINES} 个 9，到了再蒸也不加。
 */
public final class Purity {
    /** 没有纯度标记时按这个算：1 个 9，纯度 90%（服主 11-15：相当于管道、罐子不干净，破坏了纯度） */
    public static final int DEFAULT_NINES = 1;
    /** 最多这么多个 9（Claude 11-15 定的上限）：再蒸不加，读到更大的数也按这个算 */
    public static final int MAX_NINES = 32;
    /** 能精炼高纯硅（做混合气）的门槛：9 个 9（服主 11-15） */
    public static final int WAFER_GRADE_NINES = 9;
    /** 蒸馏一次要的量（mB） */
    public static final int BATCH_INPUT = 10;
    /** 蒸馏一次出的量（mB） */
    public static final int BATCH_OUTPUT = 9;
    /** 显示时 9 不超过这么多个才写出百分数，再多只写“N 个 9”（百分数太长） */
    public static final int MAX_PERCENT_NINES = 12;

    private Purity() {
    }

    /** 把读到的个数收拾成有效值：少于 {@value #DEFAULT_NINES} 个按 {@value #DEFAULT_NINES} 个，多于 {@value #MAX_NINES} 个按 {@value #MAX_NINES} 个 */
    public static int sanitize(int nines) {
        return Math.max(DEFAULT_NINES, Math.min(MAX_NINES, nines));
    }

    /** 蒸馏一遍后：多一个 9（到上限为止） */
    public static int distilled(int nines) {
        return distilled(nines, 1);
    }

    /** 连蒸 {@code passes} 遍后 */
    public static int distilled(int nines, int passes) {
        if (passes < 0) {
            throw new IllegalArgumentException("遍数不能为负：" + passes);
        }
        long n = (long) sanitize(nines) + passes;
        return (int) Math.min(MAX_NINES, n);
    }

    /** 纯度到没到 {@code n} 个 9 */
    public static boolean atLeastNines(int nines, int n) {
        return sanitize(nines) >= n;
    }

    /** 能不能做混合气：到了 {@value #WAFER_GRADE_NINES} 个 9 */
    public static boolean waferGrade(int nines) {
        return atLeastNines(nines, WAFER_GRADE_NINES);
    }

    /**
     * 纯度的百分数文字，不带百分号，由个数拼出（不经过浮点运算）：1 →“90”，2 →“99”，3 →“99.9”，9 →“99.9999999”。
     */
    public static String percent(int nines) {
        int n = sanitize(nines);
        if (n == 1) {
            return "90";
        }
        StringBuilder sb = new StringBuilder("99");
        if (n > 2) {
            sb.append('.');
            for (int i = 2; i < n; i++) {
                sb.append('9');
            }
        }
        return sb.toString();
    }

    /** 显示时写不写百分数（9 太多时只写个数） */
    public static boolean showPercent(int nines) {
        return sanitize(nines) <= MAX_PERCENT_NINES;
    }

    /**
     * 蒸 {@code input} mB（一份最多 {@value #BATCH_INPUT} mB）出多少 mB：九成，往下取整。整份 10 → 9；
     * 罐里只剩不到一份、又一直没有新的进来时，机器把余量当一份蒸掉（9 → 8，5 → 4，1 → 0），免得余量卡在罐里，
     * 挡住别的纯度的三氯氢硅进罐。
     */
    public static int outputFor(int input) {
        return input <= 0 ? 0 : input * BATCH_OUTPUT / BATCH_INPUT;
    }

    /** {@code volume} mB 能蒸几次（不够 {@value #BATCH_INPUT} mB 的余量留在罐里） */
    public static int batches(int volume) {
        return volume <= 0 ? 0 : volume / BATCH_INPUT;
    }

    /** {@code volume} mB 全部蒸一遍能出多少 mB（余量不算） */
    public static int distilledVolume(int volume) {
        return batches(volume) * BATCH_OUTPUT;
    }
}
