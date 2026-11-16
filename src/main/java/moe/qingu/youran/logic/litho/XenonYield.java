package moe.qingu.youran.logic.litho;

import java.util.Random;

/**
 * 挤 IC2 枯竭燃料棒出多少液氙（纯逻辑）：每根随机 {@value LithoNumbers#XENON_PER_ROD_MIN}–{@value LithoNumbers#XENON_PER_ROD_MAX} mB；
 * 双联、四联按棒数乘（服主 11-16），每根各自随机再相加。
 */
public final class XenonYield {
    private XenonYield() {
    }

    /** 一根：{@code min}–{@code max} 随机（含两端） */
    public static int perRod(Random r) {
        return LithoNumbers.XENON_PER_ROD_MIN + r.nextInt(LithoNumbers.XENON_PER_ROD_MAX - LithoNumbers.XENON_PER_ROD_MIN + 1);
    }

    /** {@code rods} 根一起挤 */
    public static int of(Random r, int rods) {
        int sum = 0;
        for (int i = 0; i < rods; i++) {
            sum += perRod(r);
        }
        return sum;
    }

    /** IC2 枯竭燃料棒（{@code ic2:nuclear}）的元数据 → 几联：11、14 单联，12、15 双联，13、16 四联 */
    public static int rodsIn(int meta) {
        switch (meta) {
            case 12:
            case 15:
                return 2;
            case 13:
            case 16:
                return 4;
            default:
                return 1;
        }
    }
}
