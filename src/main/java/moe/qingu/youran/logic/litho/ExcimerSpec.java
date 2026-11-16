package moe.qingu.youran.logic.litho;

import moe.qingu.youran.logic.purity.Purity;

/**
 * ArF 激光腔（服主 11-16）的数据与公式，激光腔物品、准分子激光光源方块共用（纯逻辑）。只存原始量：窗口石英管的纯度（几个 9）、
 * 腔里的氟（千分之一个）、氖（个）、氩（mB）、已用量（曝光次数）；输出、亮度、寿命都现算。
 *
 * <ul>
 *     <li>配比（服主 11-16）：最佳 1 氟 : 16 氖 : 250 mB 氩，同比例放大也算最佳；偏离变弱，氟偏多掉得最狠、氖偏少变弱、没氟不出光
 *     （{@link #gasOutput}，公式见 {@link DuvNumbers}）；</li>
 *     <li>氟每曝光一次扣 1/500 个（服主 11-16），氖、氩不耗；氟少了配比偏离，输出自然下降，不另写老化；</li>
 *     <li>窗口：石英管纯度定基准亮度与寿命（Claude 定，同汞灯），到寿命窗口坏了；不影响分辨率（0.05 μm）。</li>
 * </ul>
 */
public final class ExcimerSpec implements LightSpec {
    /** 窗口石英管的纯度（几个 9） */
    public final int nines;
    /** 腔里的氟（千分之一个） */
    public final int fluorineMilli;
    /** 腔里的氖（个） */
    public final int neon;
    /** 腔里的氩（mB） */
    public final int argonMb;
    /** 已用量：累计曝光次数 */
    public final int used;

    public ExcimerSpec(int nines, int fluorineMilli, int neon, int argonMb, int used) {
        this.nines = Purity.sanitize(nines);
        this.fluorineMilli = Math.max(0, fluorineMilli);
        this.neon = Math.max(0, neon);
        this.argonMb = Math.max(0, Math.min(DuvNumbers.ARGON_MAX_MB, argonMb));
        this.used = Math.max(0, used);
    }

    /** 刚装出来的空激光腔（窗口是这根石英管） */
    public static ExcimerSpec empty(int quartzNines) {
        return new ExcimerSpec(quartzNines, 0, 0, 0, 0);
    }

    /** 氟（个，带小数） */
    public double fluorine() {
        return fluorineMilli / (double) DuvNumbers.MILLI;
    }

    /** 还能灌多少 mB 氩 */
    public int argonSpace() {
        return DuvNumbers.ARGON_MAX_MB - argonMb;
    }

    /** 灌一次氩（最多 {@value DuvNumbers#ARGON_PER_FILL} mB，到 750 mB 为止）能灌多少 mB */
    public int argonFill(int offered) {
        return Math.max(0, Math.min(Math.min(offered, DuvNumbers.ARGON_PER_FILL), argonSpace()));
    }

    public ExcimerSpec withArgon(int addMb) {
        return new ExcimerSpec(nines, fluorineMilli, neon, argonMb + addMb, used);
    }

    /** 还能放氟：不到 {@value DuvNumbers#FLUORINE_MAX} 个（被消耗成小数后也能再放，补到 3 为止） */
    public boolean fluorineRoom() {
        return fluorineMilli < DuvNumbers.FLUORINE_MAX * DuvNumbers.MILLI;
    }

    /** 还能放氖：不到 {@value DuvNumbers#NEON_MAX} 个 */
    public boolean neonRoom() {
        return neon < DuvNumbers.NEON_MAX;
    }

    /** 放进一个氟元素，最多到 3 个（2.4 个时放一个是 3 个） */
    public ExcimerSpec plusFluorine() {
        return new ExcimerSpec(nines, Math.min(DuvNumbers.FLUORINE_MAX * DuvNumbers.MILLI, fluorineMilli + DuvNumbers.MILLI), neon, argonMb, used);
    }

