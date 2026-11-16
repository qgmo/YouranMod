package moe.qingu.youran.logic.litho;

/** 汞灯的玻璃管（服主 11-15 / 11-16）：低档玻璃管（匠魂浇的，记铸模）或石英管（几个 9） */
public enum LampGlass {
    GLASS("glass"),
    QUARTZ("quartz");

    /** 汞灯、管坯、光源 NBT 里记玻璃管种类的键 */
    public static final String TAG = "Glass";

    public final String id;

    LampGlass(String id) {
        this.id = id;
    }

    public static LampGlass byId(String id) {
        for (LampGlass g : values()) {
            if (g.id.equals(id)) {
                return g;
            }
        }
        return null;
    }
}
