package moe.qingu.youran.logic.reaction;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** 不是整数倍的比例（1000 mB 氯气 → 64 mB 四氯化锡）：搅拌机按 1:1 每步要出 1 mB，这里换成按比例抽、按比例出，总量不差 */
class FluidRatioRationalTest {
    @Test
    void chlorineToTinTetrachloride() {
        int steps = 0;
        int drained = 0;
        int out = 0;
        for (int i = 0; i < 64; i++) {
            int d = FluidRatio.drain(steps, 1, 1000, 64);
            steps += 1;
            out += FluidRatio.output(drained, d, 1000, 64);
            drained += d;
        }
        assertEquals(1000, drained);
        assertEquals(64, out);
    }

    @Test
    void integerRatioUnchanged() {
        assertEquals(3, FluidRatio.drain(0, 1, 3000, 1000));
        assertEquals(1, FluidRatio.output(0, 3, 3000, 1000));
        assertEquals(FluidRatio.output(7, 5, 3), FluidRatio.output(7, 5, 3, 1));
    }
}
