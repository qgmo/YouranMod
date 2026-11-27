package moe.qingu.youran.api.pcb;

import moe.qingu.youran.logic.litho.ChipModel;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;

import javax.annotation.Nullable;

/**
 * 个人电脑加密卡（{@code tls_pc}，2.3.1.0 第 4 期）的规格与卡号。规格不存进物品 NBT，每次由卡上加密协处理器的品质现算（计划 3.3）：
 * 只用比值 q = 最大频率 ÷ 额定频率（16 MHz）。
 * <pre>
 * q ≥ 1.30   每秒 8 次完整握手   同时 8 个会话   加解密 128 KB/s
 * q ≥ 1.10   4 次                 6 个            64 KB/s
 * q ≥ 0.90   2 次                 4 个            32 KB/s
 * 其余       1 次                 2 个            16 KB/s
 * </pre>
 * 会话复用的握手算半次；超过每秒握手数的排队（最多 10 秒），超过同时会话数的报卡忙。这张表和 079 服务里加密卡固件的
 * {@code tlscard.SpecFor} 是同一张。游戏服务器把算好的规格随 {@code tlscard} 消息交给 079 节点（字段 {@code hs}、{@code sessions}、
 * {@code bps}：每秒字节数）。悬停提示也用它，所以客户端包里也有。
 * <p>
 * 卡号（{@link #CARD_ID}，long）：卡的私钥（A 方式）与证书链存在游戏服务器的世界数据里，按卡号，<b>不进物品 NBT</b>（物品 NBT 会同步给客户端）。
 * 卡号照软盘盘号的办法在服务端分配（第一次进物品栏、掉在地上、合成出来时）。
 */
public final class TlsSpec {
    /** 物品 NBT 里卡号的键 */
    public static final String CARD_ID = "CardId";

    /** 每秒完整握手数（复用的算半次） */
    public final int handshakes;
    /** 同时会话数（客户端、服务器会话一起算） */
    public final int sessions;
    /** 加解密吞吐（每秒字节数） */
    public final int bytesPerSecond;

    public TlsSpec(int handshakes, int sessions, int bytesPerSecond) {
        this.handshakes = handshakes;
        this.sessions = sessions;
        this.bytesPerSecond = bytesPerSecond;
    }

    /** 按芯片的最大频率与额定频率（kHz）查表；整数比较（{@code max × 100 ≥ 130 × rated}），边界上不会差一档 */
    public static TlsSpec forFrequency(int maxKHz, int ratedKHz) {
        long m = (long) maxKHz * 100;
        long r = Math.max(1, ratedKHz);
        if (m >= 130 * r) {
            return new TlsSpec(8, 8, 128 * 1024);
        }
        if (m >= 110 * r) {
            return new TlsSpec(4, 6, 64 * 1024);
        }
        if (m >= 90 * r) {
            return new TlsSpec(2, 4, 32 * 1024);
        }
        return lowest();
    }

    /** 最低一档 */
    public static TlsSpec lowest() {
        return new TlsSpec(1, 2, 16 * 1024);
    }

    /** 一块 ISA 扩展卡作为加密卡的规格；没装加密协处理器、芯片报废了时为 null（这块卡不能当加密卡用） */
    @Nullable
    public static TlsSpec of(@Nullable NBTTagCompound board) {
        if (board == null || BoardNbt.controllerModel(board) != ChipModel.TLS_COPROC) {
            return null;
        }
        NBTTagCompound chip = board.getCompoundTag(BoardNbt.CTRL);
        int max = chip.getInteger(moe.qingu.youran.item.ChipNbt.MAX_FREQ);
        if (chip.getBoolean(moe.qingu.youran.item.ChipNbt.SCRAPPED) || max <= 0) {
            return null;
        }
        return forFrequency(max, ChipModel.TLS_COPROC.ratedKHz);
    }

    /** 加密卡物品的规格：没有 {@code Board} 的（{@code /give} 出来的）按最低一档；芯片报废、没装芯片的为 null */
    @Nullable
    public static TlsSpec ofItem(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return null;
        }
        NBTTagCompound tag = stack.getTagCompound();
        if (tag == null || !tag.hasKey(BoardNbt.TAG, 10)) {
            return lowest();
        }
        return of(tag.getCompoundTag(BoardNbt.TAG));
    }

    /** 加密卡物品的卡号（还没分配时 0） */
    public static long cardId(ItemStack stack) {
        if (stack == null || stack.isEmpty() || !stack.hasTagCompound()) {
            return 0;
        }
        return stack.getTagCompound().getLong(CARD_ID);
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof TlsSpec && ((TlsSpec) o).handshakes == handshakes && ((TlsSpec) o).sessions == sessions
                && ((TlsSpec) o).bytesPerSecond == bytesPerSecond;
    }

    @Override
    public int hashCode() {
        return (handshakes * 31 + sessions) * 31 + bytesPerSecond;
    }

    @Override
    public String toString() {
        return "每秒 " + handshakes + " 次握手，同时 " + sessions + " 个会话，" + bytesPerSecond + " 字节每秒";
    }
}
