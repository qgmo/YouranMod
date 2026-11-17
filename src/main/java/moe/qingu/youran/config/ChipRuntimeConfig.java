package moe.qingu.youran.config;

import net.minecraftforge.common.config.Config;

/**
 * 芯片品质接进电脑（服主 11-17）：CPU 温度、耐热性、寿命、运行错误、调频旋钮的数。YouranSCP 的普通电脑、个人电脑与家具电脑都按这里
 * （经 {@code api.chip.ChipNumbers#current()}）。各项的道理见设计文档“芯片品质接入”。改了要重启服务器。
 */
@Config(modid = ServerConfig.KEY, name = ServerConfig.FILE, category = "chip_runtime")
public final class ChipRuntimeConfig {
    @Config.Comment({"环境温度（°C）= ambientBaseC + ambientPerBiomeC × 生物群系温度（含高度修正）。",
            "默认 2 + 25 × t：平原 0.8 → 22 °C，丛林 0.95 → 26 °C，沙漠 / 下界 2.0 → 52 °C，雪原 0 → 2 °C，冰刺 -0.5 → -10.5 °C。"})
    public static double ambientBaseC = 2;
    public static double ambientPerBiomeC = 25;

    @Config.Comment("CPU 空转（等键、提示符下）时、标准频率下比环境高多少度（K）。默认 8。")
    @Config.RangeDouble(min = 0, max = 500)
    public static double riseIdleK = 8;

    @Config.Comment({"CPU 一直在跑指令时、标准频率下比环境高多少度（K）。默认 35：平原（22 °C）满载约 57 °C，",
            "标准芯片（耐热性 21150 K·s，即一分钟平均 352.5 K ≈ 79 °C）在平原超频到它的最大频率也不过热，在沙漠满载就过热。"})
    @Config.RangeDouble(min = 0, max = 500)
    public static double riseBusyK = 35;

    @Config.Comment("温升 ×（实际频率 ÷ 标准频率）^ freqExponent。默认 1：功耗与频率成正比（当年芯片电压固定）。")
    @Config.RangeDouble(min = 0, max = 4)
    public static double freqExponent = 1;

    @Config.Comment("升温、降温的时间常数（秒）。默认 30：满载约 1–2 分钟到顶，断电后约 1 分钟凉下来。")
    @Config.RangeDouble(min = 1, max = 3600)
    public static double timeConstantS = 30;

    @Config.Comment("过热断电（最近 60 秒温度对时间的积分超过耐热性）时芯片报废的概率。默认 0.2。")
    @Config.RangeDouble(min = 0, max = 1)
    public static double overheatScrapChance = 0.2;

    @Config.Comment("过热断电、芯片没报废时少掉的寿命（占这片芯片总寿命的比例）。默认 0.05：芯片寿命 3000–15000 开机小时（创造模式的标准芯片 10500 小时），一次少 150–750 小时（标准芯片 525 小时，相当于连着开机三个星期）。")
    @Config.RangeDouble(min = 0, max = 1)
    public static double overheatLifeLoss = 0.05;

    @Config.Comment({"运行错误：只在设定频率超过芯片最大频率（被封顶）、且最大频率比标准频率低 faultDeficitStart 以上时出。",
            "出错率（CPU 每跑一秒指令的次数）= faultRateMax × x²，x =（低的比例 − faultDeficitStart）÷（faultDeficitFull − faultDeficitStart），到 1 为止。",
            "默认 0.10 / 0.25 / 1.0：最大频率是标准的 85% 时约每 9 秒一次，75%（光刻机做出来最差的）每秒一次。"})
    @Config.RangeDouble(min = 0, max = 1)
    public static double faultDeficitStart = 0.10;
    @Config.RangeDouble(min = 0, max = 1)
    public static double faultDeficitFull = 0.25;
    @Config.RangeDouble(min = 0, max = 1000)
    public static double faultRateMax = 1.0;

    @Config.Comment("一次运行错误是死机（不是算错、乱写内存）的概率：刚开始出错时 crashShareMin，最差时 crashShareMax，中间线性。默认 0.05 / 0.30。")
    @Config.RangeDouble(min = 0, max = 1)
    public static double crashShareMin = 0.05;
    @Config.RangeDouble(min = 0, max = 1)
    public static double crashShareMax = 0.30;

    @Config.Comment("没有主板数据的电脑（自然生成的、这一版之前就有的）当作一片标称芯片：耐热性 = 60 × nominalHeatK（K·s），最大频率 = 标准频率，没有寿命。默认 350 K。")
    @Config.RangeDouble(min = 0, max = 2000)
    public static double nominalHeatK = 350;

    @Config.Comment("调频旋钮一格（kHz）：Z80（1.3–3.2 MHz）默认 100，8088（4.773–8 MHz）默认 250，YR-2000（1.6–2.6 GHz）默认 100000。")
    @Config.RangeInt(min = 1)
    public static int stepZ80KHz = 100;
    @Config.RangeInt(min = 1)
    public static int step8088KHz = 250;
    @Config.RangeInt(min = 1)
    public static int stepYr2000KHz = 100_000;

    private ChipRuntimeConfig() {
    }
}
