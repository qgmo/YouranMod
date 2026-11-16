package moe.qingu.youran.logic.litho;

import java.util.Random;

/**
 * 分视场显微镜对准（服主 11-15 同意方向）：左右两个视野，各有晶圆上的十字（上一层留下的）与掩模的十字。纯逻辑。
 *
 * <p>状态是晶圆相对掩模的残留偏差：平移 {@code x}、{@code y}（μm）与转角 {@code theta}（μrad）。两个标记在
 * ±{@link LithoNumbers#MARK_HALF_SPAN_UM} 处，转角让左右两边的 y 往相反方向偏（半距 × θ）。所以只平移对不上两边，
 * 要在平移与旋转之间来回配合。
 *
 * <p>旋钮有回差：换方向时头 {@link LithoNumbers#BACKLASH_UM}（转角 {@link LithoNumbers#BACKLASH_URAD}）空转，容易拧过头。
 */
public final class Alignment {
    public enum Axis {
        X, Y, THETA
    }

    private double x;
    private double y;
    private double theta;
    /** 每个旋钮上次转的方向（−1、0、+1）与还剩多少回差没吃掉 */
    private final int[] lastDir = new int[3];
    private final double[] slack = new double[3];

    public Alignment() {
    }

    public Alignment(double x, double y, double theta) {
        this.x = x;
        this.y = y;
        this.theta = theta;
    }

    /** 成形后的初始偏差：随机的平移与转角，旋钮的方向也随机（回差状态不知道） */
    public void randomize(Random random) {
        x = (random.nextDouble() * 2 - 1) * LithoNumbers.INITIAL_SHIFT_UM;
        y = (random.nextDouble() * 2 - 1) * LithoNumbers.INITIAL_SHIFT_UM;
        theta = (random.nextDouble() * 2 - 1) * LithoNumbers.INITIAL_ROTATION_URAD;
        for (int i = 0; i < 3; i++) {
            lastDir[i] = random.nextBoolean() ? 1 : -1;
            slack[i] = 0;
        }
    }

    /**
     * 拧一格旋钮。{@code dir} 为 +1 或 −1；返回这一格实际走了多少（回差吃掉的不算）。
     */
    public double turn(Axis axis, int dir, boolean fine) {
        if (dir != 1 && dir != -1) {
            throw new IllegalArgumentException("方向只能是 ±1：" + dir);
        }
        int i = axis.ordinal();
        double step;
        double backlash;
        if (axis == Axis.THETA) {
            step = fine ? LithoNumbers.STEP_FINE_URAD : LithoNumbers.STEP_COARSE_URAD;
            backlash = LithoNumbers.BACKLASH_URAD;
        } else {
            step = fine ? LithoNumbers.STEP_FINE_UM : LithoNumbers.STEP_COARSE_UM;
            backlash = LithoNumbers.BACKLASH_UM;
        }
        if (lastDir[i] != 0 && lastDir[i] != dir) {
            slack[i] = backlash;
        }
        lastDir[i] = dir;
        double eaten = Math.min(slack[i], step);
        slack[i] -= eaten;
        double moved = step - eaten;
        switch (axis) {
            case X:
                x += dir * moved;
                break;
            case Y:
                y += dir * moved;
                break;
            default:
                theta += dir * moved;
                break;
        }
        return moved;
    }

    /** 转角在标记处造成的 y 偏移（μm） */
    double rotationOffset() {
        return LithoNumbers.MARK_HALF_SPAN_UM * theta * 1e-6;
    }

    /** 左视野里晶圆十字相对掩模十字的偏差（μm）：{x, y} */
    public double[] left() {
        return new double[]{x, y - rotationOffset()};
    }

    /** 右视野的偏差（μm） */
    public double[] right() {
        return new double[]{x, y + rotationOffset()};
    }

    /** 两边残留偏差里大的那个（μm） */
    public double residual() {
        double[] l = left();
        double[] r = right();
        return Math.max(Math.hypot(l[0], l[1]), Math.hypot(r[0], r[1]));
    }

    /** 对准率（千分比）：残留偏差与公差比，0 偏差为 1000，到公差为 0 */
    public int ratePermille(double toleranceUm) {
        return ratePermille(residual(), toleranceUm);
    }

    public static int ratePermille(double residualUm, double toleranceUm) {
        if (toleranceUm <= 0) {
            return 0;
        }
        double r = 1 - residualUm / toleranceUm;
        return (int) Math.max(0, Math.min(1000, Math.round(r * 1000)));
    }

    /** 机器（激光对准）直接把台子移到这里（压电微动台，不经过旋钮）：旋钮的回差状态不变 */
    public void moveTo(double x, double y, double theta) {
        this.x = x;
        this.y = y;
        this.theta = theta;
    }

    /** 产出一片后的随机漂移 */
    public void drift(Random random) {
        x += random.nextGaussian() * LithoNumbers.DRIFT_SHIFT_UM;
        y += random.nextGaussian() * LithoNumbers.DRIFT_SHIFT_UM;
        theta += random.nextGaussian() * LithoNumbers.DRIFT_ROTATION_URAD;
    }

    public double x() {
        return x;
    }

    public double y() {
        return y;
    }

    public double theta() {
        return theta;
    }

    public int lastDir(Axis axis) {
        return lastDir[axis.ordinal()];
    }

    public double slack(Axis axis) {
        return slack[axis.ordinal()];
    }

    /** 读档用：照原样设回状态 */
    public void restore(double x, double y, double theta, int[] dirs, double[] slacks) {
        this.x = x;
        this.y = y;
        this.theta = theta;
        for (int i = 0; i < 3; i++) {
            lastDir[i] = dirs != null && i < dirs.length ? Integer.signum(dirs[i]) : 0;
            slack[i] = slacks != null && i < slacks.length ? Math.max(0, slacks[i]) : 0;
        }
    }
}
