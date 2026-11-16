package moe.qingu.youran.logic.litho;

import moe.qingu.youran.logic.purity.WaferMold;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 氦氖激光管（服主 11-16）：配比 → 输出、容量、漏氦 */
class HeNeSpecTest {
    static final double EPS = 1e-9;

    static HeNeSpec filled(LampGlass glass, WaferMold mold, int he, int ne, long t) {
        HeNeSpec s = HeNeSpec.empty(glass, mold, 2);
        for (int i = 0; i < he; i++) {
            s = s.plusHelium(t);
        }
        for (int i = 0; i < ne; i++) {
            s = s.plusNeon(t);
        }
        return s;
    }

    /** 最佳 10 氦 : 1 氖，同比例放大也算最佳；没有氖不出光；偏得越多越弱 */
    @Test
    void ratio() {
        assertEquals(1, HeNeSpec.output(10, 1), EPS);
        assertEquals(1, HeNeSpec.output(200, 20), EPS);
        assertEquals(0, HeNeSpec.output(100, 0), EPS);
        assertEquals(0, HeNeSpec.output(0, 10), EPS);
        double a = HeNeSpec.output(8, 1);
        double b = HeNeSpec.output(5, 1);
        double c = HeNeSpec.output(2, 1);
        assertTrue(1 > a && a > b && b > c && c > 0);
        assertTrue(HeNeSpec.output(20, 1) < 1);
        assertTrue(HeNeSpec.output(40, 1) < HeNeSpec.output(20, 1));
    }

    /** 一根最多 250 个元素（氦 + 氖），满了不再收 */
    @Test
    void capacity() {
        HeNeSpec s = filled(LampGlass.GLASS, WaferMold.GOLD, 227, 22, 0);
        assertEquals(249, s.elementsAt(0));
        assertTrue(s.hasRoom(0));
        s = s.plusNeon(0);
        assertEquals(250, s.elementsAt(0));
        assertFalse(s.hasRoom(0));
    }

    /** 氦慢慢漏：半衰期后剩一半；石英最快，粘土铸模的玻璃管次之，金铸模最慢；氖不漏 */
    @Test
    void heliumLeaks() {
        HeNeSpec clay = filled(LampGlass.GLASS, WaferMold.CLAY, 200, 20, 1000);
        HeNeSpec gold = filled(LampGlass.GLASS, WaferMold.GOLD, 200, 20, 1000);
        HeNeSpec quartz = filled(LampGlass.QUARTZ, null, 200, 20, 1000);
        assertTrue(quartz.halfLifeTicks() < clay.halfLifeTicks());
        assertTrue(clay.halfLifeTicks() < gold.halfLifeTicks());
        assertEquals(100, clay.heliumAt(1000 + clay.halfLifeTicks()), 1e-6);
        long later = 1000 + 30L * 24000;
        assertTrue(quartz.heliumAt(later) < clay.heliumAt(later));
        assertTrue(clay.heliumAt(later) < gold.heliumAt(later));
        assertEquals(20, quartz.neon);
        // 漏了以后配比偏离、变弱；补氦恢复
        assertEquals(1, quartz.output(1000), EPS);
        assertTrue(quartz.output(later) < 0.9);
        HeNeSpec q = quartz;
        long t = later;
        while (q.heliumAt(t) < 200 - 1e-6 && q.hasRoom(t)) {
            q = q.plusHelium(t);
        }
        assertTrue(q.output(t) > 0.98, String.valueOf(q.output(t)));
        // 记录的时刻不知道（−1）时当作现在，不算漏
        assertEquals(200, new HeNeSpec(LampGlass.GLASS, WaferMold.CLAY, 1, 200_000, 20, -1).heliumAt(99_999_999L), EPS);
    }

    /** 漏掉的一部分不占位置：漏了以后又能放进去 */
    @Test
    void leakedHeliumFreesRoom() {
        HeNeSpec s = filled(LampGlass.QUARTZ, null, 228, 22, 0);
        assertFalse(s.hasRoom(0));
        assertTrue(s.hasRoom(s.halfLifeTicks()));
    }
}
