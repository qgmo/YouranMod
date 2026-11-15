package moe.qingu.youran.logic.purity;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PurityTest {
    /** 服主 11-15：每 10 mB 纯度 x → 9 mB 纯度 x + 0.9 (1 − x)；从 90% 连蒸 8 遍正好 9 个 9 */
    @Test
    void eightPassesFromNinetyToNineNines() {
        double imp = Purity.DEFAULT_IMPURITY;
        String[] expected = {"99", "99.9", "99.99", "99.999", "99.9999", "99.99999", "99.999999", "99.9999999"};
        assertEquals("90", Purity.percent(imp));
        assertEquals(1, Purity.nines(imp));
        for (int pass = 1; pass <= 8; pass++) {
            double x = Purity.purity(imp);
            double next = Purity.distilled(imp);
            // 同题目里的式子：新纯度 = x + 0.9 (1 − x)（双精度下只差舍入误差）
            assertEquals(x + 0.9 * (1 - x), Purity.purity(next), 1e-15, "第 " + pass + " 遍");
            imp = next;
            assertEquals(expected[pass - 1], Purity.percent(imp), "第 " + pass + " 遍");
            assertEquals(pass + 1, Purity.nines(imp), "第 " + pass + " 遍");
        }
        // 杂质正好是 1e-9（十进制算，不是 1.0000000000000005E-9），9 个 9 刚好够
        assertEquals(1e-9, imp);
        assertTrue(Purity.atLeastNines(imp, 9));
        assertFalse(Purity.atLeastNines(imp, 10));
        assertEquals(1e-9, Purity.distilled(Purity.DEFAULT_IMPURITY, 8));
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
        // 理想值 0.9^8 ≈ 43%，余量损失之后是 42.93%
        assertEquals(0.4305, Math.pow(0.9, 8), 1e-4);
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

    @Test
    void conversions() {
        assertEquals(0.9, Purity.purity(0.1));
        assertEquals(0.001, Purity.impurityOf(0.999));
        assertEquals(1e-9, Purity.impurityOf(0.999999999));
        assertEquals("100", Purity.percent(0));
        assertEquals("0", Purity.percent(1));
        assertEquals(Purity.MAX_NINES, Purity.nines(0));
        assertEquals(0, Purity.nines(0.5));
        assertEquals("50", Purity.percent(0.5));
        // 太长的小数截掉（不进位成更多的 9）
        assertEquals("99.999999999999", Purity.percent(1e-15));
        // 坏值：NaN 按没有标记（90%），超出范围的夹到 0–1
        assertEquals(Purity.DEFAULT_IMPURITY, Purity.sanitize(Double.NaN));
        assertEquals(0, Purity.sanitize(-1));
        assertEquals(1, Purity.sanitize(2));
        assertThrows(IllegalArgumentException.class, () -> Purity.distilled(0.1, -1));
    }
}
