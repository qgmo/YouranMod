package moe.qingu.youran.logic.litho;

/**
 * DUV 一档（ArF 准分子激光光源、激光对准、氦氖激光管、改良正胶、YR-2000）的数值，集中在这里。
 * 每项注明是服主定的还是 Claude 定的（Claude 11-16 定的一版，服主之后再调）；交付文档（DUV与激光对准-实现.md）有一张表与这里一一对应。
 * 纯常量，不依赖 Minecraft。
 */
public final class DuvNumbers {
    private DuvNumbers() {
    }

    // ================= ArF 激光腔 / 准分子激光光源 =================

    /** 光源的分辨率（μm）：服主 11-16 定 0.05 μm；石英管纯度、氟化钙不影响分辨率 */
    public static final double EXCIMER_RESOLUTION_UM = 0.05;
    /** 曝光耗电（FE/tick）：服主 11-16 定 1024（汞灯 256 的 4 倍） */
    public static final int EXCIMER_FE_EXPOSE = 1024;

    /** 最佳配比（服主 11-16）：1 氟 : 16 氖 : 250 mB 氩；同比例放大也算最佳 */
    public static final double BEST_FLUORINE = 1;
    public static final double BEST_NEON = 16;
    public static final double BEST_ARGON_MB = 250;
    /** 灌氩（服主 11-16）：每次 10 mB，最多 750 mB */
    public static final int ARGON_PER_FILL = 10;
    public static final int ARGON_MAX_MB = 750;
    /** 每个氟管多少次曝光（服主 11-16：500 次，按小数扣）；腔里的氟按千分之一个记（每次扣 {@value #FLUORINE_MILLI_PER_EXPOSURE}） */
    public static final int FLUORINE_EXPOSURES = 500;
    public static final int MILLI = 1000;
    public static final int FLUORINE_MILLI_PER_EXPOSURE = MILLI / FLUORINE_EXPOSURES;

    /*
     * 配比 → 输出（Claude 定）：把三样各除以最佳配比里的份数（氟 ÷ 1、氖 ÷ 16、氩 ÷ 250 mB），得到 uF、uNe、uAr，
     * 再除以三者的平均数得到相对份额 dF、dNe、dAr（同比例放大不变，最佳时都是 1）。输出 = gF × gNe × gAr：
     *   gF  = dF ≤ 1 ? dF^0.7 : exp(−4.5 × (dF − 1))      氟偏多掉得最狠（多余的氟吸收 193 nm）
     *   gNe = dNe ≥ 1 ? 1 / (1 + 0.3 × (dNe − 1)) : 0.3 + 0.7 × dNe^1.2   氖偏少放电不稳；没有氖还剩 30%
     *   gAr = dAr ≥ 1 ? 1 / (1 + 0.3 × (dAr − 1)) : dAr     没有氩就没有 ArF*，不出光
     * 没有氟、没有氩：0。
     */
    public static final double GAS_F_LOW_EXP = 0.7;
    public static final double GAS_F_HIGH_K = 4.5;
    public static final double GAS_EXCESS_K = 0.3;
    public static final double GAS_NE_FLOOR = 0.3;
    public static final double GAS_NE_LOW_EXP = 1.2;

    /**
     * 窗口（石英管）定亮度与寿命（Claude 定，同汞灯的做法）：1 个 9 的基准亮度，每多一个 9 离 1000 的差减半；
     * 寿命（曝光次数，窗口与电极老化）= 3000 + 1500 × (几个 9 − 1)，到 6 个 9 为止。氟化钙窗口镜片没有档次，算在基准里。
     */
    public static final int CAVITY_BRIGHTNESS_1 = 700;
    public static final int CAVITY_LIFE_1 = 3000;
    public static final int CAVITY_LIFE_PER_NINE = 1500;
    public static final int CAVITY_MAX_NINES = 6;
    /** 用到寿命时亮度剩多少（同汞灯 0.4），之后窗口坏了不出光 */
    public static final double CAVITY_END_BRIGHTNESS = 0.4;

    // ================= 氦氖激光管 =================

    /** 一根管最多装多少个元素（氦 + 氖合计，服主 11-16 定 250） */
    public static final int HENE_MAX_ELEMENTS = 250;
    /** 最佳配比（服主 11-16）：10 氦 : 1 氖 */
    public static final double HENE_BEST_RATIO = 10;
    /** 配比偏离的输出（Claude 定）：q = 氦 ÷ (10 × 氖)，输出 = exp(−k × ln(q)²)；q = 0.5 或 2 时约 0.56 */
    public static final double HENE_RATIO_K = 1.2;
    /**
     * 氦穿过玻璃漏掉（Claude 定）：氦按半衰期指数减少（游戏刻，24000 = 一个游戏日）。
     * 石英管最快（20 个游戏日），粘土铸模的玻璃管 120 个游戏日，金铸模 240 个游戏日。放着不用也照样漏。
     */
    public static final long HENE_HALF_LIFE_QUARTZ = 20L * 24000;
    public static final long HENE_HALF_LIFE_CLAY = 120L * 24000;
    public static final long HENE_HALF_LIFE_GOLD = 240L * 24000;

    // ================= 氖、改良正胶 =================

    /** 沉浸原油精馏塔分离空气（服主 11-16）：每 100000 mB 空气平均出 1.8 个氖元素（按概率掉的副产物品） */
    public static final float AIR_NEON_PER_100000 = 1.8F;
    /** 改良正胶（服主 11-16）：1000 mB 正胶 + 10 mB UU 物质 → 1000 mB 改良正胶 */
    public static final int IMPROVED_RESIST_MB = 1000;
    public static final int IMPROVED_UU_MB = 10;
    /** 沉浸工程炼油厂做改良正胶的电耗（FE/tick，同本模组其他炼油厂配方） */
    public static final int IMPROVED_REFINERY_ENERGY = 80;

    // ================= 激光对准 =================

    /** 捕获范围（服主 11-16：平移约 ±1 μm，转角相应放宽）：平移 ±1 μm；转角 ±75 μrad（在标记处约 1.5 μm，Claude 定） */
    public static final double CAPTURE_SHIFT_UM = 1.0;
    public static final double CAPTURE_ROTATION_URAD = 75;
    /** 收敛后的残差（服主 11-16：约 0.01 μm）：满强度的激光收到 0.005–0.01 μm；激光强度 s 时 ÷ s（Claude 定） */
    public static final double LASER_RESIDUAL_UM = 0.01;
    /** 收敛时间（Claude 定）：标准频率 4.77 MHz 的 8088、满强度激光 60 tick（3 秒）；× (4773 kHz ÷ CPU 频率) ÷ 激光强度 */
    public static final int LASER_CONVERGE_TICKS = 60;
    public static final int LASER_REFERENCE_KHZ = 4773;
    /** 收敛不会慢过这个（tick），免得很弱的激光等一辈子（30 秒） */
    public static final int LASER_CONVERGE_MAX_TICKS = 600;
    /** 收敛时每 tick 耗电（FE，Claude 定）；锁定后跟踪不耗电 */
    public static final int LASER_FE_PER_TICK = 64;
    /** 激光强度低于这个算不出光 */
    public static final double LASER_MIN_STRENGTH = 0.01;
    /** 锁定后每隔多少 tick 查一次主板、程序、激光还在不在 */
    public static final int LASER_CHECK_INTERVAL = 20;
}
