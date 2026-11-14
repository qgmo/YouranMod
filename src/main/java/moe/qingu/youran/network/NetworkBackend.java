package moe.qingu.youran.network;

import moe.qingu.youran.api.network.Ipv6Address;
import moe.qingu.youran.api.network.NetworkDevices;
import net.minecraft.entity.player.EntityPlayerMP;

/**
 * 服务端网络的实现（在服务端专用的 {@code server.net} 包里，客户端包里没有）。网络运行时由它登记到 {@link NetworkHooks}，
 * 两端共用的类（api、网络包）只通过这个接口找它。
 */
public interface NetworkBackend {
    /** 设备表（主线程用） */
    NetworkDevices devices();

    /** 登记过的主机的地址；没有为 null */
    Ipv6Address hostAddress(String key);

    /** 递归解析器地址（设备上的协议栈只问它） */
    Ipv6Address resolverAddress();

    Ipv6Address ntpAddress();

    /**
     * 玩家客户端发来的原始包（在 Netty 线程上调用；实现马上转给网络线程，不碰世界）。
     * 消息不是 PACKET 或长度不对时 {@code packet} 为 null，{@code messageLength} 是消息的字节数。
     */
    void onClientPacket(EntityPlayerMP player, byte[] packet, int messageLength);
}
