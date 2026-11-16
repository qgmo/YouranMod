package moe.qingu.youran.logic.litho;

/**
 * 光刻胶的种类（服主 11-15）：负胶、正胶是同一种流体，NBT 里 {@value #TAG} 区分。这一阶段只做负胶，正胶的值先留好。
 */
public enum ResistType {
    /** 负胶（环化橡胶 + 双叠氮感光剂，如柯达 KTFR）：显影溶胀，分辨率约 2.5 μm */
    NEGATIVE("negative", 2.5),
    /** 正胶（重氮萘醌—酚醛树脂）：分辨率 1 μm 以下；配方还没定（醛没定） */
    POSITIVE("positive", 0.8);

    /** 光刻胶流体 NBT 里记种类的键（字符串） */
    public static final String TAG = "Resist";

    public final String id;
    /** 这种胶最细能做到多少 μm */
    public final double resolutionUm;

    ResistType(String id, double resolutionUm) {
        this.id = id;
        this.resolutionUm = resolutionUm;
    }

    /** 按 NBT 里的名字找；认不出（或没有）时为 null */
    public static ResistType byId(String id) {
        for (ResistType t : values()) {
            if (t.id.equals(id)) {
                return t;
            }
        }
        return null;
    }

    /** 两种里分辨率差的那种（一片晶圆各层用了不同的胶时，按差的算） */
    public static ResistType worse(ResistType a, ResistType b) {
        if (a == null) {
            return b;
        }
        if (b == null) {
            return a;
        }
        return a.resolutionUm >= b.resolutionUm ? a : b;
    }
}
