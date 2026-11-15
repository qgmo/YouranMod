package moe.qingu.youran.logic.purity;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PurityTest {
    /** 服主 11-15：每 10 mB 纯度 x → 9 mB 纯度 x + 0.9 (1 − x)，即多一个 9；从 90%（1 个 9）连蒸 8 遍正好 9 个 9 */
    @Test
    void eightPassesFromNinetyToNineNines() {
        int nines = Purity.DEFAULT_NINES;
        String[] expected = {"99", "99.9", "99.99", "99.999", "99.9999", "99.99999", "99.999999", "99.9999999"};
        assertEquals(1, nines);
        assertEquals("90", Purity.percent(nines));
        for (int pass = 1; pass <= 8; pass++) {
            nines = Purity.distilled(nines);
            assertEquals(pass + 1, nines, "第 " + pass + " 遍");
            assertEquals(expected[pass - 1], Purity.percent(nines), "第 " + pass + " 遍");
        }
        assertTrue(Purity.atLeastNines(nines, 9));
        assertFalse(Purity.atLeastNines(nines, 10));
        assertEquals(9, Purity.distilled(Purity.DEFAULT_NINES, 8));
    }

    /** 9 个 9 的门槛：8 个 9 不够 */
    @Test
    void waferGrade() {
        assertFalse(Purity.waferGrade(Purity.DEFAULT_NINES));
        assertFalse(Purity.waferGrade(8));
        assertTrue(Purity.waferGrade(9));
        assertTrue(Purity.waferGrade(Purity.MAX_NINES));
    }

    /** 上限 32 个：再蒸不加，读到更大、更小的数夹到范围里 */
    @Test
    void bounds() {
        assertEquals(Purity.MAX_NINES, Purity.distilled(Purity.MAX_NINES));
        assertEquals(Purity.MAX_NINES, Purity.distilled(30, 5));
        assertEquals(Purity.MAX_NINES, Purity.distilled(1, Integer.MAX_VALUE));
        assertEquals(Purity.MAX_NINES, Purity.sanitize(1000));
        assertEquals(Purity.DEFAULT_NINES, Purity.sanitize(0));
        assertEquals(Purity.DEFAULT_NINES, Purity.sanitize(-5));
        assertThrows(IllegalArgumentException.class, () -> Purity.distilled(1, -1));
    }

    /** 显示：由个数拼出百分数，12 个以内写百分数，再多只写个数 */
    @Test
    void percentText() {
        assertEquals("90", Purity.percent(1));
        assertEquals("99", Purity.percent(2));
        assertEquals("99.9", Purity.percent(3));
        assertEquals("99.9999999999", Purity.percent(12));
        assertEquals(33, Purity.percent(Purity.MAX_NINES).length(), "99. 后面 30 个 9");
        assertTrue(Purity.showPercent(12));
        assertFalse(Purity.showPercent(13));
    }

    /** 体积：每 10 mB 出 9 mB，不够 10 mB 的余量留下 */
    @Test
    void volume() {
        assertEquals(0, Purity.distilledVolume(9));
        assertEquals(9, Purity.distilledVolume(10));
        assertEquals(9, Purity.distilledVolume(19));
        assertEquals(900, Purity.distilledVolume(1000));
        // 10000 mB 连蒸 8 遍，每遍都按 10 mB 一份，余量不进下一遍
        int[] expected = {9000, 8100, 7290, 6561, 5904, 5310, 4779, 4293};
        int v = 10000;
        for (int pass = 0; pass < 8; pass++) {
            v = Purity.distilledVolume(v);
            assertEquals(expected[pass], v, "第 " + (pass + 1) + " 遍");
        }
    }

    /** 一份最多 10 mB，出九成往下取整；余量当一份蒸时 9 → 8，1 → 0 */
    @Test
    void remainder() {
        assertEquals(9, Purity.outputFor(10));
        assertEquals(8, Purity.outputFor(9));
        assertEquals(4, Purity.outputFor(5));
        assertEquals(0, Purity.outputFor(1));
        assertEquals(0, Purity.outputFor(0));
    }
}
