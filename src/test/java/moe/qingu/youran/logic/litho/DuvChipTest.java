package moe.qingu.youran.logic.litho;

import moe.qingu.youran.item.ChipNbt;
import moe.qingu.youran.logic.purity.WaferMold;
import net.minecraft.nbt.NBTTagCompound;
import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** YR-2000（服主 11-16）、改良正胶、对准时被 DUV 照了的胶 */
class DuvChipTest {
    /** 别的条件都最好，只看光源和胶 */
    static ChipQuality.Inputs ideal(ResistType resist, double lampResolution) {
        ChipQuality.Inputs in = new ChipQuality.Inputs();
        in.model = ChipModel.YR2000;
        in.waferNines = 30;
        in.mold = WaferMold.GOLD;
        in.resist = resist;
        in.solvent = 1;
        in.alignPermille = 1000;
        in.lampBrightness = 1;
        in.lampResolutionUm = lampResolution;
        in.maskWear = 0;
        in.pack = ChipPackage.GOLD;
        return in;
    }

    @Test
    void model() {
        assertSame(ChipModel.YR2000, ChipModel.byId("yr2000"));
        assertSame(ChipModel.YR2000, ChipModel.byMeta(10));
        assertSame(ChipModel.ROM_LASER_ALIGN, ChipModel.byMeta(11));
        assertTrue(ChipModel.YR2000.isCpu());
        assertTrue(ChipModel.ROM_LASER_ALIGN.isRom());
        assertEquals(0.13, ChipModel.YR2000.nodeUm, 0);
        assertEquals(10, ChipModel.YR2000.layers);
        assertEquals(2_000_000, ChipModel.YR2000.ratedKHz);
        assertEquals(1_600_000, ChipModel.YR2000.minKHz);
        assertEquals(2_600_000, ChipModel.YR2000.maxAdjustKHz);
        // 公差 = 制程 × 0.5 = 0.065 μm（照现有规则）
        assertEquals(0.065, ChipModel.YR2000.toleranceUm(), 1e-12);
        assertEquals("2.00 GHz", ChipModel.formatFreq(2_000_000));
        assertEquals("4.77 MHz", ChipModel.formatFreq(4773));
    }

    /** 改良正胶分辨率 0.05 μm；正胶仍 0.8 μm；正胶、改良正胶都按正性胶显影 */
    @Test
    void improvedResist() {
        assertEquals(0.05, ResistType.IMPROVED_POSITIVE.resolutionUm, 0);
        assertEquals(0.8, ResistType.POSITIVE.resolutionUm, 0);
        assertSame(ResistType.IMPROVED_POSITIVE, ResistType.byId("improved_positive"));
        assertTrue(ResistType.POSITIVE.positiveTone());
        assertTrue(ResistType.IMPROVED_POSITIVE.positiveTone());
        assertTrue(!ResistType.NEGATIVE.positiveTone());
        assertSame(ResistType.POSITIVE, ResistType.worse(ResistType.POSITIVE, ResistType.IMPROVED_POSITIVE));
    }

    /** YR-2000 用汞灯、负胶、正胶一定报废（分辨率不够），不看随机数 */
    @Test
    void yr2000NeedsDuvAndImprovedResist() {
        Object[][] cases = {
                {ResistType.IMPROVED_POSITIVE, LithoNumbers.QUARTZ_LAMP_RESOLUTION_UM},   // 汞灯（石英）
                {ResistType.IMPROVED_POSITIVE, LithoNumbers.GLASS_LAMP_RESOLUTION_UM},    // 汞灯（玻璃）
                {ResistType.NEGATIVE, DuvNumbers.EXCIMER_RESOLUTION_UM},                  // 负胶
                {ResistType.POSITIVE, DuvNumbers.EXCIMER_RESOLUTION_UM},                  // 正胶
        };
        for (Object[] c : cases) {
            ChipQuality.Inputs in = ideal((ResistType) c[0], (Double) c[1]);
            for (long seed = 0; seed < 300; seed++) {
                assertSame(ChipQuality.Reason.RESOLUTION, ChipQuality.evaluate(in, new Random(seed)).reason, c[0] + " / " + c[1]);
            }
        }
    }

    /** DUV + 改良正胶：分辨率这项报废约 2%（0.9 × (0.05 ÷ 0.13)^4），别的条件都最好时总报废率约 2% */
    @Test
    void duvWithImprovedResistScrapsAbout2Percent() {
        ChipQuality.Inputs in = ideal(ResistType.IMPROVED_POSITIVE, DuvNumbers.EXCIMER_RESOLUTION_UM);
        double resolutionScrap = LithoNumbers.SCRAP_RESOLUTION_FACTOR * Math.pow(0.05 / 0.13, 4);
        assertEquals(0.0197, resolutionScrap, 0.0005);
        double scrap = 1 - ChipQuality.survival(in);
        assertTrue(scrap > 0.018 && scrap < 0.023, String.valueOf(scrap));
        Random r = new Random(2026);
        int n = 20_000;
        int bad = 0;
        for (int i = 0; i < n; i++) {
            ChipQuality.Result res = ChipQuality.evaluate(in, r);
            if (res.scrapped()) {
                assertSame(ChipQuality.Reason.DEFECT, res.reason);
                bad++;
            }
        }
        double rate = bad / (double) n;
        assertTrue(rate > 0.016 && rate < 0.025, String.valueOf(rate));
    }

    /** 好片的最大频率围绕 2 GHz，NBT 带 CPU 的标准频率与可调范围 */
    @Test
    void goodChipNbt() {
        ChipQuality.Inputs in = ideal(ResistType.IMPROVED_POSITIVE, DuvNumbers.EXCIMER_RESOLUTION_UM);
        Random r = new Random(1);
        ChipQuality.Result res = ChipQuality.evaluate(in, r);
        while (res.scrapped()) {
            res = ChipQuality.evaluate(in, r);
        }
        assertTrue(res.maxKHz > 1_600_000 && res.maxKHz < 3_200_000, String.valueOf(res.maxKHz));
        NBTTagCompound t = ChipNbt.tag(ChipModel.YR2000, "YR2000-1", res, new NBTTagCompound());
        assertEquals(2_000_000, t.getInteger(ChipNbt.STD_FREQ));
        assertEquals(2_600_000, t.getInteger(ChipNbt.MAX_ADJ_FREQ));
    }

    /** 对准用的激光是 DUV、把涂好的胶照了：条件再好也一定报废（同变质胶） */
    @Test
    void alignExposedAlwaysScraps() {
        ChipQuality.Inputs in = ideal(ResistType.IMPROVED_POSITIVE, DuvNumbers.EXCIMER_RESOLUTION_UM);
        in.alignExposed = true;
        for (long seed = 0; seed < 300; seed++) {
            assertSame(ChipQuality.Reason.ALIGN_EXPOSED, ChipQuality.evaluate(in, new Random(seed)).reason);
        }
        in.model = ChipModel.Z80;
        assertSame(ChipQuality.Reason.ALIGN_EXPOSED, ChipQuality.evaluate(in, new Random(0)).reason);
    }
}
