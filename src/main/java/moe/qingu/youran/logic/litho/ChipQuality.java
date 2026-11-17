package moe.qingu.youran.logic.litho;

import moe.qingu.youran.logic.purity.Purity;
import moe.qingu.youran.logic.purity.WaferMold;

import java.util.Random;

/**
 * 芯片的出厂报废与三项指标（服主 11-15 定因素，公式 Claude 11-16 定一版）。纯逻辑。
 *
 * <p>报废：
 * <ul>
 *     <li>有效分辨率（光刻胶与汞灯里差的那个）比掩模制程还粗：一定报废；</li>
 *     <li>对准率低于 {@link LithoNumbers#ALIGN_MIN_PERMILLE}：一定报废；</li>
 *     <li>否则按各项独立的“存活率”相乘：晶圆纯度 × 铸模、分辨率与制程之比、溶剂、汞灯亮度、掩模磨损、对准率。</li>
 * </ul>
 * 没报废的：溶剂、纯度、对准率影响最大频率与寿命；对准率、外壳影响耐热性。
 */
public final class ChipQuality {
    private ChipQuality() {
    }

    public enum Reason {
        NONE, RESOLUTION, ALIGNMENT, DEFECT,
        /** 涂的是变质（已曝光）的光刻胶：一定报废（服主 11-16） */
        EXPOSED_RESIST,
        /** 对准时激光源用的是 DUV（ArF 激光腔 / 准分子激光光源），把涂好的胶照了：一定报废（服主 11-16） */
        ALIGN_EXPOSED
    }

    /** 做这片芯片时记下的条件 */
    public static final class Inputs {
        public ChipModel model = ChipModel.Z80;
        public int waferNines = Purity.WAFER_GRADE_NINES;
        public WaferMold mold = WaferMold.GOLD;
        public ResistType resist = ResistType.NEGATIVE;
        /** 有哪一层涂的是变质（已曝光）的光刻胶 */
        public boolean exposedResist;
        /** 有哪一层涂好胶以后被对准用的 DUV 激光照过 */
        public boolean alignExposed;
        /** 各层显影里最差的溶剂品质（0–1） */
        public double solvent = 1;
        /** 各层曝光时最差的对准率（千分比） */
        public int alignPermille = 1000;
        /** 各层曝光时最暗的汞灯亮度（0–1） */
        public double lampBrightness = 1;
        /** 汞灯的分辨率（μm） */
        public double lampResolutionUm = LithoNumbers.QUARTZ_LAMP_RESOLUTION_UM;
        /** 掩模磨损（0–1） */
        public double maskWear;
        public ChipPackage pack = ChipPackage.IRON;
    }

    public static final class Result {
        public final Reason reason;
        /** 耐热性（K·s，最近 1 分钟温度对时间积分的上限） */
        public final int heatToleranceKs;
        /** 最大频率（kHz） */
        public final int maxKHz;
        /** 寿命（开机秒数） */
        public final long lifetimeSeconds;

        Result(Reason reason, int heatToleranceKs, int maxKHz, long lifetimeSeconds) {
            this.reason = reason;
            this.heatToleranceKs = heatToleranceKs;
            this.maxKHz = maxKHz;
            this.lifetimeSeconds = lifetimeSeconds;
        }

        public boolean scrapped() {
            return reason != Reason.NONE;
        }
    }

    /** 有效分辨率（μm）：光刻胶与汞灯里差的那个 */
    public static double effectiveResolution(Inputs in) {
        double resist = in.resist == null ? ResistType.NEGATIVE.resolutionUm : in.resist.resolutionUm;
        return Math.max(resist, in.lampResolutionUm);
    }

    /** 晶圆纯度这一项的报废率 */
    public static double purityScrap(int nines, WaferMold mold) {
        int n = Purity.sanitize(nines);
        if (n < Purity.WAFER_GRADE_NINES) {
            return 1;
        }
        int factor = mold == null ? WaferMold.CLAY.scrapFactorPercent : mold.scrapFactorPercent;
        return Math.min(1, LithoNumbers.SCRAP_PURITY_BASE * factor / 100.0 * Math.pow(0.8, n - Purity.WAFER_GRADE_NINES));
    }

    /** 不算“一定报废”的情况，这片芯片的存活率（0–1） */
    public static double survival(Inputs in) {
        double res = effectiveResolution(in);
        double sP = 1 - purityScrap(in.waferNines, in.mold);
        double sR = 1 - LithoNumbers.SCRAP_RESOLUTION_FACTOR * Math.pow(Math.min(1, res / in.model.nodeUm), 4);
        double sS = 1 - LithoNumbers.SCRAP_SOLVENT * (1 - clamp01(in.solvent));
        double sL = 1 - LithoNumbers.SCRAP_LAMP * (1 - clamp01(in.lampBrightness));
        double wear = clamp01(in.maskWear);
        double sM = 1 - LithoNumbers.SCRAP_MASK * wear * wear;
        double sA = 1 - LithoNumbers.SCRAP_ALIGN * (1 - in.alignPermille / 1000.0);
        return clamp01(sP * sR * sS * sL * sM * sA);
    }

    /** 纯度的品质（0–1）：9 个 9 为 0，19 个 9 起为 1 */
    static double purityQuality(int nines) {
        return clamp01((Purity.sanitize(nines) - Purity.WAFER_GRADE_NINES) / 10.0);
    }

    /** 对准的品质（0–1）：报废线为 0，100% 为 1 */
    static double alignQuality(int permille) {
        double min = LithoNumbers.ALIGN_MIN_PERMILLE;
        return clamp01((permille - min) / (1000 - min));
    }

    public static Result evaluate(Inputs in, Random random) {
        if (in.exposedResist) {
            return new Result(Reason.EXPOSED_RESIST, 0, 0, 0);
        }
        if (in.alignExposed) {
            return new Result(Reason.ALIGN_EXPOSED, 0, 0, 0);
        }
        if (effectiveResolution(in) > in.model.nodeUm) {
            return new Result(Reason.RESOLUTION, 0, 0, 0);
        }
        if (in.alignPermille < LithoNumbers.ALIGN_MIN_PERMILLE) {
            return new Result(Reason.ALIGNMENT, 0, 0, 0);
        }
        if (random.nextDouble() >= survival(in)) {
            return new Result(Reason.DEFECT, 0, 0, 0);
        }
        double q = clamp01(in.solvent);
        double p = purityQuality(in.waferNines);
        double a = alignQuality(in.alignPermille);
        double pk = in.pack == null ? 0 : in.pack.quality();
        double freq = in.model.ratedKHz * (0.75 + 0.25 * q + 0.2 * p + 0.3 * a) * noise(random);
        double hours = (LithoNumbers.LIFETIME_MIN_HOURS + LithoNumbers.LIFETIME_SPAN_HOURS * (0.375 * q + 0.375 * p + 0.25 * a)) * noise(random);
        double heat = 60 * (LithoNumbers.HEAT_BASE_K + LithoNumbers.HEAT_PACKAGE_K * pk + LithoNumbers.HEAT_ALIGN_K * a) * noise(random);
        return new Result(Reason.NONE, (int) Math.round(heat), (int) Math.round(freq), Math.round(hours * 3600));
    }

    private static double noise(Random random) {
        return 1 + random.nextGaussian() * LithoNumbers.METRIC_NOISE;
    }

    private static double clamp01(double v) {
        return Math.max(0, Math.min(1, v));
    }
}
