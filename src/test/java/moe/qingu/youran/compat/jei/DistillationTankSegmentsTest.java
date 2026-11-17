package moe.qingu.youran.compat.jei;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 精馏塔 JEI 页输出罐里叠着的几格：加起来正好是罐子高，每格至少 4 像素（服主 11-17：刻度伸出罐子、压住说明文字） */
class DistillationTankSegmentsTest {
    static int sum(int[] h) {
        int s = 0;
        for (int v : h) {
            s += v;
        }
        return s;
    }

    @Test
    void fillsTankExactly() {
        int[][] cases = {{1000}, {500, 300, 200}, {24, 1000}, {1, 1, 1, 1000}, {7, 3}, {0, 50}, {100, 100, 100, 100, 100, 100}};
        for (int[] c : cases) {
            int[] h = TankSegments.split(c);
            assertEquals(TankSegments.TANK_HEIGHT, sum(h), java.util.Arrays.toString(c));
            for (int v : h) {
                assertTrue(v >= 4, "每格至少 4 像素：" + java.util.Arrays.toString(h));
            }
        }
    }

    @Test
    void proportionalWhenRoomy() {
        int[] h = TankSegments.split(new int[]{500, 300, 200});
        assertEquals(23, h[0]);
        assertEquals(14, h[1]);
        assertEquals(10, h[2]);
    }
}
