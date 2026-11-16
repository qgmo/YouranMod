package moe.qingu.youran.logic.litho;

import moe.qingu.youran.logic.purity.WaferMold;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LampSpecTest {
    static LampSpec lamp(LampGlass g, WaferMold m, int nines, LampGas gas) {
        return LampSpec.tube(g, m, nines).assembled(gas).refilled();
    }

    /** 服主 11-16：金铸模的玻璃管好过粘土，两种都比任何纯度的石英管差 */
    @Test
    void tubeTiers() {
        LampSpec clay = lamp(LampGlass.GLASS, WaferMold.CLAY, 1, LampGas.ARGON);
        LampSpec gold = lamp(LampGlass.GLASS, WaferMold.GOLD, 1, LampGas.ARGON);
        LampSpec quartz1 = lamp(LampGlass.QUARTZ, null, 1, LampGas.ARGON);
        assertTrue(gold.baseBrightness() > clay.baseBrightness());
        assertTrue(gold.life() > clay.life());
        assertTrue(quartz1.baseBrightness() > gold.baseBrightness());
        assertTrue(quartz1.life() > gold.life());
        // 低档管配氙也比不上 1 个 9 的石英管配氩
        LampSpec goldXe = lamp(LampGlass.GLASS, WaferMold.GOLD, 1, LampGas.XENON);
        assertTrue(quartz1.baseBrightness() > goldXe.baseBrightness());
        assertTrue(quartz1.life() > goldXe.life());
    }

    @Test
    void morePurityBetterUpToCap() {
        LampSpec q2 = lamp(LampGlass.QUARTZ, null, 2, LampGas.ARGON);
        LampSpec q3 = lamp(LampGlass.QUARTZ, null, 3, LampGas.ARGON);
        assertTrue(q3.baseBrightness() > q2.baseBrightness());
        assertTrue(q3.life() > q2.life());
        assertEquals(lamp(LampGlass.QUARTZ, null, LithoNumbers.QUARTZ_LAMP_MAX_NINES, LampGas.ARGON).life(),
                lamp(LampGlass.QUARTZ, null, 20, LampGas.ARGON).life());
    }

    @Test
    void xenonBetterThanArgon() {
        LampSpec ar = lamp(LampGlass.QUARTZ, null, 2, LampGas.ARGON);
        LampSpec xe = lamp(LampGlass.QUARTZ, null, 2, LampGas.XENON);
        assertTrue(xe.baseBrightness() > ar.baseBrightness());
        assertTrue(xe.life() > ar.life());
    }

    /** 汞：新组装的灯没汞不亮；补满后每 50 次曝光用 1 mB；用完不亮，补了又亮 */
    @Test
    void mercuryDepletesAndRefills() {
        LampSpec s = LampSpec.tube(LampGlass.QUARTZ, null, 3).assembled(LampGas.ARGON);
        assertEquals(0, s.mercury);
        assertEquals(0, s.brightness(), 1e-9);
        s = s.refilled();
        assertEquals(LithoNumbers.LAMP_MERCURY, s.mercury);
        for (int i = 0; i < LithoNumbers.MERCURY_EXPOSURES_PER_MB; i++) {
            s = s.afterExposure();
        }
        assertEquals(LithoNumbers.LAMP_MERCURY - 1, s.mercury);
        assertEquals(1, s.mercurySpace());
        for (int i = 0; i < LithoNumbers.MERCURY_EXPOSURES_PER_MB * LithoNumbers.LAMP_MERCURY; i++) {
            s = s.afterExposure();
        }
        assertEquals(0, s.mercury);
        assertEquals(-1, s.exposeTicks());
        s = s.refilled();
        assertTrue(s.brightness() > 0 || s.burntOut());
    }

    @Test
    void dimsWithUseThenBurnsOut() {
        LampSpec s = lamp(LampGlass.GLASS, WaferMold.GOLD, 1, LampGas.ARGON);
        double fresh = s.brightness();
        LampSpec half = new LampSpec(s.glass, s.mold, s.nines, s.gas, s.life() / 2, 10, 0);
        assertTrue(half.brightness() < fresh);
        assertTrue(half.exposeTicks() > s.exposeTicks());
        LampSpec dead = new LampSpec(s.glass, s.mold, s.nines, s.gas, s.life(), 10, 0);
        assertTrue(dead.burntOut());
        assertEquals(0, dead.brightness(), 1e-9);
    }
}
