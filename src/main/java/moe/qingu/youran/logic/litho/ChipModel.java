package moe.qingu.youran.logic.litho;

/**
 * 服务器定好的几种芯片（服主 11-15：固定型号，与电脑配置写死一致，不做玩家自己设计）。一套掩模、一张芯片设计图对应一种。
 * 两种 CPU：普通电脑的 Z80、个人电脑的 8088；一种内存：4164 DRAM；ROM（服主 11-16）：监控程序与 BIOS 两种掩模 ROM、2764 EPROM、
 * 汉卡的四片字库 ROM。频率单位 kHz。ROM 同内存，只有存取速度（额定频率是额定存取时间的倒数）。
 * <p>
 * 内存没有 CPU 那样的标准频率、可调范围。芯片照样有“最大频率”这项指标（品质规则与 CPU 相同），
 * 内存的额定频率 {@link #ratedKHz} 取额定存取时间的倒数（4164-15：150 ns ≈ 6.67 MHz）；
 * 内存芯片的 NBT 不写标准频率、可调范围（{@code StdFreq} / {@code MinFreq} / {@code MaxAdjFreq}）。
 */
public enum ChipModel {
    /** Zilog Z80（NMOS，4 μm，6 层）：标准频率跟着 SCP 普通电脑走（服主 11-16）：12.638 MHz ÷ 6 ≈ 2.106 MHz；可调范围同比例往下挪：1.3–3.2 MHz */
    Z80("z80", 0, Kind.CPU, 4.0, 6, 2106, 1300, 3200),
    /** Intel 8088（HMOS，3 μm，7 层）：标准频率跟着 SCP 个人电脑走：14.31818 MHz ÷ 3 ≈ 4.773 MHz（IBM PC），可调到 8 MHz（Turbo XT） */
    I8088("8088", 1, Kind.CPU, 3.0, 7, 4773, 4773, 8000),
    /**
     * 4164 动态内存（64 Kbit × 1，NMOS，约 3 μm，6 层：有源区、一层多晶（存储电容极板）、二层多晶（字线 / 栅）、
     * 埋层接触、接触孔、金属）：额定存取时间 150 ns（4164-15），按 1 / 150 ns ≈ 6667 kHz 算额定频率
     */
    DRAM_4164("4164", 2, Kind.RAM, 3.0, 6, 6667, 0, 0),
    /**
     * 普通电脑的监控程序掩模 ROM（服主 11-16）：4 KB（2332 那一类，NMOS 4 μm，6 层：有源区、栅氧与多晶、编程注入（程序就做在这一层）、
     * 接触孔、金属、钝化开窗）。额定存取时间 450 ns。程序做在掩模里，光刻机出来就是正版的监控程序
     */
    ROM_MONITOR("monitor_rom", 3, Kind.ROM, 4.0, 6, 2222, 0, 0),
    /** 个人电脑的 BIOS 掩模 ROM（服主 11-16）：8 KB（2364 那一类，HMOS 3 μm，6 层），额定存取时间 250 ns；出来就是正版 BIOS */
    ROM_BIOS("bios_rom", 4, Kind.ROM, 3.0, 6, 4000, 0, 0),
    /**
     * 2764 EPROM（服主 11-16）：8 KB，HMOS 3 μm，7 层（比掩模 ROM 多一层多晶：浮栅 + 控制栅）。光刻机出来是芯片本体，
     * 和玻璃管合成（石英小窗）才是能烧录、能紫外擦除的 EPROM（{@code item.ItemEprom}）。额定存取时间 250 ns
     */
    EPROM_2764("2764", 5, Kind.ROM, 3.0, 7, 4000, 0, 0),
    /**
     * 字库 ROM（服主 11-16，汉卡用）：64 KB 掩模 ROM，四片合起来是 GB2312 的 16×16 点阵字库（约 238 KB）；四片各一套掩模。
     * 按 512 Kbit 的大片子算，3 μm、6 层（负胶做得了），额定存取时间 200 ns
     */
    FONT_ROM_1("font_rom_1", 6, Kind.ROM, 3.0, 6, 5000, 0, 0),
    FONT_ROM_2("font_rom_2", 7, Kind.ROM, 3.0, 6, 5000, 0, 0),
    FONT_ROM_3("font_rom_3", 8, Kind.ROM, 3.0, 6, 5000, 0, 0),
    FONT_ROM_4("font_rom_4", 9, Kind.ROM, 3.0, 6, 5000, 0, 0),
    /**
     * YR-2000（服主 11-16，家具电脑的 CPU，型号虚构）：0.13 μm，10 层，标准频率 2.0 GHz，可调 1.6–2.6 GHz。
     * 只有 ArF 光源（0.05 μm）+ 改良正胶（0.05 μm）做得了；对准公差 0.065 μm，要激光对准
     */
    YR2000("yr2000", 10, Kind.CPU, 0.13, 10, 2_000_000, 1_600_000, 2_600_000),
    /**
     * 激光对准控制程序 ROM（服主 11-16）：插在个人电脑主板 ROM 槽上，对准台认它才开“激光对准”。同 BIOS ROM 那一类
     * （8 KB 掩模 ROM，HMOS 3 μm，6 层，额定存取时间 250 ns）；程序做在掩模里，出厂就是正版
     */
    ROM_LASER_ALIGN("laser_align_rom", 11, Kind.ROM, 3.0, 6, 4000, 0, 0),
    /**
     * 网卡控制芯片（2.3.1.0 第 1 期；服主 11-27 选 A：HMOS 3 μm、7 层，负胶做得了，同 8088 一档）：个人电脑 ISA 网卡（{@code nic_pc}）上那颗，
     * 卡上“模块”的固件（IPv6、TCP 等）就在它里面。版图参照 NE2000 上的 DP8390（寄存器不兼容：这张网没有以太网层）。
     * 额定时钟 20 MHz（DP8390 在 NE2000 上的时钟，计划 3.1）。品质只用“最大频率 ÷ 额定频率”这一个比值，决定卡的同时连接数与缓冲区
     * （{@code api.pcb.NicSpec}）；耐热性、寿命照样记着，这一版不用
     */
    NIC_CTRL("nic_ctrl", 12, Kind.CONTROLLER, 3.0, 7, 20000, 0, 0),
    /**
     * 加密协处理器（2.3.1.0 第 4 期；服主 11-27 选 A：2 μm、8 层，要正胶、石英汞灯）：个人电脑 ISA 加密卡（{@code tls_pc}）上那颗，
     * 卡上的 TLS 引擎（握手、证书校验、加解密）就在它里面。额定时钟 16 MHz（计划 3.1）。品质只用“最大频率 ÷ 额定频率”这一个比值，
     * 决定卡的每秒握手数、同时会话数与加解密吞吐（{@code api.pcb.TlsSpec}）；耐热性、寿命照样记着，这一版不用
     */
    TLS_COPROC("tls_coproc", 13, Kind.CONTROLLER, 2.0, 8, 16000, 0, 0);

