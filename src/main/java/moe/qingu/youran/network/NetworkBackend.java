package moe.qingu.youran.network;

import com.mojang.authlib.GameProfile;
import moe.qingu.youran.api.network.ConfirmRequest;
import moe.qingu.youran.api.network.ConfirmResult;
import moe.qingu.youran.api.network.Ipv6Address;
import moe.qingu.youran.api.network.NetworkInterface;
import moe.qingu.youran.api.network.identity.OperatorResult;
import moe.qingu.youran.api.network.location.LocationResult;
import net.minecraft.entity.player.EntityPlayerMP;

import java.util.Collection;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * 服务端网络的实现（在服务端专用的 {@code server.net} 包里，客户端包里没有）。网络运行时由它登记到 {@link NetworkHooks}，
 * 两端共用的类（api、网络包）只通过这个接口找它。
 */
public interface NetworkBackend {
    /**
     * 把网卡插到维度 {@code dimension} 的 (x, y, z)，用这一格的 0 号地址；没有地址、这一格已经插着别的网卡时返回 null（不顶掉旧的）。
     * 任意线程。调用方（{@code YouranNetworkApi.plug}）拿着网卡的锁，并负责把返回的端口装到网卡上
     */
    NicPort plug(NetworkInterface nic, int dimension, int x, int y, int z);

    /** 拔掉这个端口（{@code YouranNetworkApi.unplug} 已经把它从网卡上拿下）。任意线程 */
    void unplug(NicPort port);

    /** 这一格（0 号）的地址；没有地址为 null。不要求插着网卡 */
    Ipv6Address addressAt(int dimension, int x, int y, int z);

    /** 网卡与玩家客户端之间的中转（{@code ClientRelayNic} 用） */
    ClientRelay relay();

    /** 登记过的主机的地址；没有为 null */
    Ipv6Address hostAddress(String key);

    /** 登记过的原始主机；没有（没登记、没分到地址）为 null。任意线程 */
    moe.qingu.youran.api.network.RawHost rawHost(String key);

    /** 整个服务器内网的 /48 前缀 */
    Ipv6Address networkPrefix();

    /** 递归解析器地址（设备上的协议栈只问它） */
    Ipv6Address resolverAddress();

    Ipv6Address ntpAddress();

    /** 证书签发服务（ca.yr）；没配中间 CA 时也有，申请都失败并说明原因 */
    moe.qingu.youran.api.network.ca.CertificateAuthority certificateAuthority();

    /**
     * 玩家客户端发来的原始包（在 Netty 线程上调用；实现马上转给网络线程，不碰世界）。{@code port} 是网口号（0 是玩家自己的网口）。
     * 消息不是原始包或长度不对时 {@code packet} 为 null、{@code port} 为 -1，{@code messageLength} 是消息的字节数。
     */
    void onClientPacket(EntityPlayerMP player, int port, byte[] packet, int messageLength);

    /** 玩家现在的临时地址；没有（不在线、网络没在运行）为 null。任意线程 */
    Ipv6Address playerAddress(UUID player);

    /** 给玩家的临时地址放行一组目标主机（见 {@code api.network.PlayerAddresses#allow}）；任意线程 */
    boolean allowPlayer(UUID player, String purpose, Collection<Ipv6Address> hosts, long ttlMillis);

    /** 撤销一个用途的放行；任意线程 */
    void revokePlayer(UUID player, String purpose);

    /** 世界存档目录（默认注册局存注册信息用） */
    java.io.File worldDirectory();

    /** 系统确认框：直接问在线玩家 {@code player}。任意线程；future 在主线程上完成 */
    CompletableFuture<ConfirmResult> confirm(UUID player, ConfirmRequest request);

    /** 主线程：客户端对确认框的回应 */
    void onConfirmAnswer(EntityPlayerMP player, long id, boolean approve);

    /** 主线程：玩家客户端回来的 {@code /ynet} 结果的一行（{@code net.ProbeMessages.Line}） */
    void onProbeLine(EntityPlayerMP player, long id, String text, boolean done);

    /** 给网卡的地址签位置（先问 {@code player}）。任意线程；future 在主线程上完成 */
    CompletableFuture<LocationResult> signLocation(NetworkInterface nic, GameProfile player, int dimension, int x, int y, int z, String site, String nonce);

    /** 给网卡的地址签操作者证明（先问 {@code player}）。任意线程；future 在主线程上完成 */
    CompletableFuture<OperatorResult> signOperator(NetworkInterface nic, GameProfile player, String site, String nonce);

    /** 定位模块的签名公钥；没有为 null */
    java.security.PublicKey locationKey();

    /** 身份模块（操作者证明）的签名公钥；没有为 null */
    java.security.PublicKey operatorKey();
}
