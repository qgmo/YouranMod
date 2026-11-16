package moe.qingu.youran.logic.resist;

import moe.qingu.youran.logic.litho.ResistType;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 正胶显影的扣料（服主 11-16）：每层 125 mB 水 + 2 个氢氧根或 1 个碳酸钾；负胶照旧用溶剂 */
class PositiveDevelopTest {
    @Test
    void numbersAreTheOnesTheOwnerSet() {
        assertEquals(125, ResistNumbers.DEVELOP_WATER);
        assertEquals(2, ResistNumbers.DEVELOP_HYDROXIDE);
        assertEquals(1, ResistNumbers.DEVELOP_CARBONATE);
        assertEquals(2, PositiveDevelop.Alkali.HYDROXIDE.perLayer);
        assertEquals(1, PositiveDevelop.Alkali.CARBONATE.perLayer);
    }

    @Test
    void negativeUsesSolventPositiveUsesAlkali() {
        assertFalse(PositiveDevelop.usesAlkali(ResistType.NEGATIVE));
        assertTrue(PositiveDevelop.usesAlkali(ResistType.POSITIVE));
        assertFalse(PositiveDevelop.usesAlkali(null), "没记下种类的按溶剂（旧存档）");
    }

    @Test
    void hydroxideNeedsTwo() {
        assertNull(PositiveDevelop.choose(1, 0), "1 个氢氧根不够");
        assertEquals(PositiveDevelop.Alkali.HYDROXIDE, PositiveDevelop.choose(2, 0));
        assertEquals(PositiveDevelop.Alkali.HYDROXIDE, PositiveDevelop.choose(64, 0));
    }

    @Test
    void carbonateNeedsOne() {
        assertNull(PositiveDevelop.choose(0, 0));
        assertEquals(PositiveDevelop.Alkali.CARBONATE, PositiveDevelop.choose(0, 1));
        // 氢氧根只有 1 个、不够用，就改用碳酸钾
        assertEquals(PositiveDevelop.Alkali.CARBONATE, PositiveDevelop.choose(1, 1));
    }

    @Test
    void hydroxideFirstWhenBothEnough() {
        assertEquals(PositiveDevelop.Alkali.HYDROXIDE, PositiveDevelop.choose(2, 5));
    }

    @Test
    void checkReportsWhatIsMissing() {
        assertEquals(PositiveDevelop.Missing.NOTHING, PositiveDevelop.check(125, 2, 0));
        assertEquals(PositiveDevelop.Missing.NOTHING, PositiveDevelop.check(4000, 0, 1));
        assertEquals(PositiveDevelop.Missing.WATER, PositiveDevelop.check(124, 2, 0), "差 1 mB 水也停下");
        assertEquals(PositiveDevelop.Missing.WATER, PositiveDevelop.check(0, 0, 0), "水、碱都缺先报水");
        assertEquals(PositiveDevelop.Missing.ALKALI, PositiveDevelop.check(125, 1, 0));
        assertEquals(PositiveDevelop.Missing.ALKALI, PositiveDevelop.check(8000, 0, 0));
    }

    @Test
    void consumptionOverManyLayers() {
        // 6 层的芯片：750 mB 水；氢氧根 12 个 / 碳酸钾 6 个
        int water = 4000;
        int hydroxide = 12;
        int layers = 0;
        while (PositiveDevelop.check(water, hydroxide, 0) == PositiveDevelop.Missing.NOTHING) {
            water -= ResistNumbers.DEVELOP_WATER;
            hydroxide -= PositiveDevelop.choose(hydroxide, 0).perLayer;
            layers++;
        }
        assertEquals(6, layers);
        assertEquals(4000 - 6 * 125, water);
        assertEquals(0, hydroxide);
    }

    @Test
    void waters() {
        assertTrue(PositiveDevelop.isWater("water"));
        assertTrue(PositiveDevelop.isWater("ic2distilled_water"));
        assertTrue(PositiveDevelop.isWater("fuild_mineral_water"));
        assertTrue(PositiveDevelop.isWater("purified_water"));
        assertFalse(PositiveDevelop.isWater("creosote"));
        assertFalse(PositiveDevelop.isWater("arene"));
        assertFalse(PositiveDevelop.isWater("lava"));
    }
}
