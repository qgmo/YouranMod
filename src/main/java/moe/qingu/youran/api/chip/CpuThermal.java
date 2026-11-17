package moe.qingu.youran.api.chip;

import net.minecraft.nbt.NBTTagCompound;

/**
 * CPU 温度与“最近 1 分钟温度对时间的积分”（服主 11-15、11-17）。每台电脑一份，每秒走一步（{@link #step}）：
 * <ul>
 *     <li>升温看负载：开着机时目标温度 = 环境 + 温升，温升 =（空转温升 +（满载温升 − 空转温升）× 负载）×（实际频率 ÷ 标准频率）^ 指数；</li>
 *     <li>降温看环境：关着机时目标是环境温度。没有散热片、风扇（服主 11-17：散热器不做）；</li>
 *     <li>温度每秒往目标靠 1 − e^(−1/τ)（牛顿冷却）；</li>
 *     <li>记最近 60 秒每秒的温度，积分（K·s）和芯片的耐热性比（服主 11-15：不另设安全温度）。</li>
 * </ul>
 * 存进方块实体（{@link #write} / {@link #read}）；区块卸载期间没走的时间在读回时按“关着机”补上（电脑没在跑）。纯逻辑，不碰世界。
 */
public final class CpuThermal {
    /** 积分的时间窗（秒）：服主定“最近 1 分钟” */
    public static final int WINDOW = 60;

    private double tempK = Double.NaN;
    /** 最近 60 秒每秒的温度（环形）；{@link #head} 是下一个要写的位置 */
    private final double[] window = new double[WINDOW];
    private int head;
    private double sum;

    /** 还没有温度（刚放下、刚读的旧存档） */
    public boolean unset() {
        return Double.isNaN(tempK);
    }

    /** 温度定成 {@code k}（K），最近 60 秒都当作这个温度 */
    public void reset(double k) {
        tempK = k;
        for (int i = 0; i < WINDOW; i++) {
            window[i] = k;
        }
        head = 0;
        sum = k * WINDOW;
    }

    public double tempK() {
        return tempK;
    }

    public double tempC() {
        return tempK - 273.15;
    }

    /** 最近 60 秒温度对时间的积分（K·s） */
    public double integralKs() {
        return sum;
    }

    /** 积分占耐热性的比例（到 1 就过热）；耐热性 0（坏了）时为 0 */
    public double heatFraction(int toleranceKs) {
        return toleranceKs <= 0 ? 0 : sum / toleranceKs;
    }

    /**
     * 给人看的“热量”（0–1，到 1 就过热断电）：最近 60 秒积分在“一直是环境温度”（0）与耐热性（1）之间的位置。直接拿积分比耐热性的话，
     * 环境温度本身就占了八成多，看不出离过热还有多远。耐热性不比环境高（这片芯片在这里一开机就会过热）时为 1。
     */
    public double heatLevel(int toleranceKs, double ambientK) {
        double base = ambientK * WINDOW;
        if (toleranceKs <= 0) {
            return 0;
        }
        if (toleranceKs <= base) {
            return 1;
        }
        return Math.max(0, Math.min(1, (sum - base) / (toleranceKs - base)));
    }

    /** 积分超过耐热性了（过热，要断电） */
    public boolean overheated(int toleranceKs) {
        return toleranceKs > 0 && sum > toleranceKs;
    }

    /** 开着机、负载 {@code load}（0–1，CPU 在跑指令的时间比例）、实际频率与标准频率之比 {@code freqRatio} 时的目标温升（K） */
    public static double rise(ChipNumbers n, double load, double freqRatio) {
        double l = Math.max(0, Math.min(1, load));
        double base = n.riseIdleK + (n.riseBusyK - n.riseIdleK) * l;
        return base * Math.pow(Math.max(0, freqRatio), n.freqExponent);
    }

    /**
     * 走一秒：{@code powered} 开着机（CPU 有电在跑；坏了的 CPU 不发热，传 false）、负载、频率比、环境温度（K）。
     * 走之前没有温度时从环境温度开始。返回走完的温度（K）。
     */
    public double step(ChipNumbers n, double ambientK, boolean powered, double load, double freqRatio) {
        if (unset()) {
            reset(ambientK);
        }
        double target = ambientK + (powered ? rise(n, load, freqRatio) : 0);
        tempK += (target - tempK) * (1 - Math.exp(-1 / n.timeConstantS));
        sum += tempK - window[head];
        window[head] = tempK;
        head = (head + 1) % WINDOW;
        return tempK;
    }

    /** 关着机过了 {@code seconds} 秒（区块卸载期间）：温度往环境靠，最近 60 秒按走完的样子补 */
    public void coolFor(ChipNumbers n, double ambientK, long seconds) {
        if (unset()) {
            reset(ambientK);
            return;
        }
        if (seconds <= 0) {
            return;
        }
        if (seconds >= WINDOW) {
            double t = ambientK + (tempK - ambientK) * Math.exp(-seconds / n.timeConstantS);
            reset(t);
            return;
        }
        for (long i = 0; i < seconds; i++) {
            step(n, ambientK, false, 0, 0);
        }
    }

    public static final String TAG_TEMP = "Temp";
    public static final String TAG_WINDOW = "Window";
    public static final String TAG_HEAD = "Head";

    /** 存进 {@code tag}：温度（K）、60 秒的温度（0.01 K 为单位的整数）、环形的位置 */
    public NBTTagCompound write(NBTTagCompound tag) {
        if (unset()) {
            return tag;
        }
        tag.setDouble(TAG_TEMP, tempK);
        int[] w = new int[WINDOW];
        for (int i = 0; i < WINDOW; i++) {
            w[i] = (int) Math.round(window[i] * 100);
        }
        tag.setIntArray(TAG_WINDOW, w);
        tag.setByte(TAG_HEAD, (byte) head);
        return tag;
    }

    /** 从 {@code tag} 读回；没有或不对时当作没有温度 */
    public void read(NBTTagCompound tag) {
        if (!tag.hasKey(TAG_TEMP, 99)) {
            tempK = Double.NaN;
            return;
        }
        double t = tag.getDouble(TAG_TEMP);
        int[] w = tag.getIntArray(TAG_WINDOW);
        if (!(t > 0) || w.length != WINDOW) {
            if (t > 0) {
                reset(t);
            } else {
                tempK = Double.NaN;
            }
            return;
        }
        tempK = t;
        sum = 0;
        for (int i = 0; i < WINDOW; i++) {
            window[i] = w[i] / 100.0;
            sum += window[i];
        }
        head = Math.floorMod(tag.getByte(TAG_HEAD), WINDOW);
    }
}
