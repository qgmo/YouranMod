package moe.qingu.youran.logic.litho;

/**
 * 服务器定好的几种芯片（服主 11-15：固定型号，与电脑配置写死一致，不做玩家自己设计）。一套掩模、一张芯片设计图对应一种。
 * 两种 CPU：普通电脑的 Z80、个人电脑的 8088；一种内存：4164 DRAM（服主 11-16，每台电脑要几片还没定）。频率单位 kHz。
 * <p>
 * 内存没有 CPU 那样的标准频率、可调范围。芯片照样有“最大频率”这项指标（品质规则与 CPU 相同），
 * 内存的额定频率 {@link #ratedKHz} 取额定存取时间的倒数（4164-15：150 ns ≈ 6.67 MHz）；
 * 内存芯片的 NBT 不写标准频率、可调范围（{@code StdFreq} / {@code MinFreq} / {@code MaxAdjFreq}）。
 */
public enum ChipModel {
    /** Zilog Z80（NMOS，4 μm，6 层）：标准 4 MHz（Z80A），可调 2.5–6 MHz */
    Z80("z80", Kind.CPU, 4.0, 6, 4000, 2500, 6000),
    /** Intel 8088（HMOS，3 μm，7 层）：标准 4.77 MHz（IBM PC），可调到 8 MHz（Turbo XT） */
    I8088("8088", Kind.CPU, 3.0, 7, 4770, 4770, 8000),
    /**
     * 4164 动态内存（64 Kbit × 1，NMOS，约 3 μm，6 层：有源区、一层多晶（存储电容极板）、二层多晶（字线 / 栅）、
     * 埋层接触、接触孔、金属）：额定存取时间 150 ns（4164-15），按 1 / 150 ns ≈ 6667 kHz 算额定频率
     */
    DRAM_4164("4164", Kind.RAM, 3.0, 6, 6667, 0, 0);

    /** 芯片种类：CPU 有标准频率与可调范围；内存只有存取速度 */
    public enum Kind {
        CPU, RAM
    }

    /** 掩模、设计图、芯片 NBT 里记型号的键（字符串） */
    public static final String TAG = "Model";

    public final String id;
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

    ChipModel(String id, Kind kind, double nodeUm, int layers, int ratedKHz, int minKHz, int maxAdjustKHz) {
        this.id = id;
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

    public boolean isCpu() {
        return kind == Kind.CPU;
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
