package moe.qingu.youran.logic.pcb;

/**
 * 主板线、ROM、紫外擦除器、烧录、软盘套的数量（服主 11-16 定的标“服主”，其余是 Claude 定的一版，集中在这里方便改）。
 * 时间单位 tick（20 tick = 1 秒），电 FE（本服 1 EU = 2 FE）。
 */
public final class PcbNumbers {
    private PcbNumbers() {
    }

    // ---- 乙醛、乙酸、酚醛树脂（服主） ----
    /** 炼油厂：乙醇 500 + IC2 空气 2000 → 乙醛 500（空气里的氧少，停在乙醛） */
    public static final int ACETALDEHYDE_ETHANOL = 500;
    public static final int ACETALDEHYDE_AIR = 2000;
    public static final int ACETALDEHYDE_OUT = 500;
    /** 匠魂冶炼炉同比例缩小（Claude）：25 + 100 → 25 */
    public static final int ACETALDEHYDE_ALLOY_ETHANOL = 25;
    public static final int ACETALDEHYDE_ALLOY_AIR = 100;
    public static final int ACETALDEHYDE_ALLOY_OUT = 25;
    /** 炼油厂：乙醛 500 + 氧气 163 → 乙酸 500 */
    public static final int ACETIC_ACETALDEHYDE = 500;
    public static final int ACETIC_OXYGEN = 163;
    public static final int ACETIC_OUT = 500;
    /** 冶炼炉（Claude）：163 : 500 没有整数的小比例，取 100 + 33 → 100（0.33，差 1%） */
    public static final int ACETIC_ALLOY_ACETALDEHYDE = 100;
    public static final int ACETIC_ALLOY_OXYGEN = 33;
    public static final int ACETIC_ALLOY_OUT = 100;
    /** 酚醛树脂：甲酚 500 + 乙醛 400 → 500（醛 : 酚约 0.8，现实的线性酚醛） */
    public static final int RESIN_CRESOL = 500;
    public static final int RESIN_ACETALDEHYDE = 400;
    public static final int RESIN_OUT = 500;
    /** 冶炼炉（Claude）：5 + 4 → 5 */
    public static final int RESIN_ALLOY_CRESOL = 5;
    public static final int RESIN_ALLOY_ACETALDEHYDE = 4;
    public static final int RESIN_ALLOY_OUT = 5;
    /** 炼油厂每 tick 电耗（同沉浸工程的生物柴油） */
    public static final int REFINERY_ENERGY = 80;

    // ---- 主板（服主） ----
    /** 酚醛基板：纸 + 125 mB 酚醛树脂 */
    public static final int SUBSTRATE_RESIN = 125;
    /** 涂胶：10 mB 光刻胶 */
    public static final int COAT_RESIST = 10;
    /** 蚀刻：100 mB 三氯化铁水溶液 */
    public static final int ETCH_FECL3 = 100;
    /** 手工打孔打坏的概率（服主：Claude 先定 25%） */
    public static final double HAND_PUNCH_BREAK = 0.25;
    /** 报废主板熔成熔融铜（服主：比一块铜板 144 mB 少一点，Claude 先定 108） */
    public static final int SCRAP_BOARD_COPPER = 108;
    /** 沉浸工程金属冲压机冲孔一次的电（同沉浸工程的板模具） */
    public static final int PRESS_ENERGY = 2400;

    // ---- 三氯化铁 ----
    /** 服主：1 铁粉 + 3000 mB 氯气 → 1 三氯化铁粉 */
    public static final int FECL3_CHLORINE = 3000;
    /** 服主：1 三氯化铁粉 ⇄ 16 个炼金化学化合物 */
    public static final int FECL3_COMPOUNDS = 16;
    /** Claude：1 三氯化铁粉 + 1000 mB 水 → 1000 mB 三氯化铁水溶液 */
    public static final int FECL3_SOLUTION = 1000;
    /** 混合器每份电耗（同负胶链） */
    public static final int MIXER_ENERGY = 1600;

    // ---- 紫外擦除器（Claude；服主定“十几分钟擦干净”“灯越好擦得越快”） ----
    /** 电缓存、每 tick 最多收、亮灯时每 tick 用电 */
    public static final int UV_ENERGY_CAPACITY = 16000;
    public static final int UV_ENERGY_INPUT = 256;
    public static final int UV_ENERGY_PER_TICK = 32;
    /**
     * 擦一块 EPROM：基准 9000 tick ÷ 灯现在的亮度（0–1）。新灯：石英 1 个 9（60%）12.5 分钟、金铸模玻璃管（50%）15 分钟、
     * 粘土铸模（40%）18.75 分钟、石英 6 个 9 充氙（约 100%）7.5 分钟；灯越用越暗、越慢
     */
    public static final int UV_ERASE_TICKS = 9000;
    /** 曝光一块涂胶主板：基准 1200 tick（1 分钟）÷ 亮度 */
    public static final int UV_EXPOSE_TICKS = 1200;
    /** 灯亮着每这么多 tick 算汞灯用了一次（同光刻机曝光一层；汞每 50 次 1 mB） */
    public static final int UV_TICKS_PER_LAMP_USE = 1200;
    /** 匠魂晾干架晒掉 EPROM 的内容：3 个游戏日 */
    public static final int DRYING_RACK_ERASE_TICKS = 72000;

    // ---- 烧录（Claude；服主定“玻璃管档次只影响烧录速度”） ----
    /**
     * 烧 1 KB 要多少 tick：金铸模玻璃管 600（30 秒），粘土铸模 800；石英管 1 个 9 是 400，每多一个 9 乘 0.8，最快 160。
     * 8 KB 的 BIOS：粘土 5.3 分钟、金 4 分钟、石英 1 个 9 2.7 分钟
     */
    public static final int BURN_TICKS_PER_KB_GLASS_GOLD = 600;
    public static final int BURN_TICKS_PER_KB_GLASS_CLAY = 800;
    public static final int BURN_TICKS_PER_KB_QUARTZ_1 = 400;
    public static final double BURN_QUARTZ_PER_NINE = 0.8;
    public static final int BURN_TICKS_PER_KB_MIN = 160;
    /** EPROM 最多 8 KB（2764） */
    public static final int EPROM_BYTES = 8192;

    // ---- 软盘套（服主定 6 团、烧回 4 团；冲坏的概率 Claude） ----
    public static final int JACKET_LUMPS = 6;
    public static final int JACKET_BROKEN_LUMPS = 4;
    public static final double JACKET_HAND_BREAK = 0.2;
}
