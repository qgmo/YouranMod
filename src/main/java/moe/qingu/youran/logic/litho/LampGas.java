package moe.qingu.youran.logic.litho;

/** 汞灯里充的惰性气体（服主 11-16：氩或氙，氙做出来的灯更好） */
public enum LampGas {
    ARGON("argon"),
    XENON("xenon");

    public static final String TAG = "Gas";

    public final String id;

    LampGas(String id) {
        this.id = id;
    }

    public static LampGas byId(String id) {
        for (LampGas g : values()) {
            if (g.id.equals(id)) {
                return g;
            }
        }
        return null;
    }
}
