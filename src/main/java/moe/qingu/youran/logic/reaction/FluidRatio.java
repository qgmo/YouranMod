package moe.qingu.youran.logic.reaction;

/**
 * 输入、输出流体按比例换算（纯逻辑）：每 {@code out} mB 输出要 {@code in} mB 输入（如 3000 : 1000、1000 : 64）。机器一点一点地抽输入时，
 * 按累计算这一次该抽多少、该出多少，余数留到下一次，整个过程下来不多不少（如 3000 mB 盐酸正好出 1000 mB）。
 */
public final class FluidRatio {
    private FluidRatio() {
    }

    /**
     * 已经抽了 {@code drainedBefore} mB，这次又抽了 {@code drained} mB，这次该出几 mB（每出 1 mB 要 {@code inputPerOutput} mB 输入）。
     */
    public static int output(int drainedBefore, int drained, int inputPerOutput) {
        return output(drainedBefore, drained, inputPerOutput, 1);
    }

    /** 同上，比例是 {@code in} mB 输入出 {@code out} mB */
    public static int output(int drainedBefore, int drained, int in, int out) {
        check(in, out);
        long before = Math.max(0, drainedBefore);
        long now = before + Math.max(0, drained);
        return (int) (now * out / in - before * out / in);
    }

    /**
     * 机器本来按 1:1 每步抽 {@code step} mB（= 这一步要出的量）、已经这样走了 {@code stepsBefore} mB，这一步实际该抽多少输入。
     */
    public static int drain(int stepsBefore, int step, int in, int out) {
        check(in, out);
        long before = Math.max(0, stepsBefore);
        long now = before + Math.max(0, step);
        return (int) (now * in / out - before * in / out);
    }

    private static void check(int in, int out) {
        if (in <= 0 || out <= 0) {
            throw new IllegalArgumentException("比例要大于 0：" + in + " / " + out);
        }
    }
}
