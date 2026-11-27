package moe.qingu.youran.network;

import moe.qingu.youran.api.network.Ipv6Address;
import net.minecraft.entity.player.EntityPlayerMP;

/**
 * 网卡与玩家客户端之间的中转（YouranMod 内部；{@code ClientRelayNic} 用它）。一个玩家同时只接一张网卡：接新的时旧的断开；
 * 接上时服务器告诉客户端多了一个网口（地址、递归解析器、NTP），断开时告诉客户端去掉。网络没在运行时什么都不做（{@link NetworkHooks#relay()}）。
 */
public interface ClientRelay {
    /** 主线程：把 {@code end} 接到这个玩家的客户端（{@code address} 是网卡地址） */
    void connect(RelayEnd end, Ipv6Address address, EntityPlayerMP player);

    /** 主线程：断开（没接着时什么都不做） */
    void disconnect(RelayEnd end);

    /** 网络线程：发给接着的玩家客户端；这个玩家现在没接网卡、连接断了时返回 false */
    boolean toClient(EntityPlayerMP player, byte[] packet);

    /** 网络没在运行时用的：什么都接不上 */
    ClientRelay NONE = new ClientRelay() {
        @Override
        public void connect(RelayEnd end, Ipv6Address address, EntityPlayerMP player) {
        }

        @Override
        public void disconnect(RelayEnd end) {
        }

        @Override
        public boolean toClient(EntityPlayerMP player, byte[] packet) {
            return false;
        }
    };
}
