package moe.qingu.youran.logic.purity;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WashWaterTest {
    /** 整合包里的水：8 种，纯净率在 0–100 之间，按从低到高排；不是水的不认 */
    @Test
    void table() {
        assertEquals(8, WashWater.rates().size());
        List<Integer> rates = new ArrayList<>(WashWater.rates().values());
        for (int i = 1; i < rates.size(); i++) {
            assertTrue(rates.get(i - 1) < rates.get(i), "从低到高");
        }
        assertEquals(60, WashWater.rate("water"));
        assertEquals(95, WashWater.rate("ic2distilled_water"));
        assertEquals(85, WashWater.rate("purified_water"));
        assertEquals(50, WashWater.rate("fuild_mineral_water"));
        assertTrue(WashWater.isWashWater("swamp_water"));
        assertFalse(WashWater.isWashWater("lava"));
        assertFalse(WashWater.isWashWater("h_cl_fluid"));
        assertFalse(WashWater.isWashWater(null));
        assertEquals(0, WashWater.rate("lava"));
        assertEquals(0, WashWater.rate(null));
    }

    /** 报废概率：(1 − 纯净率) × 铸模倍数 × 0.8^(个数 − 9)；文档里举的例子 */
    @Test
    void scrapChanceExamples() {
        assertEquals(0.05, WaferRules.scrapChance(95, 9, WaferMold.GOLD), 1e-12);
        assertEquals(0.075, WaferRules.scrapChance(95, 9, WaferMold.CLAY), 1e-12);
        assertEquals(0.40, WaferRules.scrapChance(60, 9, WaferMold.GOLD), 1e-12);
        assertEquals(0.60, WaferRules.scrapChance(60, 9, WaferMold.CLAY), 1e-12);
        assertEquals(0.15, WaferRules.scrapChance(85, 9, WaferMold.GOLD), 1e-12);
        // 每多一个 9 乘 0.8
        assertEquals(0.04, WaferRules.scrapChance(95, 10, WaferMold.GOLD), 1e-12);
        assertEquals(0.05 * Math.pow(0.8, 23), WaferRules.scrapChance(95, Purity.MAX_NINES, WaferMold.GOLD), 1e-15);
        // 沼泽水 + 粘土：0.8 × 1.5 = 1.2，按 1 算
        assertEquals(1.0, WaferRules.scrapChance(20, 9, WaferMold.CLAY));
        // 不知道铸模按粘土
        assertEquals(WaferRules.scrapChance(95, 9, WaferMold.CLAY), WaferRules.scrapChance(95, 9, null));
    }

    /** 不到 9 个 9 一定报废；不是水（纯净率 0）也照公式算；越纯的水、金铸模、9 越多，越不容易报废 */
    @Test
    void scrapChanceRules() {
        assertEquals(1.0, WaferRules.scrapChance(98, 8, WaferMold.GOLD));
        assertEquals(1.0, WaferRules.scrapChance(98, Purity.DEFAULT_NINES, WaferMold.GOLD));
        assertEquals(1.0, WaferRules.scrapChance(0, 9, WaferMold.GOLD));
        assertEquals(0.0, WaferRules.scrapChance(100, 9, WaferMold.CLAY));
        assertEquals(0.0, WaferRules.scrapChance(150, 9, WaferMold.CLAY), "超出范围的纯净率夹到 100");
        double last = 2;
        for (int rate : WashWater.rates().values()) {
            double p = WaferRules.scrapChance(rate, 9, WaferMold.GOLD);
            assertTrue(p < last, "纯净率 " + rate);
            assertTrue(p <= WaferRules.scrapChance(rate, 9, WaferMold.CLAY));
            assertTrue(WaferRules.scrapChance(rate, 12, WaferMold.GOLD) <= p);
            last = p;
        }
    }
}
