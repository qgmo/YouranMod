package moe.qingu.youran.logic.alchemy;

/**
 * 炼金化学给“和元素同名的流体”自动加的雾化器 / 液化器配方是“144 mB ⇄ 16 个元素”，按一锭熔融金属的量算。
 * 对熔融金属（匠魂的铁、铜……）没问题；对气体、低温液体（液氩、液氙，别的模组的氖、氦……）不对。
 * 服主 11-16 定的惯例是“8 个元素 ⇄ 500 mB”，所以这类流体的 144 mB 那条要换成 500 mB ⇄ 8 个（服主 11-17：雾化器还有 144 mB 变液氩、氖的配方）。
 */
public final class ElementFluidRule {
    /** 炼金化学自动配方的量：144 mB ⇄ 16 个 */
    public static final int INGOT_MB = 144;
    public static final int INGOT_ELEMENTS = 16;
    /** 换成的量：500 mB ⇄ 8 个 */
    public static final int GAS_MB = 500;
    public static final int GAS_ELEMENTS = 8;
    /** 低于这个温度（K，0 °C）的是低温液体（液氩 87 K、液氙 165 K）；常温的不动（有的模组的熔融金属温度没设，是默认 300 K） */
    public static final int CRYOGENIC_BELOW_K = 273;

    private ElementFluidRule() {
    }

    /** 这条“{@code mb} mB ⇄ {@code elements} 个”是不是要换掉的那种（自动加的锭量配方，流体又是气体或低温液体） */
    public static boolean replaceIngotPair(int mb, int elements, int temperatureK, boolean gaseous) {
        return mb == INGOT_MB && elements == INGOT_ELEMENTS && (gaseous || temperatureK < CRYOGENIC_BELOW_K);
    }
}
