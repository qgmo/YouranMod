package moe.qingu.youran.litho;

/** 光刻机主方块的物品格 */
public final class LithoSlots {
    /** 掩模槽：只在界面里出现，不进任何能力（漏斗、管道碰不到） */
    public static final int MASK = 0;
    /** 五个罐的容器格（放空桶、IC2 单元、沉浸工程油桶把罐倒出来）：水蒸气、光刻胶、溶剂、刻蚀液、输出 */
    public static final int CONTAINER_FIRST = 1;
    public static final int CONTAINERS = 5;
    /** 晶圆（一格一片） */
    public static final int WAFER_FIRST = 6;
    public static final int WAFERS = 2;
    public static final int BORON_INGOT = 8;
    public static final int GLOWSTONE = 9;
    public static final int BORON_ELEMENT = 10;
    public static final int PHOSPHORUS = 11;
    public static final int ALUMINUM = 12;
    public static final int CASING = 13;
    /** 芯片输出 */
    public static final int OUTPUT_FIRST = 14;
    public static final int OUTPUTS = 4;
    /** 正胶显影吃的碱（服主 11-16）：炼金化学氢氧根、碳酸钾各一格；加在最后，不改已有格子的编号 */
    public static final int HYDROXIDE = 18;
    public static final int CARBONATE = 19;
    public static final int COUNT = 20;

    private LithoSlots() {
    }

    public static boolean isOutput(int slot) {
        return slot >= OUTPUT_FIRST && slot < OUTPUT_FIRST + OUTPUTS;
    }

    public static boolean isContainer(int slot) {
        return slot >= CONTAINER_FIRST && slot < CONTAINER_FIRST + CONTAINERS;
    }

    public static boolean isWafer(int slot) {
        return slot >= WAFER_FIRST && slot < WAFER_FIRST + WAFERS;
    }
}
