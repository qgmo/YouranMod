package moe.qingu.youran.api.chip;

import moe.qingu.youran.api.pcb.BoardNbt;
import moe.qingu.youran.item.ChipNbt;
import moe.qingu.youran.logic.litho.ChipModel;
import net.minecraft.nbt.NBTTagCompound;

import javax.annotation.Nullable;

/**
 * 电脑里那片 CPU 的品质（只读的快照）：主板数据 {@code Board.Cpu} 里的芯片 NBT（{@code item.ChipNbt}），没有主板数据的电脑当作一片“标称”芯片
 * （{@link #nominal}：最大频率 = 标准频率、耐热性 = 60 × {@link ChipNumbers#nominalHeatK}、没有寿命）。
 * <p>
 * 寿命用掉多少记在芯片 NBT 的 {@value #USED}（开机秒数，服主 11-17：寿命是开机时长，用完就坏），见 {@link ChipWear}。
 */
public final class CpuChip {
    /** 芯片 NBT 里记已经开机多少秒（长整数） */
    public static final String USED = "Used";

    public final ChipModel model;
    /** 没有主板数据（标称芯片）：不记寿命、不会报废 */
    public final boolean nominal;
    /** 没装 CPU（主板上缺）、报废了、寿命用完了 */
    public final boolean dead;
    public final int stdKHz;
    public final int minKHz;
    public final int maxAdjKHz;
    /** 最大频率（kHz；坏了为 0） */
    public final int maxKHz;
    /** 耐热性（K·s；坏了为 0） */
    public final int heatToleranceKs;
    /** 总寿命、已用（秒）；标称芯片为 Long.MAX_VALUE、0 */
    public final long lifetimeS;
    public final long usedS;

    private CpuChip(ChipModel model, boolean nominal, boolean dead, int stdKHz, int minKHz, int maxAdjKHz, int maxKHz, int heatToleranceKs,
                    long lifetimeS, long usedS) {
        this.model = model;
        this.nominal = nominal;
        this.dead = dead;
        this.stdKHz = stdKHz;
        this.minKHz = minKHz;
        this.maxAdjKHz = maxAdjKHz;
        this.maxKHz = maxKHz;
        this.heatToleranceKs = heatToleranceKs;
        this.lifetimeS = lifetimeS;
        this.usedS = usedS;
    }

    /** 标称芯片（没有主板数据的电脑） */
    public static CpuChip nominal(ChipModel model, ChipNumbers n) {
        return new CpuChip(model, true, false, model.ratedKHz, model.minKHz, model.maxAdjustKHz, model.ratedKHz,
                (int) Math.round(60 * n.nominalHeatK), Long.MAX_VALUE, 0);
    }

    /**
     * 电脑的 CPU：{@code board} 是主板数据（{@link BoardNbt#TAG} 里那一份），null = 没有主板数据（标称芯片，型号按 {@code fallback}）。
     * 主板上没装 CPU 时是坏的。
     */
    public static CpuChip of(@Nullable NBTTagCompound board, ChipModel fallback, ChipNumbers n) {
        if (board == null) {
            return nominal(fallback, n);
        }
        if (!BoardNbt.hasCpu(board)) {
            return new CpuChip(fallback, false, true, fallback.ratedKHz, fallback.minKHz, fallback.maxAdjustKHz, 0, 0, 0, 0);
        }
        return ofChip(board.getCompoundTag(BoardNbt.CPU), fallback);
    }

    /** 一片 CPU 的芯片 NBT */
    public static CpuChip ofChip(NBTTagCompound chip, ChipModel fallback) {
        ChipModel m = ChipModel.byId(chip.getString(ChipModel.TAG));
        if (m == null) {
            m = fallback;
        }
        int std = chip.getInteger(ChipNbt.STD_FREQ) > 0 ? chip.getInteger(ChipNbt.STD_FREQ) : m.ratedKHz;
        int min = chip.getInteger(ChipNbt.MIN_FREQ) > 0 ? chip.getInteger(ChipNbt.MIN_FREQ) : m.minKHz;
        int maxAdj = chip.getInteger(ChipNbt.MAX_ADJ_FREQ) > 0 ? chip.getInteger(ChipNbt.MAX_ADJ_FREQ) : m.maxAdjustKHz;
        long life = chip.getLong(ChipNbt.LIFETIME);
        long used = Math.max(0, chip.getLong(USED));
        // 没写寿命的（手改的 NBT、旧数据）当作寿命不限
        boolean dead = chip.getBoolean(ChipNbt.SCRAPPED) || (life > 0 && used >= life) || chip.getInteger(ChipNbt.MAX_FREQ) <= 0;
        return new CpuChip(m, false, dead, std, Math.min(min, std), Math.max(maxAdj, std), dead ? 0 : chip.getInteger(ChipNbt.MAX_FREQ),
                dead ? 0 : chip.getInteger(ChipNbt.HEAT), life, used);
    }

    /** 还剩多少寿命（秒；标称芯片为 Long.MAX_VALUE） */
    public long remainingS() {
        return nominal || lifetimeS <= 0 ? Long.MAX_VALUE : Math.max(0, lifetimeS - usedS);
    }
}
