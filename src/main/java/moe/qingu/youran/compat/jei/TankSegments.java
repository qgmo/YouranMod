package moe.qingu.youran.compat.jei;

/** 精馏塔 JEI 页的输出罐：几种流体自下而上叠着时各占多高（纯计算，不碰 JEI） */
final class TankSegments {
    private TankSegments() {
    }

    /**
     * 输出罐里自下而上叠着的几格流体各多高：按量分，每格至少 4 像素，最上面一格补齐，几格加起来正好是罐子高（不超出、不留缝）
     */
    static int[] split(int[] amounts) {
        int n = amounts.length;
        long total = 0;
        for (int a : amounts) {
            total += Math.max(0, a);
        }
        int[] h = new int[n];
        if (n == 0) {
            return h;
        }
        int min = Math.min(4, TANK_HEIGHT / n);
        int used = 0;
        for (int i = 0; i < n - 1; i++) {
            int want = total <= 0 ? TANK_HEIGHT / n : (int) (TANK_HEIGHT * (long) Math.max(0, amounts[i]) / total);
            // 给后面每格至少留 min
            int room = TANK_HEIGHT - used - min * (n - 1 - i);
            h[i] = Math.max(min, Math.min(want, room));
            used += h[i];
        }
        h[n - 1] = TANK_HEIGHT - used;
        return h;
    }

    /** 精馏塔界面里罐子的高（沉浸原油精馏塔界面的罐子） */
    static final int TANK_HEIGHT = 47;
}
