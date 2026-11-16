package moe.qingu.youran.logic.resist;

/**
 * 正胶线的数值（服主 11-16，光刻.md“正胶”）。每项注明服主定的还是 Claude 定的；
 * “服主定、以后可调”的都集中在这里，改数不用动别的类。
 */
public final class ResistNumbers {
    // ---- 萘 ----
    /** 萘的来源：精馏塔每处理一份（100 mB 杂酚油，和原有的芳烃 50 mB、甲酚 25 mB 同一份）掉出一个的概率（服主定 10%） */
    public static final float DISTILL_CHANCE = 0.10F;

    /** 身上带多少个萘以内不要紧：超过才反胃（服主定 32） */
    public static final int CARRY_FREE = 32;
    /** 反胃的基本时长（秒；Claude 定一版，服主 11-16 认可：3 秒） */
    public static final int NAUSEA_BASE_SECONDS = 3;
    /** 超出部分每多这么多个，时长加 1 秒（Claude 定：8 个） */
    public static final int NAUSEA_SECONDS_STEP = 8;
    /** 超出部分每多这么多个，反胃等级加一级（Claude 定：64 个） */
    public static final int NAUSEA_LEVEL_STEP = 64;
    /** 反胃等级最高到 IV，即放大等级 3（服主定） */
    public static final int NAUSEA_MAX_AMPLIFIER = 3;

    // ---- 萘酚磺酸粉：电弧炉（萘 + 辅料；数量服主定，时间、电耗 Claude 定，同叠氮化钠那条） ----
    public static final int NAPHTHOL_SULFUR = 2;
    public static final int NAPHTHOL_HYDROXIDE = 4;
    public static final int NAPHTHOL_CARBONATE = 2;
    public static final int NAPHTHOL_ARC_TIME = 400;
    public static final int NAPHTHOL_ARC_ENERGY = 512;

    // ---- 重氮萘醌磺酸粉：工作台、装配机；一桶水 ----
    /** 一桶水（mB）；装配机里从罐里顶替 */
    public static final int DIAZO_WATER = 1000;

    // ---- 重氮萘醌磺酰氯粉：装罐机、灌装机（服主定 1000 mB 氯气） ----
    public static final int SULFONYL_CHLORINE = 1000;

    // ---- 正胶：混合器、装罐机（服主定 1000 mB 酚醛树脂 + 1 粉 → 1000 mB） ----
    public static final int RESIST_RESIN = 1000;
    public static final int RESIST_OUT = 1000;

    // ---- 正胶显影（服主定：每层 125 mB 水，同时吃 2 个氢氧根或 1 个碳酸钾；以后可调） ----
    public static final int DEVELOP_WATER = 125;
    public static final int DEVELOP_HYDROXIDE = 2;
    public static final int DEVELOP_CARBONATE = 1;

    // ---- 萤石出氟化钙（服主定） ----
    /** 萤石粉：4 磷之外，这个概率（百分数）出 1 个氟化钙 */
    public static final int FLUORITE_DUST_CHANCE = 25;
    public static final int FLUORITE_DUST_PHOSPHORUS = 4;
    /** 萤石块按 4 个萤石粉折算（Claude 定一版，服主 11-16 认可）：16 磷 + 1 氟化钙（固定） */
    public static final int FLUORITE_BLOCK_PHOSPHORUS = 16;
    public static final int FLUORITE_BLOCK_CALCIUM_FLUORIDE = 1;

    private ResistNumbers() {
    }
}
