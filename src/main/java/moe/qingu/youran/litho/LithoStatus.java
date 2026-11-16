package moe.qingu.youran.litho;

/** 光刻机界面上显示的状态（语言键 {@code youranmod.litho.status.<小写名>}） */
public enum LithoStatus {
    IDLE,
    RUNNING,
    NO_WAFER,
    NO_MASK,
    WRONG_MASK,
    MASK_WORN,
    NO_LIGHT,
    LAMP_OUT,
    NO_MERCURY,
    NOT_ALIGNED,
    NO_POWER,
    REDSTONE,
    NEED_STEAM,
    OUTPUT_TANK_FULL,
    NEED_RESIST,
    NEED_SOLVENT,
    NEED_ETCHANT,
    NEED_DOPANT,
    NEED_ALUMINUM,
    NEED_CASING,
    OUTPUT_FULL,
    WAFER_REMOVED;

    public String key() {
        return "youranmod.litho.status." + name().toLowerCase(java.util.Locale.ROOT);
    }

    public static LithoStatus byOrdinal(int i) {
        LithoStatus[] v = values();
        return i >= 0 && i < v.length ? v[i] : IDLE;
    }
}
