package moe.qingu.youran.logic.reaction;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class FluidRatioTest {
    /** 搅拌机每 tick 抽 3 mB 盐酸：每 tick 出 1 mB，1000 tick 正好 3000 → 1000 */
    @Test
    void steadyThreeToOne() {
        int drained = 0;
        int out = 0;
        for (int tick = 0; tick < 1000; tick++) {
            int o = FluidRatio.output(drained, 3, 3);
            assertEquals(1, o);
            drained += 3;
            out += o;
        }
        assertEquals(3000, drained);
        assertEquals(1000, out);
    }

    /** 抽的量不整齐（时间倍率不是 1、罐里快空了）时余数留到下一次，总量不变 */
    @Test
    void unevenSteps() {
        int[] steps = {1, 2, 1, 1, 5, 2, 0, 4, 1, 1, 2, 7, 3};
        int drained = 0;
        int out = 0;
        for (int s : steps) {
            out += FluidRatio.output(drained, s, 3);
            drained += s;
        }
        assertEquals(drained / 3, out);
        assertEquals(5, FluidRatio.output(0, 5, 1));
        assertThrows(IllegalArgumentException.class, () -> FluidRatio.output(0, 1, 0));
    }
}