    /** 放进一个氖元素，最多到 48 个 */
    public ExcimerSpec plusNeon() {
        return new ExcimerSpec(nines, fluorineMilli, Math.min(DuvNumbers.NEON_MAX, neon + 1), argonMb, used);
    }

    /** 配比决定的输出（0–1）：最佳为 1，三样同比例放大不变 */
    public double gasOutput() {
        return gasOutput(fluorine(), neon, argonMb);
    }

    /** 配比决定的输出（0–1），公式见 {@link DuvNumbers} */
    public static double gasOutput(double fluorine, double neon, double argonMb) {
        if (fluorine <= 0 || argonMb <= 0) {
            return 0;
        }
        double uF = fluorine / DuvNumbers.BEST_FLUORINE;
        double uNe = Math.max(0, neon) / DuvNumbers.BEST_NEON;
        double uAr = argonMb / DuvNumbers.BEST_ARGON_MB;
        double mean = (uF + uNe + uAr) / 3;
        double dF = uF / mean;
        double dNe = uNe / mean;
        double dAr = uAr / mean;
        double gF = dF <= 1 ? Math.pow(dF, DuvNumbers.GAS_F_LOW_EXP) : Math.exp(-DuvNumbers.GAS_F_HIGH_K * (dF - 1));
        double gNe = dNe >= 1 ? 1 / (1 + DuvNumbers.GAS_EXCESS_K * (dNe - 1))
                : DuvNumbers.GAS_NE_FLOOR + (1 - DuvNumbers.GAS_NE_FLOOR) * Math.pow(dNe, DuvNumbers.GAS_NE_LOW_EXP);
        double gAr = dAr >= 1 ? 1 / (1 + DuvNumbers.GAS_EXCESS_K * (dAr - 1)) : dAr;
        return Math.max(0, Math.min(1, gF * gNe * gAr));
    }

    int effectiveNines() {
        return Math.min(nines, DuvNumbers.CAVITY_MAX_NINES);
    }

    /** 新窗口的基准亮度（千分比）：1 个 9 是 700，每多一个 9 离 1000 的差减半 */
    public int windowBrightness() {
        double gap = 1000 - DuvNumbers.CAVITY_BRIGHTNESS_1;
        return (int) Math.round(1000 - gap * Math.pow(0.5, effectiveNines() - 1));
    }

    /** 寿命（曝光次数） */
    public int life() {
        return DuvNumbers.CAVITY_LIFE_1 + DuvNumbers.CAVITY_LIFE_PER_NINE * (effectiveNines() - 1);
    }

    @Override
    public boolean burntOut() {
        return used >= life();
    }

    /** 不出光：没氟、配比不出光 */
    @Override
    public boolean noFuel() {
        return gasOutput() <= 0;
    }

    /** 输出强度（0–1，不算窗口）：给激光对准当激光源时用，也是物品提示里的“预计输出” */
    public double strength() {
        return burntOut() ? 0 : gasOutput() * windowFactor();
    }

    /** 窗口：基准亮度随用量线性变暗，到寿命时剩 {@link DuvNumbers#CAVITY_END_BRIGHTNESS} */
    double windowFactor() {
        double wear = used / (double) life();
        return windowBrightness() / 1000.0 * (1 - (1 - DuvNumbers.CAVITY_END_BRIGHTNESS) * Math.min(1, wear));
    }

    /** 现在的亮度（0–1）：窗口 × 配比；窗口坏了为 0 */
    @Override
    public double brightness() {
        return strength();
    }

    @Override
    public double resolutionUm() {
        return DuvNumbers.EXCIMER_RESOLUTION_UM;
    }

    @Override
    public int exposeFePerTick() {
        return DuvNumbers.EXCIMER_FE_EXPOSE;
    }

    /** 曝光一次：已用量加 1，氟扣 1/500 个（扣到 0 为止） */
    @Override
    public ExcimerSpec afterExposure() {
        return new ExcimerSpec(nines, fluorineMilli - DuvNumbers.FLUORINE_MILLI_PER_EXPOSURE, neon, argonMb, used + 1);
    }
}
