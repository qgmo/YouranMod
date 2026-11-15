package moe.qingu.youran.logic.purity;

/**
 * 晶圆后半段（服主 11-15 定的流程，数量是 Claude 11-15 定的一版）的规则，纯逻辑，不依赖 Minecraft。纯度一律按“几个 9”（{@link Purity}）。
 *
 * <p>第 6 步，混合气：三氯氢硅（到 {@value Purity#WAFER_GRADE_NINES} 个 9）+ 氢气 → 三氯氢硅氢气混合气，纯度随三氯氢硅。
 * 照西门子法氢气过量的思路，体积比 三氯氢硅 : 氢气 = 1 : 4，出的混合气是两者之和。
 *
 * <p>第 8 步，浇注：{@value #MIXTURE_PER_WAFER} mB 混合气 + 1 掺杂硅晶圆铸模（消耗）→ 1 硅晶圆，纯度同混合气，记下铸模材质。
 *
 * <p>第 9 步，清洗：IC2 洗矿机，每片用 {@value #WASH_WATER} mB 水，成了是高精硅晶圆（照抄纯度与铸模），坏了是报废硅晶圆。
 * 报废的概率见 {@link #scrapChance}。
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

    /** 一片硅晶圆要的混合气（mB）：相当于 200 mB 三氯氢硅，约半个硅粉提纯到 9 个 9 剩下的量 */
    public static final int MIXTURE_PER_WAFER = 1000;
    /** 匠魂浇铸台浇一片的时间（tick） */
    public static final int CASTING_TIME = 200;

    /** 洗一片硅晶圆用的水（mB），同 IC2 洗一份粉碎矿石 */
    public static final int WASH_WATER = 1000;
    /** 每多一个 9（超过 {@value Purity#WAFER_GRADE_NINES} 个），报废率乘这个数（百分数） */
    public static final int SCRAP_PER_EXTRA_NINE_PERCENT = 80;

    private WaferRules() {
    }

    /**
     * 清洗一片硅晶圆报废的概率（0–1，Claude 11-15 定的一版）：
     * <pre>
     *   不到 9 个 9 的晶圆：一定报废（丢了纯度标记的混合气浇出来的就是这种）；
     *   否则 = (1 − 纯净率) × 铸模倍数（金 1、粘土 1.5）× 0.8^(个数 − 9)，超过 1 按 1
     * </pre>
     * 例：9 个 9、金铸模，蒸馏水（95%）报废 5%，普通水（60%）40%；粘土铸模分别是 7.5%、60%。
     *
     * @param waterPercent 水的纯净率（百分数，{@link WashWater#rate}）
     * @param nines        晶圆的纯度（几个 9）
     * @param mold         晶圆用的铸模；不知道时按粘土算
     */
    public static double scrapChance(int waterPercent, int nines, WaferMold mold) {
        int n = Purity.sanitize(nines);
        if (n < Purity.WAFER_GRADE_NINES) {
            return 1.0;
        }
        int water = Math.max(0, Math.min(100, waterPercent));
        WaferMold m = mold == null ? WaferMold.CLAY : mold;
        double p = (100 - water) / 100.0 * m.scrapFactorPercent / 100.0
                * Math.pow(SCRAP_PER_EXTRA_NINE_PERCENT / 100.0, n - Purity.WAFER_GRADE_NINES);
        return Math.max(0.0, Math.min(1.0, p));
    }

    /** 浇注出来的硅晶圆的纯度：同混合气 */
    public static int waferNines(int mixtureNines) {
        return Purity.sanitize(mixtureNines);
    }

    /**
     * 灌装机（按流体连 NBT 比配方）要登记的纯度：没有标记的（1 个 9，丢了标记的混合气）和 9–32 个 9。
     * 丢了标记的混合气也能浇，只是做出来的晶圆是 1 个 9，不到 9 个 9 的晶圆清洗时一定报废。
     */
    public static int[] castingGrades() {
        int[] mixtures = mixtureGrades();
        int[] grades = new int[mixtures.length + 1];
        grades[0] = Purity.DEFAULT_NINES;
        System.arraycopy(mixtures, 0, grades, 1, mixtures.length);
        return grades;
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
