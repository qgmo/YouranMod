package moe.qingu.youran.litho;

import javax.annotation.Nullable;

/**
 * 光刻机界面里空格子的提示：每个能放东西的格子认哪一类东西、做什么用（语言键 {@code youranmod.litho.hint.<id>}，
 * 有第二行时再加 {@code .2}）。与格子的 {@code isItemValid}（{@link TileLithoMaster} 的 {@code inv}、{@link LaserAligner} 的 {@code inv}）一一对应；
 * 芯片输出格不收东西，没有提示。界面（幽灵图标、悬停说明）与单元测试都用这里的编号，改格子认的东西时一起改。
 */
public final class SlotHints {
    public static final String MASK = "mask";
    public static final String CONTAINER = "container";
    public static final String WAFER = "wafer";
    public static final String BORON_INGOT = "boron_ingot";
    public static final String GLOWSTONE = "glowstone";
    public static final String BORON_ELEMENT = "boron_element";
    public static final String PHOSPHORUS = "phosphorus";
    public static final String ALUMINUM = "aluminum";
    public static final String CASING = "casing";
    public static final String HYDROXIDE = "hydroxide";
    public static final String CARBONATE = "carbonate";
    public static final String BOARD = "board";
    public static final String LASER = "laser";

    private SlotHints() {
    }

    /** 主方块物品格（{@link LithoSlots}）的提示编号；输出格与不认识的编号为 null */
    @Nullable
    public static String forMachineSlot(int slot) {
        if (slot == LithoSlots.MASK) {
            return MASK;
        }
        if (LithoSlots.isContainer(slot)) {
            return CONTAINER;
        }
        if (LithoSlots.isWafer(slot)) {
            return WAFER;
        }
        switch (slot) {
            case LithoSlots.BORON_INGOT:
                return BORON_INGOT;
            case LithoSlots.GLOWSTONE:
                return GLOWSTONE;
            case LithoSlots.BORON_ELEMENT:
                return BORON_ELEMENT;
            case LithoSlots.PHOSPHORUS:
                return PHOSPHORUS;
            case LithoSlots.ALUMINUM:
                return ALUMINUM;
            case LithoSlots.CASING:
                return CASING;
            case LithoSlots.HYDROXIDE:
                return HYDROXIDE;
            case LithoSlots.CARBONATE:
                return CARBONATE;
            default:
                return null;
        }
    }

    /** 激光对准两格（{@link LaserAligner#SLOT_BOARD}、{@link LaserAligner#SLOT_LASER}）的提示编号 */
    @Nullable
    public static String forLaserSlot(int slot) {
        return slot == LaserAligner.SLOT_BOARD ? BOARD : slot == LaserAligner.SLOT_LASER ? LASER : null;
    }

    public static String key(String id) {
        return "youranmod.litho.hint." + id;
    }
}
