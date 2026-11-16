package moe.qingu.youran.logic.litho;

/**
 * 服务器定好的几种芯片（服主 11-15：固定型号，与电脑配置写死一致，不做玩家自己设计）。一套掩模、一张芯片设计图对应一种。
 * 这一阶段只有两种 CPU：普通电脑的 Z80、个人电脑的 8088。频率单位 kHz。
 */
public enum ChipModel {
    /** Zilog Z80（NMOS，4 μm，6 层）：标准 4 MHz（Z80A），可调 2.5–6 MHz */
    Z80("z80", 4.0, 6, 4000, 2500, 6000),
    /** Intel 8088（HMOS，3 μm，7 层）：标准 4.77 MHz（IBM PC），可调到 8 MHz（Turbo XT） */
    I8088("8088", 3.0, 7, 4770, 4770, 8000);

    /** 掩模、设计图、芯片 NBT 里记型号的键（字符串） */
    public static final String TAG = "Model";

    public final String id;
    /** 制程（μm） */
    public final double nodeUm;
    /** 一套掩模有几层（每层走一遍氧化 → 涂胶 → 对准曝光 → 显影 → 刻蚀） */
    public final int layers;
    public final int standardKHz;
    public final int minKHz;
    public final int maxAdjustKHz;

    ChipModel(String id, double nodeUm, int layers, int standardKHz, int minKHz, int maxAdjustKHz) {
        this.id = id;
        this.nodeUm = nodeUm;
        this.layers = layers;
        this.standardKHz = standardKHz;
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

    /** 对准公差（μm）：制程越细要求越严 */
    public double toleranceUm() {
        return nodeUm * LithoNumbers.TOLERANCE_PER_NODE;
    }
}
