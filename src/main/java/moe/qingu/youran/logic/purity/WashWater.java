package moe.qingu.youran.logic.purity;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * IC2 洗矿机认的“水”（服主 11-15：除了普通水，整合包里别的水也能洗）与每种水的“纯净率”（百分数，Claude 11-15 定的一版）。
 * 洗别的矿物时各种水没有区别；洗硅晶圆时纯净率越高越不容易报废（{@link WaferRules#scrapChance}）。纯逻辑，按 Forge 流体注册名。
 */
public final class WashWater {
    /** 流体注册名 → 纯净率（百分数），按纯净率从低到高 */
    private static final Map<String, Integer> RATES = new LinkedHashMap<>();

    static {
        // SCP: Lockdown 的沼泽水（SCP-811 的）：脏
        RATES.put("swamp_water", 20);
        // 本模组的矿泉水：溶着矿物质，比自来水还差
        RATES.put("fuild_mineral_water", 50);
        // IC2 的热水（地热、反应堆出来的）
        RATES.put("ic2hot_water", 55);
        // 普通水
        RATES.put("water", 60);
        // 意志坚定（Tough As Nails）的纯净水：木炭过滤过
        RATES.put("purified_water", 85);
        // IC2 的重水
        RATES.put("ic2heavy_water", 90);
        // IC2 的蒸馏水
        RATES.put("ic2distilled_water", 95);
        // SCP: Lockdown 的不老泉（SCP-006）
        RATES.put("fountain_of_youth_liquid", 98);
    }

    private WashWater() {
    }

    /** 全部（只读，按纯净率从低到高） */
    public static Map<String, Integer> rates() {
        return Collections.unmodifiableMap(RATES);
    }

    /** 是不是洗矿机认的水 */
    public static boolean isWashWater(String fluidName) {
        return fluidName != null && RATES.containsKey(fluidName);
    }

    /**
     * 水质档次（服主 11-16：JEI 里不写报废率，用“谁比谁好”的定性说法）：0 很脏、1 偏脏、2 一般、3 干净、4 很干净。
     * 按纯净率分：&lt;40、40–59、60–79、80–94、≥95。
     */
    public static int grade(String fluidName) {
        int r = rate(fluidName);
        if (r >= 95) {
            return 4;
        }
        if (r >= 80) {
            return 3;
        }
        if (r >= 60) {
            return 2;
        }
        if (r >= 40) {
            return 1;
        }
        return 0;
    }

    /** 纯净率（百分数）；不是洗矿机认的水（或罐空，null）时为 0 */
    public static int rate(String fluidName) {
        Integer r = fluidName == null ? null : RATES.get(fluidName);
        return r == null ? 0 : r;
    }
}
