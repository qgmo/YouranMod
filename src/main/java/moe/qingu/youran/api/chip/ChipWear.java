package moe.qingu.youran.api.chip;

import moe.qingu.youran.api.pcb.BoardNbt;
import moe.qingu.youran.item.ChipNbt;
import net.minecraft.nbt.NBTTagCompound;

import javax.annotation.Nullable;
import java.util.Random;

/**
 * CPU 的寿命与过热的后果（服主 11-15、11-17）。直接改主板数据里那片 CPU 的芯片 NBT（{@code Board.Cpu}）：
 * <ul>
 *     <li>寿命 = 开机时长，用掉的秒数记在 {@value CpuChip#USED}；用完就坏（{@code Scrapped:1b, ScrapReason:"worn_out"}）；</li>
 *     <li>过热断电：有 {@link ChipNumbers#overheatScrapChance} 的概率报废（{@code ScrapReason:"overheat"}），没报废也少掉总寿命的
 *     {@link ChipNumbers#overheatLifeLoss}（少到用完就是用坏了）。</li>
 * </ul>
 * 报废后照光刻机出厂报废的样子去掉三项指标（芯片悬停、主板悬停都显示报废原因）。没有主板数据、主板上没有 CPU 时什么都不做。
 */
public final class ChipWear {
    public static final String REASON_WORN_OUT = "worn_out";
    public static final String REASON_OVERHEAT = "overheat";

    private ChipWear() {
    }

    /** 过热断电的结果 */
    public enum Overheat {
        /** 没有能记的芯片（标称芯片、没装 CPU、本来就坏了） */
        NONE,
        /** 芯片报废了 */
        SCRAPPED,
        /** 少了一截寿命（没用完） */
        LIFE_LOST,
        /** 少的那一截正好用完了寿命：用坏了 */
        WORN_OUT
    }

    @Nullable
    private static NBTTagCompound cpu(@Nullable NBTTagCompound board) {
        if (board == null || !BoardNbt.hasCpu(board)) {
            return null;
        }
        NBTTagCompound c = board.getCompoundTag(BoardNbt.CPU);
        return c.getBoolean(ChipNbt.SCRAPPED) ? null : c;
    }

    /** 记下开机了 {@code seconds} 秒；返回这一下是不是把寿命用完了（芯片随之报废） */
    public static boolean addUsed(@Nullable NBTTagCompound board, long seconds) {
        NBTTagCompound c = cpu(board);
        if (c == null || seconds <= 0) {
            return false;
        }
        long used = Math.max(0, c.getLong(CpuChip.USED)) + seconds;
        c.setLong(CpuChip.USED, used);
        if (c.getLong(ChipNbt.LIFETIME) > 0 && used >= c.getLong(ChipNbt.LIFETIME)) {
            scrap(c, REASON_WORN_OUT);
            return true;
        }
        return false;
    }

    /** 过热断电了：掷一次报废，没报废少寿命 */
    public static Overheat overheat(@Nullable NBTTagCompound board, ChipNumbers n, Random random) {
        NBTTagCompound c = cpu(board);
        if (c == null) {
            return Overheat.NONE;
        }
        if (random.nextDouble() < n.overheatScrapChance) {
            scrap(c, REASON_OVERHEAT);
            return Overheat.SCRAPPED;
        }
        long loss = Math.round(c.getLong(ChipNbt.LIFETIME) * n.overheatLifeLoss);
        return addUsed(board, Math.max(1, loss)) ? Overheat.WORN_OUT : Overheat.LIFE_LOST;
    }

    /** 报废：同光刻机出厂报废（去掉三项指标，记原因）；用掉的寿命留着 */
    static void scrap(NBTTagCompound chip, String reason) {
        chip.setBoolean(ChipNbt.SCRAPPED, true);
        chip.setString(ChipNbt.SCRAP_REASON, reason);
        chip.removeTag(ChipNbt.HEAT);
        chip.removeTag(ChipNbt.MAX_FREQ);
        chip.removeTag(ChipNbt.LIFETIME);
    }
}
