package moe.qingu.youran.logic.litho;

/**
 * 光刻机与光刻胶线上还没定的数值，集中在这里（Claude 11-16 定的一版，服主之后再调）。
 * 交付文档（光刻-实现.md）里有一张表与这里一一对应。纯常量，不依赖 Minecraft。
 */
public final class LithoNumbers {
    private LithoNumbers() {
    }

    // ---- 罐、电 ----
    /** 四个输入罐各自的容量（mB）：设计稿说别太大，换灯拆机前要能倒空 */
    public static final int INPUT_TANK = 4000;
    /** 输出罐（蒸汽 / 蒸馏水）的容量（mB） */
    public static final int OUTPUT_TANK = 8000;
    /** 电容量（FE） */
    public static final int ENERGY_CAPACITY = 64000;
    /** 每 tick 最多收多少 FE */
    public static final int ENERGY_MAX_INPUT = 4096;

    // ---- 每一层（氧化 → 涂胶 → 对准曝光 → 显影 → 刻蚀）的消耗与时间 ----
    /** 氧化：每层水蒸气（IC2 蒸汽或过热蒸汽，mB） */
    public static final int OXIDIZE_STEAM = 1000;
    /** 过热蒸汽用过后变成多少蒸汽（mB，每 1000 mB 过热蒸汽） */
    public static final int SUPERHEATED_TO_STEAM = 1000;
    /** 蒸汽用过后冷凝成多少蒸馏水（mB，每 1000 mB 蒸汽；IC2 是 1 mB 水 → 100 mB 蒸汽） */
    public static final int STEAM_TO_DISTILLED = 10;
    /** 涂胶：每层光刻胶（mB）；1 环化橡胶 + 25 mB 双叠氮感光剂正好做 25 mB */
    public static final int COAT_RESIST = 25;
    /** 刻蚀：每层刻蚀液（盐酸，mB） */
    public static final int ETCH_ACID = 100;

    public static final int OXIDIZE_TICKS = 100;
    public static final int COAT_TICKS = 60;
    /** 曝光的基准时间（tick）：实际 = 基准 ÷ 汞灯亮度 */
    public static final int EXPOSE_TICKS = 80;
    public static final int DEVELOP_TICKS = 60;
    public static final int ETCH_TICKS = 80;

    // ---- 一片晶圆一次（掺杂 → 金属化 → 去胶 → 切割 → 封装）----
    /** 掺杂：硼锭按四分之一记（0.25 硼锭 / 片） */
    public static final int DOPE_BORON_QUARTERS = 1;
    public static final int DOPE_GLOWSTONE = 4;
    /** 掺杂的另一种辅料：炼金化学硼元素、磷元素各几个 */
    public static final int DOPE_BORON_ELEMENT = 4;
    public static final int DOPE_PHOSPHORUS_ELEMENT = 4;
    /** 金属化：铝粉按四分之一记（0.25 铝粉 / 片） */
    public static final int METAL_ALUMINUM_QUARTERS = 1;
    public static final int DOPE_TICKS = 100;
    public static final int METALLIZE_TICKS = 80;
    public static final int DICE_TICKS = 40;
    public static final int PACKAGE_TICKS = 60;

    // ---- 每 tick 电耗（FE）----
    public static final int FE_OXIDIZE = 192;
    public static final int FE_COAT = 64;
    public static final int FE_EXPOSE = 256;
    public static final int FE_DEVELOP = 64;
    public static final int FE_ETCH = 96;
    public static final int FE_DOPE = 192;
    public static final int FE_METALLIZE = 128;
    public static final int FE_DICE = 64;
    public static final int FE_PACKAGE = 64;

    // ---- 对准（单位：μm、μrad）----
    /** 两个对准标记之间的距离（μm）：θ 每 1 μrad 让两边各偏 半距 × 1e-6 μm */
    public static final double MARK_HALF_SPAN_UM = 20000;
    /** 成形后（上一层留下的）初始偏差：平移 ±（μm）、转角 ±（μrad） */
    public static final double INITIAL_SHIFT_UM = 40;
    public static final double INITIAL_ROTATION_URAD = 1250;
    /** 旋钮一格：粗调 / 细调（平移 μm） */
    public static final double STEP_COARSE_UM = 2.0;
    public static final double STEP_FINE_UM = 0.1;
    /** 旋钮一格：粗调 / 细调（转角 μrad）；粗调一格让两边各走 1 μm */
    public static final double STEP_COARSE_URAD = 50;
    public static final double STEP_FINE_URAD = 2.5;
    /** 旋钮回差：换方向时头这么多空转（平移 μm、转角 μrad） */
    public static final double BACKLASH_UM = 1.5;
    public static final double BACKLASH_URAD = 40;
    /** 显微镜视野半径：低倍 / 高倍（μm） */
    public static final double FIELD_LOW_UM = 60;
    public static final double FIELD_HIGH_UM = 6;
    /** 对准公差 = 掩模制程 × 这个比例（μm）；残留偏差到公差时对准率为 0 */
    public static final double TOLERANCE_PER_NODE = 0.5;
    /** 对准率低于这个（千分比）直接报废，也不能“完成对准”，产出后掉到这以下要重新对准 */
    public static final int ALIGN_MIN_PERMILLE = 500;
    /** 每产出一片的随机漂移（正态分布的标准差）：平移 μm、转角 μrad */
    public static final double DRIFT_SHIFT_UM = 0.03;
    public static final double DRIFT_ROTATION_URAD = 1.5;

