package moe.qingu.youran.logic.litho;

import moe.qingu.youran.logic.purity.WaferMold;
import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChipQualityTest {
    static ChipQuality.Inputs good() {
        ChipQuality.Inputs in = new ChipQuality.Inputs();
        in.model = ChipModel.Z80;
        in.waferNines = 12;
        in.mold = WaferMold.GOLD;
        in.solvent = 0.9;
        in.alignPermille = 950;
        in.lampBrightness = 0.9;
        in.lampResolutionUm = LithoNumbers.QUARTZ_LAMP_RESOLUTION_UM;
        in.pack = ChipPackage.GOLD;
        return in;
    }

    /** 负胶 2.5 μm 做不了 1 μm 以下（以后的现代芯片）；Z80（4 μm）、8088（3 μm）都做得了 */
    @Test
    void resolutionLimit() {
        ChipQuality.Inputs in = good();
        assertTrue(ChipQuality.effectiveResolution(in) <= ChipModel.I8088.nodeUm);
        in.lampResolutionUm = 3.5;
        in.model = ChipModel.I8088;
        assertEquals(ChipQuality.Reason.RESOLUTION, ChipQuality.evaluate(in, new Random(1)).reason);
    }

    @Test
    void poorAlignmentAlwaysScraps() {
        ChipQuality.Inputs in = good();
        in.alignPermille = LithoNumbers.ALIGN_MIN_PERMILLE - 1;
        assertEquals(ChipQuality.Reason.ALIGNMENT, ChipQuality.evaluate(in, new Random(1)).reason);
    }

    /** 涂过变质（已曝光）的光刻胶：条件再好也一定报废，不看随机数（服主 11-16） */
    @Test
    void exposedResistAlwaysScraps() {
        ChipQuality.Inputs in = good();
        in.waferNines = 30;
        in.solvent = 1;
        in.alignPermille = 1000;
        in.lampBrightness = 1;
        in.exposedResist = true;
        for (long seed = 0; seed < 500; seed++) {
            ChipQuality.Result r = ChipQuality.evaluate(in, new Random(seed));
            assertTrue(r.scrapped());
            assertEquals(ChipQuality.Reason.EXPOSED_RESIST, r.reason);
        }
        in.exposedResist = false;
        assertEquals(ChipQuality.Reason.NONE, ChipQuality.evaluate(in, new Random(0)).reason);
    }

    /** 纯度高、溶剂好、制程粗的存活率高；8088 用负胶比 Z80 难得多 */
    @Test
    void survivalMonotonic() {
        ChipQuality.Inputs a = good();
        ChipQuality.Inputs b = good();
        b.waferNines = 9;
        assertTrue(ChipQuality.survival(a) > ChipQuality.survival(b));
        b = good();
        b.solvent = 0.6;
        assertTrue(ChipQuality.survival(a) > ChipQuality.survival(b));
        b = good();
        b.mold = WaferMold.CLAY;
        assertTrue(ChipQuality.survival(a) > ChipQuality.survival(b));
        b = good();
        b.model = ChipModel.I8088;
        assertTrue(ChipQuality.survival(a) - ChipQuality.survival(b) > 0.2);
    }

    /** 外壳只影响耐热性 */
    @Test
    void packageOnlyAffectsHeat() {
        ChipQuality.Inputs gold = good();
        ChipQuality.Inputs iron = good();
        iron.pack = ChipPackage.IRON;
        gold.waferNines = iron.waferNines = 30;
        gold.solvent = iron.solvent = 1;
        gold.lampBrightness = iron.lampBrightness = 1;
        gold.alignPermille = iron.alignPermille = 1000;
        gold.model = iron.model = ChipModel.Z80;
        ChipQuality.Result g = ChipQuality.evaluate(gold, new Random(7));
        ChipQuality.Result i = ChipQuality.evaluate(iron, new Random(7));
        assertFalse(g.scrapped());
        assertFalse(i.scrapped());
        assertEquals(g.maxKHz, i.maxKHz);
        assertEquals(g.lifetimeSeconds, i.lifetimeSeconds);
        assertTrue(g.heatToleranceKs > i.heatToleranceKs);
    }

    @Test
    void metricsInSaneRange() {
        Random r = new Random(5);
        int ok = 0;
        for (int i = 0; i < 2000; i++) {
            ChipQuality.Result res = ChipQuality.evaluate(good(), r);
            if (!res.scrapped()) {
                ok++;
                assertTrue(res.maxKHz > ChipModel.Z80.standardKHz * 0.6 && res.maxKHz < ChipModel.Z80.standardKHz * 1.8);
                assertTrue(res.heatToleranceKs > 18000 && res.heatToleranceKs < 25000);
                assertTrue(res.lifetimeSeconds > 50 * 3600L);
            }
        }
        double rate = ok / 2000.0;
        assertEquals(ChipQuality.survival(good()), rate, 0.05);
    }

    /** 溶剂 UU 物质最好 */
    @Test
    void uuBestSolvent() {
        for (Solvent s : Solvent.values()) {
            assertTrue(Solvent.UU_MATTER.qualityPermille >= s.qualityPermille);
        }
    }
}
