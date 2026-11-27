package moe.qingu.youran.api.pcb;

import moe.qingu.youran.logic.litho.ChipModel;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;

import javax.annotation.Nullable;

/**
 * 网卡的规格（2.3.1.0 第 1 期，计划 3.1、3.2；服主 11-27 选 B：网卡不单独限速，芯片品质只决定同时连接数与缓冲区）。
 * 规格不存进物品 NBT，每次由卡上网卡控制芯片的品质现算：只用比值 q = 最大频率 ÷ 额定频率（光刻机出的芯片在 0.75–1.5 之间，
 * 创造物品栏的标准芯片是 1.3）。
 * <pre>
 * q ≥ 1.30   8 个连接   每个连接收 / 发 4 KB
 * q ≥ 1.10   6 个连接   2 KB / 2 KB
 * q ≥ 0.90   4 个连接   2 KB / 2 KB
 * 其余       2 个连接   1 KB / 1 KB
 * </pre>
 * 这张表和 079 服务里网卡固件的 {@code netcard.SpecFor} 是同一张（那边只在没收到规格时用最低一档）。游戏服务器把算好的规格随
 * {@code nic} 消息交给 079 节点（字段 {@code conns}、{@code rx}、{@code tx}）。悬停提示也用它，所以客户端包里也有。
 */
public final class NicSpec {
    /** 同时 TCP 连接数（监听的不算；UDP 另有固定 4 个） */
    public final int conns;
    /** 每个连接的收缓冲（字节） */
    public final int rxBytes;
    /** 每个连接的发缓冲（字节） */
    public final int txBytes;

    public NicSpec(int conns, int rxBytes, int txBytes) {
        this.conns = conns;
        this.rxBytes = rxBytes;
        this.txBytes = txBytes;
    }

    /**
     * 按芯片的最大频率与额定频率（kHz）查表。用整数比较（{@code max × 100 ≥ 130 × rated}），不经浮点，边界上不会差一档。
     */
    public static NicSpec forFrequency(int maxKHz, int ratedKHz) {
        long m = (long) maxKHz * 100;
        long r = Math.max(1, ratedKHz);
        if (m >= 130 * r) {
            return new NicSpec(8, 4096, 4096);
        }
        if (m >= 110 * r) {
            return new NicSpec(6, 2048, 2048);
        }
        if (m >= 90 * r) {
            return new NicSpec(4, 2048, 2048);
        }
        return new NicSpec(2, 1024, 1024);
    }

    /** 最低一档（q 不到 0.90） */
    public static NicSpec lowest() {
        return new NicSpec(2, 1024, 1024);
    }

    /**
     * 一块 ISA 扩展卡（{@link BoardNbt}）作为网卡的规格。没装网卡控制芯片、芯片报废了（没有最大频率）时为 null：这块卡不能当网卡用。
     */
    @Nullable
    public static NicSpec of(@Nullable NBTTagCompound board) {
        if (board == null || BoardNbt.controllerModel(board) != ChipModel.NIC_CTRL) {
            return null;
        }
        NBTTagCompound chip = board.getCompoundTag(BoardNbt.CTRL);
        int max = chip.getInteger(moe.qingu.youran.item.ChipNbt.MAX_FREQ);
        if (chip.getBoolean(moe.qingu.youran.item.ChipNbt.SCRAPPED) || max <= 0) {
            return null;
        }
        return forFrequency(max, ChipModel.NIC_CTRL.ratedKHz);
    }

    /**
     * 网卡物品（{@code nic_pc}）的规格：物品 NBT 里 {@link BoardNbt#TAG} 的那块板子。没有 {@code Board} 的（{@code /give} 出来的空物品）
     * 按最低一档；有板子但芯片报废、没装芯片的为 null。
     */
    @Nullable
    public static NicSpec ofItem(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return null;
        }
        NBTTagCompound tag = stack.getTagCompound();
        if (tag == null || !tag.hasKey(BoardNbt.TAG, 10)) {
            return lowest();
        }
        return of(tag.getCompoundTag(BoardNbt.TAG));
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof NicSpec && ((NicSpec) o).conns == conns && ((NicSpec) o).rxBytes == rxBytes && ((NicSpec) o).txBytes == txBytes;
    }

    @Override
    public int hashCode() {
        return (conns * 31 + rxBytes) * 31 + txBytes;
    }

    @Override
    public String toString() {
        return conns + " 个连接，收 " + rxBytes + " / 发 " + txBytes + " 字节";
    }
}
