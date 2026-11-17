package moe.qingu.youran.network;

import moe.qingu.youran.api.network.ConfirmRequest;
import moe.qingu.youran.api.network.ConfirmResult;
import moe.qingu.youran.api.network.DeviceLocation;
import moe.qingu.youran.api.network.location.LocationResult;
import moe.qingu.youran.api.network.Ipv6Address;
import moe.qingu.youran.api.network.NetworkDevices;
import moe.qingu.youran.api.network.SourceIdentity;
import moe.qingu.youran.api.network.tls.TlsServerContext;
import net.minecraft.entity.player.EntityPlayerMP;

import java.util.Collection;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
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

    /** 登记过的原始主机；没有（没登记、没分到地址）为 null。任意线程 */
    moe.qingu.youran.api.network.RawHost rawHost(String key);

    /** 整个服务器内网的 /48 前缀 */
    Ipv6Address networkPrefix();

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

    /** 世界存档目录（默认注册局存注册信息用） */
    java.io.File worldDirectory();

    /** 这个地址是设备表里一台设备的地址时，它在哪；否则 null。任意线程（不读世界） */
    DeviceLocation deviceLocation(Ipv6Address address);

    /** 按来源地址认人（设备或玩家临时地址）；认不出为 null。任意线程（不读世界） */
    SourceIdentity identify(Ipv6Address address);

    /** 系统确认框：问来源 {@code source} 的操作者（{@code source} 为 null 时直接问在线玩家 {@code player}）。任意线程；future 在主线程上完成 */
    CompletableFuture<ConfirmResult> confirm(Ipv6Address source, UUID player, ConfirmRequest request);

    /** 主线程：客户端对确认框的回应 */
    void onConfirmAnswer(EntityPlayerMP player, long id, boolean approve);

    /** 给设备签位置（先问操作者）。任意线程；future 在主线程上完成 */
    CompletableFuture<LocationResult> signLocation(Ipv6Address device, String site, String nonce);

    /** 主线程：客户端请位置（按他现在操作的设备签），结果发回给他 */
    void onLocationRequest(EntityPlayerMP player, int requestId, String site, String nonce);

    /** 定位模块的签名公钥；没有为 null */
    java.security.PublicKey locationKey();
}
