package moe.qingu.youran.logic.litho;

/**
 * 显影用的溶剂（服主 11-15：芳烃、杂酚油（沉浸工程、IC2 的都行）、甲酚、UU 物质，各有好坏，UU 物质最好，溶剂会被消耗）。
 * Claude 11-16 定的“好坏”：好的溶剂报废少、指标好，差的便宜但每层用得多。
 */
public enum Solvent {
    UU_MATTER("ic2uu_matter", 1000, 5),
    ARENE("arene", 900, 50),
    CRESOL("cresol", 800, 50),
    CREOSOTE_IE("creosote", 600, 100),
    CREOSOTE_IC2("ic2creosote", 600, 100);

    /** Forge 流体注册名 */
    public final String fluid;
    /** 品质（千分比，1000 最好） */
    public final int qualityPermille;
    /** 每层显影用多少 mB */
    public final int perLayer;

    Solvent(String fluid, int qualityPermille, int perLayer) {
        this.fluid = fluid;
        this.qualityPermille = qualityPermille;
        this.perLayer = perLayer;
    }

    /** 按流体注册名找；不是溶剂时为 null（带叠氮基的芳烃由调用方排除） */
    public static Solvent byFluid(String fluid) {
        for (Solvent s : values()) {
            if (s.fluid.equals(fluid)) {
                return s;
            }
        }
        return null;
    }

    public double quality() {
        return qualityPermille / 1000.0;
    }
}
