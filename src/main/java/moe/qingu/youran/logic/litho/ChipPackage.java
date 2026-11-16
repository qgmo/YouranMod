package moe.qingu.youran.logic.litho;

/**
 * 封装用的 IC2 外壳（服主 11-15：各种外壳都能用，材料记进芯片 NBT、只影响耐热性）。金最好（陶瓷 + 镀金引脚），
 * 铜、青铜居中，铁、锡最普通（便宜的塑料封装）；铅、钢的位置 Claude 11-16 定：钢（相当于可伐 / 42 合金引线框）居中偏上，铅最差（软、熔点低）。
 * {@link #meta} 是 {@code ic2:casing} 的元数据。
 */
public enum ChipPackage {
    BRONZE("bronze", 0, 600),
    COPPER("copper", 1, 600),
    GOLD("gold", 2, 1000),
    IRON("iron", 3, 300),
    LEAD("lead", 4, 150),
    STEEL("steel", 5, 700),
    TIN("tin", 6, 300);

    public final String id;
    public final int meta;
    /** 品质（千分比），只影响耐热性 */
    public final int qualityPermille;

    ChipPackage(String id, int meta, int qualityPermille) {
        this.id = id;
        this.meta = meta;
        this.qualityPermille = qualityPermille;
    }

    public static ChipPackage byMeta(int meta) {
        for (ChipPackage p : values()) {
            if (p.meta == meta) {
                return p;
            }
        }
        return null;
    }

    public static ChipPackage byId(String id) {
        for (ChipPackage p : values()) {
            if (p.id.equals(id)) {
                return p;
            }
        }
        return null;
    }

    public double quality() {
        return qualityPermille / 1000.0;
    }
}
