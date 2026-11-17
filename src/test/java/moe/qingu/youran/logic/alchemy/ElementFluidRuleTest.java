package moe.qingu.youran.logic.alchemy;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 服主 11-17：气体、低温液体不要 144 mB ⇄ 16 个，熔融金属照旧 */
class ElementFluidRuleTest {
    @Test
    void gasesAndCryogenicLiquidsAreReplaced() {
        assertTrue(ElementFluidRule.replaceIngotPair(144, 16, 87, false), "液氩 87 K");
        assertTrue(ElementFluidRule.replaceIngotPair(144, 16, 165, false), "液氙 165 K");
        assertTrue(ElementFluidRule.replaceIngotPair(144, 16, 300, true), "常温气体（氖、氦……）");
        assertFalse(ElementFluidRule.replaceIngotPair(144, 16, 300, false), "常温液体不动（可能是没设温度的熔融金属）");
    }

    @Test
    void moltenMetalsStay() {
        assertFalse(ElementFluidRule.replaceIngotPair(144, 16, 769, false), "匠魂熔融铁");
        assertFalse(ElementFluidRule.replaceIngotPair(144, 16, 1300, false));
    }

    @Test
    void otherAmountsUntouched() {
        assertFalse(ElementFluidRule.replaceIngotPair(500, 8, 87, false), "我们自己的 500 mB ⇄ 8 个");
        assertFalse(ElementFluidRule.replaceIngotPair(144, 1, 300, false), "可可脂 144 mB → 1 个甘油三酯");
    }
}
