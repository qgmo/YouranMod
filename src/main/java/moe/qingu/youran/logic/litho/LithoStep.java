package moe.qingu.youran.logic.litho;

/**
 * 光刻机的工序（服主 11-15 定的十步：氧化 → 涂胶 → 对准 → 显影 → 刻蚀 → 掺杂 → 金属化 → 去胶 → 切割 → 封装）。
 * 中间产物不是物品，是机器的状态。
 *
 * <p>多层（设计稿 2.2：每一层的循环在机器内部做）：前五步（氧化、涂胶、对准曝光、显影、刻蚀）每层走一遍，层数由掩模定
 * （一套掩模每层一块）；走完所有层后掺杂、金属化、去胶、切割、封装各一次。去胶照规格忽略（0 tick，不耗东西）。
 * “对准”一步就是用已调好的对准曝光：对准本身是玩家在显微镜里调的、机器的状态。
 */
public enum LithoStep {
    IDLE(0, 0),
    OXIDIZE(LithoNumbers.OXIDIZE_TICKS, LithoNumbers.FE_OXIDIZE),
    COAT(LithoNumbers.COAT_TICKS, LithoNumbers.FE_COAT),
    EXPOSE(LithoNumbers.EXPOSE_TICKS, LithoNumbers.FE_EXPOSE),
    DEVELOP(LithoNumbers.DEVELOP_TICKS, LithoNumbers.FE_DEVELOP),
    ETCH(LithoNumbers.ETCH_TICKS, LithoNumbers.FE_ETCH),
    DOPE(LithoNumbers.DOPE_TICKS, LithoNumbers.FE_DOPE),
    METALLIZE(LithoNumbers.METALLIZE_TICKS, LithoNumbers.FE_METALLIZE),
    STRIP(0, 0),
    DICE(LithoNumbers.DICE_TICKS, LithoNumbers.FE_DICE),
    PACKAGE(LithoNumbers.PACKAGE_TICKS, LithoNumbers.FE_PACKAGE);

    /** 基准时间（tick）；曝光的实际时间看汞灯亮度 */
    public final int ticks;
    /** 每 tick 电耗（FE） */
    public final int fePerTick;

    LithoStep(int ticks, int fePerTick) {
        this.ticks = ticks;
        this.fePerTick = fePerTick;
    }

    /** 每层都走的一步 */
    public boolean perLayer() {
        return ordinal() >= OXIDIZE.ordinal() && ordinal() <= ETCH.ordinal();
    }

    /** 下一步：{@code layer} 是当前这一步所在的层（0 起），{@code layers} 是一共几层；封装之后回到 {@link #IDLE} */
    public LithoStep next(int layer, int layers) {
        switch (this) {
            case IDLE:
                return OXIDIZE;
            case ETCH:
                return layer + 1 < layers ? OXIDIZE : DOPE;
            case PACKAGE:
                return IDLE;
            default:
                return values()[ordinal() + 1];
        }
    }

    /** 进入下一步时层号怎么变：刻蚀后还有层就加 1 */
    public int nextLayer(int layer, int layers) {
        if (this == ETCH && layer + 1 < layers) {
            return layer + 1;
        }
        if (this == PACKAGE) {
            return 0;
        }
        return layer;
    }

    public static LithoStep byOrdinal(int i) {
        LithoStep[] v = values();
        return i >= 0 && i < v.length ? v[i] : IDLE;
    }

    /** 一片晶圆一共要走几步（含每层的五步） */
    public static int totalSteps(int layers) {
        return layers * 5 + 5;
    }

    /** 这一步是一片晶圆的第几步（1 起），进度显示用 */
    public int stepNumber(int layer, int layers) {
        if (this == IDLE) {
            return 0;
        }
        if (perLayer()) {
            return layer * 5 + (ordinal() - OXIDIZE.ordinal()) + 1;
        }
        return layers * 5 + (ordinal() - DOPE.ordinal()) + 1;
    }
}
