package moe.qingu.youran.logic.reaction;

/**
 * 输入、输出流体按整数比换算（纯逻辑）：每出 1 mB 要 {@code inputPerOutput} mB 输入。机器一点一点地抽输入时，
 * 按累计抽了多少算这一次该出多少，余数留到下一次，整个过程下来不多不少（如 3000 mB 盐酸正好出 1000 mB）。
 */
public final class FluidRatio {
    private FluidRatio() {
    }

    /**
     * 已经抽了 {@code drainedBefore} mB，这次又抽了 {@code drained} mB，这次该出几 mB。
     *
     * @param inputPerOutput 每出 1 mB 要几 mB 输入（≥ 1）
     */
    public static int output(int drainedBefore, int drained, int inputPerOutput) {
        if (inputPerOutput <= 0) {
            throw new IllegalArgumentException("比例要大于 0：" + inputPerOutput);
        }
        int before = Math.max(0, drainedBefore);
        int now = before + Math.max(0, drained);
        return now / inputPerOutput - before / inputPerOutput;
    }
}
