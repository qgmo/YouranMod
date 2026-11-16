package moe.qingu.youran.logic.resist;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** 正胶线上服主定的数（服主 11-16，光刻.md“正胶”）：改数时这里要跟着改，免得悄悄改掉 */
class ResistNumbersTest {
    @Test
    void naphthaleneNumbers() {
        assertEquals(0.10F, ResistNumbers.DISTILL_CHANCE, 0F, "每 100 mB 杂酚油 10%");
        assertEquals(32, ResistNumbers.CARRY_FREE);
        assertEquals(3, ResistNumbers.NAUSEA_BASE_SECONDS);
        assertEquals(8, ResistNumbers.NAUSEA_SECONDS_STEP);
        assertEquals(64, ResistNumbers.NAUSEA_LEVEL_STEP);
        assertEquals(3, ResistNumbers.NAUSEA_MAX_AMPLIFIER, "最多 IV 级（放大等级 3）");
    }

    @Test
    void chemistryNumbers() {
        assertEquals(2, ResistNumbers.NAPHTHOL_SULFUR);
        assertEquals(4, ResistNumbers.NAPHTHOL_HYDROXIDE);
        assertEquals(2, ResistNumbers.NAPHTHOL_CARBONATE);
        assertEquals(1000, ResistNumbers.DIAZO_WATER);
        assertEquals(1000, ResistNumbers.SULFONYL_CHLORINE);
        assertEquals(1000, ResistNumbers.RESIST_RESIN);
        assertEquals(1000, ResistNumbers.RESIST_OUT);
    }

    @Test
    void fluoriteNumbers() {
        assertEquals(4, ResistNumbers.FLUORITE_DUST_PHOSPHORUS);
        assertEquals(25, ResistNumbers.FLUORITE_DUST_CHANCE);
        // 萤石块按 4 个萤石粉折算：磷 16 = 4 × 4，氟化钙固定 1 个
        assertEquals(4 * ResistNumbers.FLUORITE_DUST_PHOSPHORUS, ResistNumbers.FLUORITE_BLOCK_PHOSPHORUS);
        assertEquals(1, ResistNumbers.FLUORITE_BLOCK_CALCIUM_FLUORIDE);
    }
}
