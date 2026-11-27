package moe.qingu.youran.network;

import net.minecraft.entity.player.EntityPlayerMP;

/** {@code ClientRelayNic} 交给中转的另一头（YouranMod 内部） */
public interface RelayEnd {
    /** 中转改“现在接着谁”（接上、被别的网卡顶掉、玩家下线）；null 为没接 */
    void connected(EntityPlayerMP player);

    /** 接着的玩家客户端发来的包，从这张网卡进网络 */
    boolean fromClient(byte[] packet);
}
