package moe.qingu.youran.logic.purity;

/**
 * 掺杂硅晶圆铸模的材质（服主 11-15：匠魂“盘”铸模，金的或粘土的，在电弧炉里掺上硼与萤石粉）。
 * 晶圆记下用的是哪种铸模（NBT 里 {@value #TAG}），清洗时金铸模出来的更不容易报废（Claude 11-15 定，见 {@link WaferRules#scrapChance}）。
 */
public enum WaferMold {
    /** 金铸模：报废率按原样 */
    GOLD("gold", 100),
    /** 粘土铸模：报废率 × 1.5 */
    CLAY("clay", 150);

    /** 晶圆 NBT 里记铸模材质的键（字符串） */
    public static final String TAG = "Mold";

    /** 写进 NBT 的名字 */
    public final String id;
    /** 报废率的倍数（百分数） */
    public final int scrapFactorPercent;

    WaferMold(String id, int scrapFactorPercent) {
        this.id = id;
        this.scrapFactorPercent = scrapFactorPercent;
    }

    /** 按 NBT 里的名字找；认不出（或没有）时为 null */
    public static WaferMold byId(String id) {
        for (WaferMold m : values()) {
            if (m.id.equals(id)) {
                return m;
            }
        }
        return null;
    }
}