    /**
     * 芯片种类：CPU 有标准频率与可调范围；内存只有存取速度；控制芯片（扩展卡上的主芯片，如网卡控制芯片）有额定时钟、没有可调范围
     */
    public enum Kind {
        CPU, RAM, ROM, CONTROLLER
    }

    /** 掩模、设计图、芯片 NBT 里记型号的键（字符串） */
    public static final String TAG = "Model";

    public final String id;
    /**
     * 物品元数据（服主 11-16：掩模按元数据区分，不按 NBT）：掩模、芯片设计图、芯片三种物品都用它区分型号。存档里用的，定了就不能改；
     * 新型号往后加
     */
    public final int meta;
    public final Kind kind;
    /** 制程（μm） */
    public final double nodeUm;
    /** 一套掩模有几层（每层走一遍氧化 → 涂胶 → 对准曝光 → 显影 → 刻蚀） */
    public final int layers;
    /** 额定频率：CPU 是标准频率；内存是额定存取时间的倒数。芯片的最大频率围绕它上下浮动 */
    public final int ratedKHz;
    /** CPU 可调范围（内存为 0） */
    public final int minKHz;
    public final int maxAdjustKHz;

    ChipModel(String id, int meta, Kind kind, double nodeUm, int layers, int ratedKHz, int minKHz, int maxAdjustKHz) {
        this.id = id;
        this.meta = meta;
        this.kind = kind;
        this.nodeUm = nodeUm;
        this.layers = layers;
        this.ratedKHz = ratedKHz;
        this.minKHz = minKHz;
        this.maxAdjustKHz = maxAdjustKHz;
    }

    public static ChipModel byId(String id) {
        for (ChipModel m : values()) {
            if (m.id.equals(id)) {
                return m;
            }
        }
        return null;
    }

    /** 这个元数据的型号；没有时为 null */
    public static ChipModel byMeta(int meta) {
        for (ChipModel m : values()) {
            if (m.meta == meta) {
                return m;
            }
        }
        return null;
    }

    /** 版图图片的名字（{@code textures/gui/layout_<名字>.png}、{@code mask_<名字>.png}）：四片字库 ROM 共用一张，其余一型一张 */
    public String layoutId() {
        return id.startsWith("font_rom_") ? "font_rom" : id;
    }

    public boolean isRom() {
        return kind == Kind.ROM;
    }

    public boolean isCpu() {
        return kind == Kind.CPU;
    }

    /** 扩展卡上的控制芯片（悬停里显示时钟频率，不是存取时间） */
    public boolean isController() {
        return kind == Kind.CONTROLLER;
    }

    /** 频率（kHz）给人看：1 GHz 以上写 GHz，否则写 MHz，两位小数 */
    public static String formatFreq(int kHz) {
        return kHz >= 1_000_000 ? String.format(java.util.Locale.ROOT, "%.2f GHz", kHz / 1_000_000.0)
                : String.format(java.util.Locale.ROOT, "%.2f MHz", kHz / 1000.0);
    }

    /** 频率换成存取时间（ns）：1 / f */
    public static int accessNs(int kHz) {
        return kHz <= 0 ? 0 : (int) Math.round(1_000_000.0 / kHz);
    }

    /** 对准公差（μm）：制程越细要求越严 */
    public double toleranceUm() {
        return nodeUm * LithoNumbers.TOLERANCE_PER_NODE;
    }
}
