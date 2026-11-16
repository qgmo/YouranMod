package moe.qingu.youran.logic.litho;

import java.util.Random;

/**
 * 激光对准（服主 11-16，半自动）：人先用显微镜和旋钮把偏差拧进激光的捕获范围，按“激光对准”后机器在几秒内把残差收到约 0.01 μm；
 * 之后每片的漂移只要还在捕获范围里，机器自己补；漂出范围（或拆过结构）要人重新粗对准。纯逻辑。
 *
 * <ul>
 *     <li>捕获范围（{@link #inCapture}）：平移 ±{@value DuvNumbers#CAPTURE_SHIFT_UM} μm，转角 ±{@value DuvNumbers#CAPTURE_ROTATION_URAD} μrad；</li>
 *     <li>收敛快慢（{@link #convergeTicks}）：主板 CPU 越快越快，激光越弱越慢；</li>
 *     <li>收敛到哪（{@link #targetResidual}）：激光越弱越不准；</li>
 *     <li>路径：从按下时的位置平滑地移到目标（先快后慢），走完就锁定（{@link State#LOCKED}）。</li>
 * </ul>
 */
public final class LaserAlign {
    public enum State {
        /** 没开 */
        OFF,
        /** 正在收敛 */
        CONVERGING,
        /** 收好了，锁定跟踪 */
        LOCKED
    }

    private State state = State.OFF;
    private double sx;
    private double sy;
    private double st;
    private double tx;
    private double ty;
    private double tt;
    private int tick;
    private int total;

    /** 偏差在不在激光的捕获范围里 */
    public static boolean inCapture(Alignment a) {
        return Math.abs(a.x()) <= DuvNumbers.CAPTURE_SHIFT_UM && Math.abs(a.y()) <= DuvNumbers.CAPTURE_SHIFT_UM
                && Math.abs(a.theta()) <= DuvNumbers.CAPTURE_ROTATION_URAD;
    }

    /** 收敛要几 tick：基准 × (4773 kHz ÷ CPU 频率) ÷ 激光强度，在 1 到上限之间 */
    public static int convergeTicks(int cpuKHz, double strength) {
        if (cpuKHz <= 0 || strength < DuvNumbers.LASER_MIN_STRENGTH) {
            return DuvNumbers.LASER_CONVERGE_MAX_TICKS;
        }
        double t = DuvNumbers.LASER_CONVERGE_TICKS * (DuvNumbers.LASER_REFERENCE_KHZ / (double) cpuKHz) / strength;
        return (int) Math.max(1, Math.min(DuvNumbers.LASER_CONVERGE_MAX_TICKS, Math.ceil(t)));
    }

    /** 收敛后的残差（μm）：满强度 0.005–0.01（{@code u} 是 0–1 的随机数），强度 s 时 ÷ s */
    public static double targetResidual(double strength, double u) {
        double s = Math.max(DuvNumbers.LASER_MIN_STRENGTH, Math.min(1, strength));
        return DuvNumbers.LASER_RESIDUAL_UM / s * (0.5 + 0.5 * Math.max(0, Math.min(1, u)));
    }

    /**
     * 按下“激光对准”（或漂移后自动补）：偏差在捕获范围里、CPU 能跑、激光出光时开始收敛，返回 true；否则不动、返回 false。
     */
    public boolean engage(Alignment a, int cpuKHz, double strength, Random random) {
        if (!inCapture(a) || cpuKHz <= 0 || strength < DuvNumbers.LASER_MIN_STRENGTH) {
            return false;
        }
        sx = a.x();
        sy = a.y();
        st = a.theta();
        double r = targetResidual(strength, random.nextDouble());
        double phi = random.nextDouble() * Math.PI * 2;
        tx = r * Math.cos(phi);
        ty = r * Math.sin(phi);
        tt = 0;
        tick = 0;
        total = convergeTicks(cpuKHz, strength);
        state = State.CONVERGING;
        return true;
    }

    /** 收敛走一 tick（调用方已扣了这一 tick 的电）；不在收敛时什么也不做、返回 false */
    public boolean step(Alignment a) {
        if (state != State.CONVERGING) {
            return false;
        }
        tick = Math.min(total, tick + 1);
        double f = total <= 0 ? 1 : tick / (double) total;
        double e = 1 - Math.pow(1 - f, 3);
        a.moveTo(sx + (tx - sx) * e, sy + (ty - sy) * e, st + (tt - st) * e);
        if (tick >= total) {
            state = State.LOCKED;
        }
        return true;
    }

    /**
     * 出一片、台子漂了以后：开着的话，还在捕获范围里就重新收敛（返回 true）；漂出范围就关掉（返回 false，要人重新粗对准）。
     * 没开时什么也不做、返回 false。
     */
    public boolean afterDrift(Alignment a, int cpuKHz, double strength, Random random) {
        if (state == State.OFF) {
            return false;
        }
        if (!engage(a, cpuKHz, strength, random)) {
            state = State.OFF;
            return false;
        }
        return true;
    }

    /** 关掉（人拧了旋钮、拆了结构、主板或激光拿走了） */
    public void release() {
        state = State.OFF;
        tick = 0;
        total = 0;
    }

    public State state() {
        return state;
    }

    public boolean on() {
        return state != State.OFF;
    }

    /** 收敛进度（0–1） */
    public double progress() {
        return state == State.LOCKED ? 1 : total <= 0 ? 0 : tick / (double) total;
    }

    public int totalTicks() {
        return total;
    }

    /** 存档：{state, tick, total, 起点, 目标} */
    public double[] save() {
        return new double[]{state.ordinal(), tick, total, sx, sy, st, tx, ty, tt};
    }

    public void restore(double[] d) {
        if (d == null || d.length < 9) {
            release();
            return;
        }
        int s = (int) d[0];
        state = s >= 0 && s < State.values().length ? State.values()[s] : State.OFF;
        tick = (int) d[1];
        total = (int) d[2];
        sx = d[3];
        sy = d[4];
        st = d[5];
        tx = d[6];
        ty = d[7];
        tt = d[8];
    }
}
