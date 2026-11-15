package moe.qingu.youran.logic.ip;

/**
 * 沉浸原油快艇的油量规则（审查 IP-M1），由 {@code mixin.immersivepetroleum} 调用。
 *
 * <p>船的油量只能在 [0, 容量] 里；骑船的客户端每 tick 报一次“这一 tick 烧掉多少”，
 * 正常客户端报的是这种燃料每 tick 的耗量，加速时是 {@value #BOOST_FACTOR} 倍，不会是负数。
 */
public final class BoatFuel {
    /** 加速时一 tick 烧掉每 tick 耗量的几倍（沉浸原油 {@code EntitySpeedboat.controlBoat}） */
    public static final int BOOST_FACTOR = 3;

    private BoatFuel() {
    }

    /** 把油量夹到 [0, max]；max 小于 0 时按 0 */
    public static int clamp(int amount, int max) {
        if (amount <= 0) {
            return 0;
        }
        return Math.min(amount, Math.max(0, max));
    }

    /**
     * 客户端报的这一 tick 的耗油量收不收：0 到 {@value #BOOST_FACTOR} 倍每 tick 耗量之间才收。
     * 负数（反向加油）和比加速时还多的都不收。
     */
    public static boolean acceptsConsumed(int amount, int perTick) {
        return amount >= 0 && (long) amount <= (long) BOOST_FACTOR * Math.max(0, perTick);
    }
}
