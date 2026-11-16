package moe.qingu.youran.logic.resist;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 萘的毒性（服主 11-16）：超过 32 个才反胃；时长 3 秒 + 超出每 8 个加 1 秒；等级 超出每 64 个加一级，最多 IV */
class NaphthaleneToxicityTest {
    @Test
    void notPoisonousUpTo32() {
        assertFalse(NaphthaleneToxicity.poisons(0));
        assertFalse(NaphthaleneToxicity.poisons(1));
        assertFalse(NaphthaleneToxicity.poisons(32), "正好 32 个不反胃");
        assertEquals(0, NaphthaleneToxicity.seconds(32));
        assertEquals(-1, NaphthaleneToxicity.amplifier(32));
        assertEquals(0, NaphthaleneToxicity.excess(32));
    }

    @Test
    void justOver32() {
        assertTrue(NaphthaleneToxicity.poisons(33));
        assertEquals(1, NaphthaleneToxicity.excess(33));
        assertEquals(3, NaphthaleneToxicity.seconds(33), "超出 1 个：还是 3 秒");
        assertEquals(0, NaphthaleneToxicity.amplifier(33), "I 级");
    }

    @Test
    void durationGrowsOneSecondPerEight() {
        assertEquals(3, NaphthaleneToxicity.seconds(32 + 7));
        assertEquals(4, NaphthaleneToxicity.seconds(32 + 8));
        assertEquals(4, NaphthaleneToxicity.seconds(32 + 15));
        assertEquals(5, NaphthaleneToxicity.seconds(32 + 16));
        // 满一组（64 个）再带 64 个：超出 96 → 3 + 12
        assertEquals(15, NaphthaleneToxicity.seconds(32 + 96));
    }

    @Test
    void levelGrowsOnePerSixtyFour() {
        assertEquals(0, NaphthaleneToxicity.amplifier(32 + 1));
        assertEquals(0, NaphthaleneToxicity.amplifier(32 + 63));
        assertEquals(1, NaphthaleneToxicity.amplifier(32 + 64), "II 级");
        assertEquals(1, NaphthaleneToxicity.amplifier(32 + 127));
        assertEquals(2, NaphthaleneToxicity.amplifier(32 + 128), "III 级");
        assertEquals(3, NaphthaleneToxicity.amplifier(32 + 192), "IV 级");
    }

    @Test
    void levelCapsAtIV() {
        assertEquals(3, NaphthaleneToxicity.amplifier(32 + 192 + 63));
        assertEquals(3, NaphthaleneToxicity.amplifier(32 + 256), "超出 256 也还是 IV");
        assertEquals(3, NaphthaleneToxicity.amplifier(32 + 10_000));
        // 等级封顶，时长不封顶
        assertEquals(3 + 10_000 / 8, NaphthaleneToxicity.seconds(32 + 10_000));
    }

    @Test
    void fullInventory() {
        // 36 格物品栏 + 副手全是萘：37 × 64 = 2368
        int total = 37 * 64;
        assertEquals(3, NaphthaleneToxicity.amplifier(total));
        assertEquals(3 + (total - 32) / 8, NaphthaleneToxicity.seconds(total));
    }
}
