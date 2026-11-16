package moe.qingu.youran.logic.litho;

import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class XenonYieldTest {
    /** 单联 1、双联 2、四联 4（铀、MOX 都是） */
    @Test
    void rodsByMeta() {
        assertEquals(1, XenonYield.rodsIn(11));
        assertEquals(2, XenonYield.rodsIn(12));
        assertEquals(4, XenonYield.rodsIn(13));
        assertEquals(1, XenonYield.rodsIn(14));
        assertEquals(2, XenonYield.rodsIn(15));
        assertEquals(4, XenonYield.rodsIn(16));
    }

    /** 几联就是几根各自随机再相加：范围按棒数乘，四联平均约是单联的 4 倍 */
    @Test
    void amountScalesWithRods() {
        Random r = new Random(7);
        long one = 0, four = 0;
        for (int i = 0; i < 4000; i++) {
            int a = XenonYield.of(r, 1);
            int b = XenonYield.of(r, 4);
            assertTrue(a >= LithoNumbers.XENON_PER_ROD_MIN && a <= LithoNumbers.XENON_PER_ROD_MAX);
            assertTrue(b >= 4 * LithoNumbers.XENON_PER_ROD_MIN && b <= 4 * LithoNumbers.XENON_PER_ROD_MAX);
            one += a;
            four += b;
        }
        double ratio = (double) four / one;
        assertTrue(ratio > 3.8 && ratio < 4.2, "比值 " + ratio);
    }
}
