package moe.qingu.youran.item;

import moe.qingu.youran.logic.litho.ChipModel;
import moe.qingu.youran.logic.litho.ChipQuality;
import net.minecraft.nbt.NBTTagCompound;

/**
 * 芯片的 NBT（服主 11-15：每个都独一无二，NBT 记各项参数）。
 * <pre>
 * {Model:"z80", Serial:"Z80-3F2A9C1E", Scrapped:0b,
 *  HeatTolerance:22100,   // 耐热性 K·s（最近 1 分钟温度对时间积分的上限）
 *  MaxFreq:4870,          // 最大频率 kHz
 *  Lifetime:1080000L,     // 寿命：开机秒数
 *  StdFreq:4000, MinFreq:2500, MaxAdjFreq:6000,  // 这型 CPU 自带的标准频率与可调范围 kHz
 *  Made:{Nines:9, Mold:"gold", Resist:"negative", Solvent:900, Align:930, Lamp:780, Package:"gold", MaskWear:12}}
 * </pre>
 * 报废的芯片 {@code Scrapped:1b}、{@code ScrapReason}（resolution / alignment / defect / exposed_resist），没有三项指标。
 * 涂过变质（已曝光）光刻胶的，{@code Made} 里另记 {@code ExposedResist:1b}。
 */
public final class ChipNbt {
    public static final String SERIAL = "Serial";
    public static final String SCRAPPED = "Scrapped";
    public static final String SCRAP_REASON = "ScrapReason";
    public static final String HEAT = "HeatTolerance";
    public static final String MAX_FREQ = "MaxFreq";
    public static final String LIFETIME = "Lifetime";
    public static final String STD_FREQ = "StdFreq";
    public static final String MIN_FREQ = "MinFreq";
    public static final String MAX_ADJ_FREQ = "MaxAdjFreq";
    public static final String MADE = "Made";

    private ChipNbt() {
    }

    public static NBTTagCompound tag(ChipModel model, String serial, ChipQuality.Result result, NBTTagCompound made) {
        NBTTagCompound tag = new NBTTagCompound();
        tag.setString(ChipModel.TAG, model.id);
        tag.setString(SERIAL, serial);
        tag.setBoolean(SCRAPPED, result.scrapped());
        if (result.scrapped()) {
            tag.setString(SCRAP_REASON, result.reason.name().toLowerCase(java.util.Locale.ROOT));
        } else {
            tag.setInteger(HEAT, result.heatToleranceKs);
            tag.setInteger(MAX_FREQ, result.maxKHz);
            tag.setLong(LIFETIME, result.lifetimeSeconds);
        }
        tag.setInteger(STD_FREQ, model.standardKHz);
        tag.setInteger(MIN_FREQ, model.minKHz);
        tag.setInteger(MAX_ADJ_FREQ, model.maxAdjustKHz);
        tag.setTag(MADE, made);
        return tag;
    }

    /** 序列号：型号 + 8 位十六进制 */
    public static String serial(ChipModel model, long random) {
        return model.id.toUpperCase(java.util.Locale.ROOT) + "-" + String.format("%08X", random & 0xFFFFFFFFL);
    }
}
