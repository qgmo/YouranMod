package moe.qingu.youran.api.chip;

import moe.qingu.youran.config.ChipRuntimeConfig;
import moe.qingu.youran.logic.litho.ChipModel;

/**
 * 芯片品质接进电脑以后用到的数（服主 11-17：数都由 Claude 定，放配置、写进文档）。不可变；{@link #current()} 按服务端配置
 * {@code config/youranmod.cfg} 的 {@code chip_runtime} 一节（{@link ChipRuntimeConfig}）现算，{@link #DEFAULTS} 是默认值（测试用）。
 * 各项的道理见 qgmo 的设计文档“芯片品质接入”；这里只写一句。
 */
public final class ChipNumbers {
    /** 环境温度：°C = {@link #ambientBaseC} + {@link #ambientPerBiomeC} × 生物群系温度（平原 0.8 → 22 °C，沙漠 2.0 → 52 °C，雪原 0 → 2 °C） */
    public final double ambientBaseC;
    public final double ambientPerBiomeC;
    /** 开着机、CPU 空转时（标准频率）比环境高多少度（K）——升温的目标 */
    public final double riseIdleK;
    /** 开着机、CPU 一直在跑指令时（标准频率）比环境高多少度（K） */
    public final double riseBusyK;
    /** 频率对发热的影响：温升 ×（实际频率 ÷ 标准频率）^ 这个数（1 = 功耗与频率成正比，当年固定电压的芯片就是这样） */
    public final double freqExponent;
    /** 升温、降温的时间常数（秒）：每秒往目标温度靠 1 − e^(−1/τ) */
    public final double timeConstantS;
    /** 过热断电时芯片报废的概率 */
    public final double overheatScrapChance;
    /** 过热断电没报废时少掉的寿命（占这片芯片总寿命的比例） */
    public final double overheatLifeLoss;
    /** 最大频率比标准频率低多少（比例）以上才会出运行错误 */
    public final double faultDeficitStart;
    /** 低到这么多（比例）时出错最多（光刻机做出来最差的芯片大约低 25%） */
    public final double faultDeficitFull;
    /** 出错最多时，CPU 每跑一秒指令平均出几次错 */
    public final double faultRateMax;
    /** 一次运行错误是死机的概率：刚过 {@link #faultDeficitStart} 时、到 {@link #faultDeficitFull} 时（中间按线性） */
    public final double crashShareMin;
    public final double crashShareMax;
    /** 没有主板数据的电脑（自然生成的、这一版之前就有的）当作装了一片“标称”芯片：耐热性 = 60 × 这个温度（K） */
    public final double nominalHeatK;
    /** 调频旋钮一格（kHz）：Z80、8088、YR-2000 */
    public final int stepZ80KHz;
    public final int step8088KHz;
    public final int stepYr2000KHz;

    public static final ChipNumbers DEFAULTS = new ChipNumbers(2, 25, 8, 35, 1, 30, 0.2, 0.05, 0.10, 0.25, 1.0, 0.05, 0.30, 350,
            100, 250, 100_000);

    public ChipNumbers(double ambientBaseC, double ambientPerBiomeC, double riseIdleK, double riseBusyK, double freqExponent, double timeConstantS,
                       double overheatScrapChance, double overheatLifeLoss, double faultDeficitStart, double faultDeficitFull, double faultRateMax,
                       double crashShareMin, double crashShareMax, double nominalHeatK, int stepZ80KHz, int step8088KHz, int stepYr2000KHz) {
        this.ambientBaseC = ambientBaseC;
        this.ambientPerBiomeC = ambientPerBiomeC;
        this.riseIdleK = riseIdleK;
        this.riseBusyK = riseBusyK;
        this.freqExponent = freqExponent;
        this.timeConstantS = Math.max(1, timeConstantS);
        this.overheatScrapChance = overheatScrapChance;
        this.overheatLifeLoss = overheatLifeLoss;
        this.faultDeficitStart = faultDeficitStart;
        this.faultDeficitFull = Math.max(faultDeficitStart + 1e-6, faultDeficitFull);
        this.faultRateMax = faultRateMax;
        this.crashShareMin = crashShareMin;
        this.crashShareMax = crashShareMax;
        this.nominalHeatK = nominalHeatK;
        this.stepZ80KHz = Math.max(1, stepZ80KHz);
        this.step8088KHz = Math.max(1, step8088KHz);
        this.stepYr2000KHz = Math.max(1, stepYr2000KHz);
    }

    /** 按服务端配置（独立服务端读 {@code config/youranmod.cfg}；单人游戏是默认值） */
    public static ChipNumbers current() {
        return new ChipNumbers(ChipRuntimeConfig.ambientBaseC, ChipRuntimeConfig.ambientPerBiomeC, ChipRuntimeConfig.riseIdleK,
                ChipRuntimeConfig.riseBusyK, ChipRuntimeConfig.freqExponent, ChipRuntimeConfig.timeConstantS,
                ChipRuntimeConfig.overheatScrapChance, ChipRuntimeConfig.overheatLifeLoss, ChipRuntimeConfig.faultDeficitStart,
                ChipRuntimeConfig.faultDeficitFull, ChipRuntimeConfig.faultRateMax, ChipRuntimeConfig.crashShareMin,
                ChipRuntimeConfig.crashShareMax, ChipRuntimeConfig.nominalHeatK, ChipRuntimeConfig.stepZ80KHz, ChipRuntimeConfig.step8088KHz,
                ChipRuntimeConfig.stepYr2000KHz);
    }

    /** 生物群系温度（{@code Biome#getTemperature(BlockPos)}，含高度修正）换成环境温度（K） */
    public double ambientK(float biomeTemperature) {
        return 273.15 + ambientBaseC + ambientPerBiomeC * biomeTemperature;
    }

    /** 这一型 CPU 的调频一格（kHz） */
    public int stepKHz(ChipModel model) {
        return model == ChipModel.I8088 ? step8088KHz : model == ChipModel.YR2000 ? stepYr2000KHz : stepZ80KHz;
    }
}
