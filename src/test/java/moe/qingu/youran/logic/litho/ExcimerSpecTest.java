package moe.qingu.youran.logic.litho;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** ArF 激光腔（服主 11-16）：配比 → 输出、氟的消耗、灌氩 */
class ExcimerSpecTest {
    static final double EPS = 1e-9;

    /** 最佳配比 1 氟 : 16 氖 : 250 mB 氩，同比例放大也算最佳 */
    @Test
    void bestRatioAndScaling() {
        assertEquals(1, ExcimerSpec.gasOutput(1, 16, 250), EPS);
        assertEquals(1, ExcimerSpec.gasOutput(2, 32, 500), EPS);
        assertEquals(1, ExcimerSpec.gasOutput(3, 48, 750), EPS);
        assertEquals(1, ExcimerSpec.gasOutput(0.5, 8, 125), EPS);
    }

    /** 没有氟不出光；没有氩也不出（没有 ArF*） */
    @Test
    void noFluorineNoLight() {
        assertEquals(0, ExcimerSpec.gasOutput(0, 48, 750), EPS);
        assertEquals(0, ExcimerSpec.gasOutput(3, 48, 0), EPS);
        assertTrue(new ExcimerSpec(3, 0, 48, 750, 0).noFuel());
        assertTrue(ExcimerSpec.gasOutput(3, 0, 750) > 0, "没有氖放电不稳，但还出一点");
    }

    /** 偏离最佳变弱；同样偏 50%，氟偏多掉得最狠，氖偏少也变弱 */
    @Test
    void deviationsWeaken() {
        double fHigh = ExcimerSpec.gasOutput(1.5, 16, 250);
        double fLow = ExcimerSpec.gasOutput(0.5, 16, 250);
        double neLow = ExcimerSpec.gasOutput(1, 8, 250);
        double neHigh = ExcimerSpec.gasOutput(1, 24, 250);
        double arLow = ExcimerSpec.gasOutput(1, 16, 125);
        double arHigh = ExcimerSpec.gasOutput(1, 16, 375);
        for (double v : new double[]{fHigh, fLow, neLow, neHigh, arLow, arHigh}) {
            assertTrue(v < 1 && v > 0, String.valueOf(v));
        }
        for (double v : new double[]{fLow, neLow, neHigh, arLow, arHigh}) {
            assertTrue(fHigh < v, "氟偏多应掉得最狠：" + fHigh + " vs " + v);
        }
        assertTrue(neLow < neHigh, "氖偏少比偏多弱");
        // 氟越多越差（过了最佳以后单调下降）
        assertTrue(ExcimerSpec.gasOutput(2, 16, 250) < fHigh);
    }

    /** 每个氟管 500 次曝光（按小数扣），氖、氩不耗；满腔 3 氟正好 1500 次，之后不出光；输出跟着氟少慢慢降 */
    @Test
    void fluorineConsumption() {
        ExcimerSpec s = new ExcimerSpec(6, 3000, 48, 750, 0);
        double last = s.gasOutput();
        assertEquals(1, last, EPS);
        int n = 0;
        while (!s.noFuel()) {
            s = s.afterExposure();
            n++;
            assertTrue(s.gasOutput() <= last + EPS, "输出不该随用变强");
            last = s.gasOutput();
            assertEquals(48, s.neon);
            assertEquals(750, s.argonMb);
        }
        assertEquals(3 * DuvNumbers.FLUORINE_EXPOSURES, n);
        assertEquals(0, s.fluorineMilli);
        ExcimerSpec three = new ExcimerSpec(6, 3000, 48, 750, 0);
        for (int i = 0; i < DuvNumbers.FLUORINE_EXPOSURES; i++) {
            three = three.afterExposure();
        }
        assertEquals(2, three.fluorine(), EPS);
        assertTrue(three.gasOutput() < 1);
        // 补气只补氟：补一个回去又是最佳
        assertEquals(1, three.plusFluorine().gasOutput(), EPS);
    }

    /** 灌氩：每次最多 10 mB，到 750 mB 为止 */
    @Test
    void argonFilling() {
        ExcimerSpec s = ExcimerSpec.empty(2);
        assertEquals(10, s.argonFill(1000));
        assertEquals(5, s.argonFill(5));
        ExcimerSpec full = new ExcimerSpec(2, 0, 0, 745, 0);
        assertEquals(5, full.argonFill(1000));
        assertEquals(0, full.withArgon(5).argonFill(1000));
        assertEquals(750, new ExcimerSpec(2, 0, 0, 9999, 0).argonMb);
    }

    /** 石英管纯度、氟化钙影响亮度与寿命，不影响分辨率；耗电 1024 FE/tick */
    @Test
    void windowAffectsBrightnessNotResolution() {
        ExcimerSpec low = new ExcimerSpec(1, 1000, 16, 250, 0);
        ExcimerSpec high = new ExcimerSpec(4, 1000, 16, 250, 0);
        assertTrue(high.brightness() > low.brightness());
        assertTrue(high.life() > low.life());
        assertEquals(DuvNumbers.EXCIMER_RESOLUTION_UM, low.resolutionUm(), EPS);
        assertEquals(DuvNumbers.EXCIMER_RESOLUTION_UM, high.resolutionUm(), EPS);
        assertEquals(1024, low.exposeFePerTick());
        assertEquals(0.05, low.resolutionUm(), EPS);
        ExcimerSpec worn = new ExcimerSpec(1, 1000, 16, 250, low.life());
        assertTrue(worn.burntOut());
        assertEquals(0, worn.brightness(), EPS);
        assertEquals(-1, worn.exposeTicks());
        assertTrue(low.exposeTicks() > high.exposeTicks());
    }
}
