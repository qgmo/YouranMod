package moe.qingu.youran.logic.purity;

/**
 * 晶圆后半段（服主 11-15 定的流程，数量是 Claude 11-15 定的一版）的规则，纯逻辑，不依赖 Minecraft。纯度一律按“几个 9”（{@link Purity}）。
 *
 * <p>第 6 步，混合气：三氯氢硅（到 {@value Purity#WAFER_GRADE_NINES} 个 9）+ 氢气 → 三氯氢硅氢气混合气，纯度随三氯氢硅。
 * 照西门子法氢气过量的思路，体积比 三氯氢硅 : 氢气 = 1 : 4，出的混合气是两者之和。
 */
public final class WaferRules {
    /** 炼油厂每 tick：三氯氢硅（mB） */
    public static final int REFINERY_TRICHLOROSILANE = 10;
    /** 炼油厂每 tick：氢气（mB） */
    public static final int REFINERY_HYDROGEN = 40;
    /** 炼油厂每 tick：出的混合气（mB） */
    public static final int REFINERY_MIXTURE = REFINERY_TRICHLOROSILANE + REFINERY_HYDROGEN;
    /** 炼油厂每 tick 的电耗（同沉浸工程自己的生物柴油、本模组的乙酸） */
    public static final int REFINERY_ENERGY = 80;
    /** 冶炼炉合金一份：三氯氢硅（mB） */
    public static final int ALLOY_TRICHLOROSILANE = 1;
    /** 冶炼炉合金一份：氢气（mB） */
    public static final int ALLOY_HYDROGEN = 4;
    /** 冶炼炉合金一份：出的混合气（mB） */
    public static final int ALLOY_MIXTURE = ALLOY_TRICHLOROSILANE + ALLOY_HYDROGEN;

    private WaferRules() {
    }

    /** 这个纯度的三氯氢硅能不能做混合气：到了 {@value Purity#WAFER_GRADE_NINES} 个 9 */
    public static boolean canMakeMixture(int trichlorosilaneNines) {
        return Purity.waferGrade(trichlorosilaneNines);
    }

    /** 做出来的混合气的纯度：同三氯氢硅（不够门槛的不能做，见 {@link #canMakeMixture}） */
    public static int mixtureNines(int trichlorosilaneNines) {
        return Purity.sanitize(trichlorosilaneNines);
    }

    /**
     * 能做混合气的全部纯度：{@value Purity#WAFER_GRADE_NINES} 到 {@value Purity#MAX_NINES} 个 9。
     * 炼油厂、冶炼炉按流体（连 NBT）比配方，每种纯度各登记一条，产物带同样的纯度。
     */
    public static int[] mixtureGrades() {
        int[] grades = new int[Purity.MAX_NINES - Purity.WAFER_GRADE_NINES + 1];
        for (int i = 0; i < grades.length; i++) {
            grades[i] = Purity.WAFER_GRADE_NINES + i;
        }
        return grades;
    }
}
