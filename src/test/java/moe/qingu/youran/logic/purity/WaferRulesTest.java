package moe.qingu.youran.logic.purity;

import moe.qingu.youran.fluid.FluidPurity;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WaferRulesTest {
    /** 服主 11-15：只有 9 个 9 的三氯氢硅能做混合气 */
    @Test
    void mixtureThreshold() {
        assertFalse(WaferRules.canMakeMixture(Purity.DEFAULT_NINES));
        assertFalse(WaferRules.canMakeMixture(8));
        assertTrue(WaferRules.canMakeMixture(9));
        assertTrue(WaferRules.canMakeMixture(Purity.MAX_NINES));
        // 从 90% 蒸 7 遍是 8 个 9，不够；蒸 8 遍刚好
        assertFalse(WaferRules.canMakeMixture(Purity.distilled(Purity.DEFAULT_NINES, 7)));
        assertTrue(WaferRules.canMakeMixture(Purity.distilled(Purity.DEFAULT_NINES, 8)));
    }

    /** 每种能用的纯度各一条配方：9–32 个 9，共 24 条，不重复 */
    @Test
    void mixtureGrades() {
        int[] grades = WaferRules.mixtureGrades();
        assertEquals(24, grades.length);
        assertEquals(9, grades[0]);
        assertEquals(Purity.MAX_NINES, grades[grades.length - 1]);
        for (int i = 1; i < grades.length; i++) {
            assertEquals(grades[i - 1] + 1, grades[i]);
        }
        for (int g : grades) {
            assertTrue(WaferRules.canMakeMixture(g));
            assertEquals(g, WaferRules.mixtureNines(g), "混合气的纯度同三氯氢硅");
        }
    }

    /** 氢气过量：三氯氢硅 : 氢气 = 1 : 4，混合气是两者之和；炼油厂与冶炼炉同一比例 */
    @Test
    void mixtureRatio() {
        assertEquals(4 * WaferRules.REFINERY_TRICHLOROSILANE, WaferRules.REFINERY_HYDROGEN);
        assertEquals(50, WaferRules.REFINERY_MIXTURE);
        assertEquals(4 * WaferRules.ALLOY_TRICHLOROSILANE, WaferRules.ALLOY_HYDROGEN);
        assertEquals(5, WaferRules.ALLOY_MIXTURE);
        assertEquals(WaferRules.REFINERY_MIXTURE * WaferRules.ALLOY_TRICHLOROSILANE,
                WaferRules.ALLOY_MIXTURE * WaferRules.REFINERY_TRICHLOROSILANE);
    }

    /** 纯度传递（NBT 层面）：三氯氢硅的标记 → 混合气的标记个数一样，9 个 9 写 Nines:9 */
    @Test
    void mixtureTag() {
        int nines = Purity.distilled(Purity.DEFAULT_NINES, 8);
        int mixture = WaferRules.mixtureNines(FluidPurity.nines(FluidPurity.tagFor(nines)));
        assertEquals(9, FluidPurity.nines(FluidPurity.tagFor(mixture)));
        assertEquals(9, FluidPurity.tagFor(mixture).getInteger(FluidPurity.TAG_NINES));
    }

    /** 灌装机按 NBT 比，要登记的纯度：没有标记的 1 个 9 与 9–32 个 9，共 25 种；晶圆纯度同混合气 */
    @Test
    void castingGrades() {
        int[] grades = WaferRules.castingGrades();
        assertEquals(25, grades.length);
        assertEquals(Purity.DEFAULT_NINES, grades[0]);
        assertEquals(9, grades[1]);
        assertEquals(Purity.MAX_NINES, grades[24]);
        for (int g : grades) {
            assertEquals(g, WaferRules.waferNines(g));
        }
        assertEquals(1000, WaferRules.MIXTURE_PER_WAFER);
    }
}
