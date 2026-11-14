package moe.qingu.youran.network;

import moe.qingu.youran.api.network.Ipv6Address;
import moe.qingu.youran.api.network.NetworkDevices;
import moe.qingu.youran.api.network.tls.TlsServerContext;
import net.minecraft.entity.player.EntityPlayerMP;

import java.util.Collection;
import java.util.UUID;
import java.util.function.Supplier;

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

    /** 给拥有 {@code names} 里这些名字的网站做 TLS（证书按名字现签）；服务器没配中间 CA 时为 null */
    TlsServerContext serverTls(Supplier<? extends Collection<String>> names);

    /**
     * 玩家客户端发来的原始包（在 Netty 线程上调用；实现马上转给网络线程，不碰世界）。
     * 消息不是原始包或长度不对时 {@code packet} 为 null，{@code messageLength} 是消息的字节数。
     * {@code playerNic} 为 true 时是玩家临时地址的包（PLAYER_PACKET），否则是他操作的设备的包（PACKET）。
     */
    void onClientPacket(EntityPlayerMP player, byte[] packet, int messageLength, boolean playerNic);

    /** 玩家现在的临时地址；没有（不在线、网络没在运行）为 null。任意线程 */
    Ipv6Address playerAddress(UUID player);

    /** 给玩家的临时地址放行一组目标主机（见 {@code api.network.PlayerAddresses#allow}）；任意线程 */
    boolean allowPlayer(UUID player, String purpose, Collection<Ipv6Address> hosts, long ttlMillis);

    /** 撤销一个用途的放行；任意线程 */
    void revokePlayer(UUID player, String purpose);

    /** YouranMod 自己的 .yr / .com 区里登记的名字（A-label）的地址；没有为 null。任意线程 */
    Ipv6Address localNameAddress(String asciiName);
}
