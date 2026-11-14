package moe.qingu.youran.api.network;

import moe.qingu.youran.network.NetworkBackend;
import moe.qingu.youran.network.NetworkHooks;
import moe.qingu.youran.network.NetworkRegistry;
import net.minecraft.entity.player.EntityPlayer;

import java.util.Collection;
import java.util.UUID;

/**
 * 玩家临时地址（服主 11-14）：玩法 /64 里留了一个 /96 给玩家（{@link NetworkLayout#PLAYERS_DATACENTER}），玩家上线时分到一个随机的 /128，
 * 每次上线都重新随机、同一时刻不重复，下线收回，不进存档。玩家的客户端上有一块受限的网卡（{@link ClientNic#player()}）。
 * <p>
 * 这个地址受服务器控制，规则只在服务端的路由器上执行：源地址必须是他自己的；只能连递归解析器的 DNS（UDP 53）和
 * “放行的目标主机”的 TCP 80 / 443；不接受别人主动连入；每秒包数、流量有上限；其余丢掉并回“目标不可达”。
 * <p>
 * 放行的目标主机按<b>用途</b>决定：每个用途（写成“模组 ID:名字”，如 {@code cfm:spectate}）一组主机、一个有效期，
 * 再调用就整组替换；允许的主机是所有没过期用途的并集。只收服务器内网（这个 /48）里、不是玩家临时地址的主机，每个用途最多 64 台，
 * 有效期最长 1 小时（要更久就在过期前再调用一次）。撤销或过期后，到那些主机的已有连接也断。
 * <p>
 * 都只在服务端有用；网络没在运行、玩家临时地址没开（配置 {@code network.playerAddresses}）、玩家不在线时
 * {@link #address} 返回 null、{@link #allow} 返回 false。可以在任意线程调用。
 */
public final class PlayerAddresses {
    private PlayerAddresses() {
    }

    /** 玩家现在的临时地址；没有为 null */
    public static Ipv6Address address(UUID player) {
        NetworkBackend b = NetworkHooks.backend();
        return b == null || player == null ? null : b.playerAddress(player);
    }

    public static Ipv6Address address(EntityPlayer player) {
        return player == null ? null : address(player.getUniqueID());
    }

    /**
     * 按用途 {@code purpose} 给玩家的临时地址放行 {@code hosts}（替换这个用途原来的那组），{@code ttlMillis} 毫秒后过期。
     *
     * @return 收下了返回 true；玩家没有临时地址、主机超过 64 台、有效期不是正数时返回 false
     */
    public static boolean allow(UUID player, String purpose, Collection<Ipv6Address> hosts, long ttlMillis) {
        checkPurpose(purpose);
        NetworkBackend b = NetworkHooks.backend();
        return b != null && player != null && b.allowPlayer(player, purpose, hosts, ttlMillis);
    }

    public static boolean allow(EntityPlayer player, String purpose, Collection<Ipv6Address> hosts, long ttlMillis) {
        return player != null && allow(player.getUniqueID(), purpose, hosts, ttlMillis);
    }

    /** 撤销一个用途的放行（没有时什么也不做） */
    public static void revoke(UUID player, String purpose) {
        checkPurpose(purpose);
        NetworkBackend b = NetworkHooks.backend();
        if (b != null && player != null) {
            b.revokePlayer(player, purpose);
        }
    }

    public static void revoke(EntityPlayer player, String purpose) {
        if (player != null) {
            revoke(player.getUniqueID(), purpose);
        }
    }

    private static void checkPurpose(String purpose) {
        if (purpose == null || !NetworkRegistry.KEY.matcher(purpose).matches()) {
            throw new IllegalArgumentException("用途要写成“模组 ID:名字”（小写字母、数字、_ . - /）：" + purpose);
        }
    }
}