    // ---- 汞灯 ----
    /**
     * 低档玻璃管（匠魂熔融玻璃浇手柄铸模，服主 11-16）的汞灯：基准亮度（千分比）、寿命（曝光次数，一层一次）。
     * 金铸模浇的比粘土铸模的好（服主 11-16），两种都比任何纯度的石英管差。
     */
    public static final int GLASS_LAMP_BRIGHTNESS_GOLD = 500;
    public static final int GLASS_LAMP_LIFE_GOLD = 300;
    public static final int GLASS_LAMP_BRIGHTNESS_CLAY = 400;
    public static final int GLASS_LAMP_LIFE_CLAY = 220;
    /** 低档玻璃管的分辨率（μm）：普通玻璃挡掉 i 线，只剩 g / h 线 */
    public static final double GLASS_LAMP_RESOLUTION_UM = 2.5;
    /** 石英管：1 个 9 的亮度；每多一个 9 往 1000 靠一半 */
    public static final int QUARTZ_LAMP_BRIGHTNESS_1 = 600;
    /** 石英管：1 个 9 的寿命；每多一个 9 加这么多（到 {@link #QUARTZ_LAMP_MAX_NINES} 个 9 为止） */
    public static final int QUARTZ_LAMP_LIFE_1 = 600;
    public static final int QUARTZ_LAMP_LIFE_PER_NINE = 300;
    /** 石英纯度超过这么多个 9 不再变好 */
    public static final int QUARTZ_LAMP_MAX_NINES = 6;
    public static final double QUARTZ_LAMP_RESOLUTION_UM = 1.5;
    /** 充氙的灯（服主 11-16：氙比氩好）：亮度加多少（千分比，最多到 1000）、寿命乘多少（百分比） */
    public static final int XENON_BRIGHTNESS_BONUS = 80;
    public static final int XENON_LIFE_PERCENT = 125;
    /** 一盏灯装多少汞（mB，服主 11-16 定 10 mB），每曝光几次用掉 1 mB */
    public static final int LAMP_MERCURY = 10;
    public static final int MERCURY_EXPOSURES_PER_MB = 50;
    /** 匠魂浇铸台补汞的时间（tick） */
    public static final int MERCURY_CASTING_TIME = 40;
    /** 用到寿命时亮度剩多少（比例），之后熄灭 */
    public static final double LAMP_END_BRIGHTNESS = 0.4;

    // ---- 掩模 ----
    /** 掩模寿命（片）：磨损到这里不能再用 */
    public static final int MASK_LIFE = 1000;

    // ---- 芯片品质 ----
    /** 晶圆纯度的报废：0.25 × 铸模倍数 × 0.8^(几个 9 − 9) */
    public static final double SCRAP_PURITY_BASE = 0.25;
    /** 分辨率的报废：0.9 × (有效分辨率 ÷ 制程)^4 */
    public static final double SCRAP_RESOLUTION_FACTOR = 0.9;
    /** 溶剂、汞灯、掩模磨损、对准各自的报废系数 */
    public static final double SCRAP_SOLVENT = 0.3;
    public static final double SCRAP_LAMP = 0.25;
    public static final double SCRAP_MASK = 0.2;
    public static final double SCRAP_ALIGN = 0.2;
    /** 芯片寿命的基准（开机小时） */
    public static final int LIFETIME_BASE_HOURS = 300;
    /** 耐热性：一分钟平均温度的基准（K）、外壳加成、对准加成 */
    public static final double HEAT_BASE_K = 335;
    public static final double HEAT_PACKAGE_K = 25;
    public static final double HEAT_ALIGN_K = 10;
    /** 三项指标的随机浮动（正态分布的相对标准差） */
    public static final double METRIC_NOISE = 0.03;

    // ---- 其他 ----
    /** 匠魂浇一根玻璃管用的熔融玻璃（mB，一块玻璃板的量，服主 11-16 先定） */
    public static final int GLASS_TUBE_CASTING = 375;
    /** 浇一根玻璃管的时间（tick） */
    public static final int GLASS_TUBE_CASTING_TIME = 100;

    /** 一块钨锭做几根钨棒（冲压机杆模、金属成型机都按这个） */
    public static final int TUNGSTEN_RODS_PER_INGOT = 2;

    // ---- 钼（服主 11-16：铜矿的副产物）----
    /** IC2 热能离心机：粉碎铜矿石、纯净的粉碎铜矿石各有多大几率多出 1 小撮钼粉 */
    public static final float MO_CENTRIFUGE_CRUSHED = 0.05F;
    public static final float MO_CENTRIFUGE_PURIFIED = 0.08F;
    /** 沉浸工程粉碎机：铜矿石的副产物几率（锡粉、小撮钼粉；原有的 10% 金粉保留） */
    public static final float CRUSHER_COPPER_TIN = 0.10F;
    public static final float CRUSHER_COPPER_MO = 0.05F;

    /** 往输出口外推芯片、往输出口外推流体的间隔（tick） */
    public static final int PUSH_INTERVAL = 10;
    /** 每 tick 往输出口外推多少流体（mB） */
    public static final int FLUID_PUSH = 1000;
}
