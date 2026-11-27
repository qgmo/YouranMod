package moe.qingu.youran.server.net;

import moe.qingu.youran.api.network.Ipv6Address;
import moe.qingu.youran.api.network.NetworkInterface;
import moe.qingu.youran.api.network.packet.Ipv6Packet;
import moe.qingu.youran.api.network.stack.EventLoop;
import moe.qingu.youran.network.NetworkHooks;
import moe.qingu.youran.network.NicAccess;
import moe.qingu.youran.network.NicPort;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * 插着的网卡表（服主 11-27，取代原来的设备表）：坐标层每一格（0 号地址）最多插一张网卡，按格子、按地址查。线程安全，任意线程插拔，
 * 网络线程上路由器按地址查、交包。网卡不带通电、操作者、能力：收不收由网卡自己的 {@link NetworkInterface#receive} 说了算，
 * 网络不跟区块加载（方块加载时插、卸载时拔由模组自己做）。
 * <p>
 * 网卡发的包经它的端口（{@link Port#send}）校验：不是合格的 IPv6 包、超过 1280 字节、源地址不是这张网卡的地址、超过速率上限、
 * 已经拔掉时不收（返回 false），记限频日志；收下的排进网络线程交给它那个维度的路由器（{@link Ingress}）。
 * 发给网卡的包由维度路由器按目的地址查这张表交过来（{@link #deliver}）：网络不从地址解码坐标（服主 11-27）。
 */
final class NicTable {
    /** 一格的地址怎么算（{@link AddressBook#coordinateAddress}）；没有地址为 null */
    interface Addresser {
        Ipv6Address addressAt(int dimension, int x, int y, int z);
    }

    /** 网卡发出的包交给谁（网络线程上调用；一般是那张网卡所在维度的路由器） */
    interface Ingress {
        void fromNic(Port port, byte[] packet);
    }

    private final Addresser addresses;
    private final EventLoop loop;
    private final RejectLog rejects;
    private final Ingress router;
    private final int packetsPerSecond;
    private final int bytesPerSecond;
    private final Map<PosKey, Port> byPosition = new ConcurrentHashMap<>();
    private final Map<Ipv6Address, Port> byAddress = new ConcurrentHashMap<>();
    /** 拔掉以后要做的事（ClientRelayNic 断开等） */
    private final Consumer<Port> onUnplug;
    volatile long accepted;
    volatile long delivered;
    volatile boolean stopped;

    /**
     * @param router  收下的包交给谁（网络线程上调用，一般是那个维度的路由器）
     * @param onUnplug 拔掉一张网卡以后（任意线程）
     */
    NicTable(Addresser addresses, EventLoop loop, RejectLog rejects, Ingress router, int packetsPerSecond, int bytesPerSecond,
             Consumer<Port> onUnplug) {
        this.addresses = addresses;
        this.loop = loop;
        this.rejects = rejects;
        this.router = router;
        this.packetsPerSecond = packetsPerSecond;
        this.bytesPerSecond = bytesPerSecond;
        this.onUnplug = onUnplug;
    }

    // ---- 插拔（任意线程） ----

    /** 插到这一格；没有地址、这一格已经插着、网络已停时返回 null（不顶掉旧的） */
    Port plug(NetworkInterface nic, int dimension, int x, int y, int z) {
        if (stopped || nic == null) {
            return null;
        }
        Ipv6Address a = addresses.addressAt(dimension, x, y, z);
        if (a == null) {
            return null;
        }
        PosKey key = new PosKey(dimension, x, y, z);
        Port p = new Port(nic, key, a);
        if (byPosition.putIfAbsent(key, p) != null) {
            return null;
        }
        byAddress.put(a, p);
        if (stopped) {
            unplug(p);
            return null;
        }
        return p;
    }

    /** 拔掉（网卡上的端口由调用方拿下）；已经拔掉的不管 */
    void unplug(NicPort port) {
        if (!(port instanceof Port)) {
            return;
        }
        Port p = (Port) port;
        if (p.table() != this || p.removed) {
            return;
        }
        p.removed = true;
        byPosition.remove(p.key, p);
        byAddress.remove(p.address, p);
        onUnplug.accept(p);
    }

    /** 关服：全部拔掉，网卡上的端口也拿下（模组下次开服后会重新插） */
    void clear() {
        stopped = true;
        NicAccess access = NetworkHooks.nicAccess();
        for (Port p : new ArrayList<>(byPosition.values())) {
            if (access != null) {
                access.detach(p.nic, p);
            }
            unplug(p);
        }
    }

    // ---- 查（任意线程） ----

    Ipv6Address addressAt(int dimension, int x, int y, int z) {
        return addresses.addressAt(dimension, x, y, z);
    }

    Port at(int dimension, int x, int y, int z) {
        return byPosition.get(new PosKey(dimension, x, y, z));
    }

    Port byAddress(Ipv6Address a) {
        return a == null ? null : byAddress.get(a);
    }

    Collection<Port> all() {
        return byPosition.values();
    }

    // ---- 网络线程 ----

    /**
     * 维度路由器把发给 {@code dst} 的包交过来（{@code packet} 跳数已减一、是副本）：插着地址是 {@code dst} 的网卡时交给它的
     * {@link NetworkInterface#receive}。没插着、网卡返回 false 或抛异常时返回 false（路由器回“地址不可达”）
     */
    boolean deliver(Ipv6Address dst, byte[] packet) {
        Port p = byAddress.get(dst);
        if (p == null || p.removed) {
            return false;
        }
        boolean ok;
        try {
            ok = p.nic.receive(packet);
        } catch (Throwable t) {
            NetworkServer.LOG.error("游戏内网络：{} 收包时出错（按收不了算）", p.label(), t);
            ok = false;
        }
        if (ok) {
            delivered++;
            p.received++;
        }
        return ok;
    }

    private void reject(String source, String reason) {
        loop.execute(() -> rejects.reject(source, reason, loop.nowMillis()));
    }

    /** 一张插着的网卡的端口（网卡拿到的 {@link NicPort}） */
    final class Port implements NicPort {
        final NetworkInterface nic;
        final PosKey key;
        final Ipv6Address address;
        private final RateLimiter limiter = new RateLimiter(packetsPerSecond, bytesPerSecond);
        volatile boolean removed;
        volatile long sent;
        volatile long received;

        Port(NetworkInterface nic, PosKey key, Ipv6Address address) {
            this.nic = nic;
            this.key = key;
            this.address = address;
        }

        NicTable table() {
            return NicTable.this;
        }

        @Override
        public Ipv6Address address() {
            return address;
        }

        @Override
        public boolean send(byte[] p) {
            String bad = check(p);
            if (bad != null) {
                reject(label(), bad);
                return false;
            }
            synchronized (this) {
                if (!limiter.tryAcquire(p.length, loop.nowMillis())) {
                    reject(label(), "超过速率上限（每秒 " + packetsPerSecond + " 个包、" + bytesPerSecond + " 字节）");
                    return false;
                }
            }
            sent++;
            accepted++;
            loop.execute(() -> router.fromNic(this, p));
            return true;
        }

        private String check(byte[] p) {
            if (removed || stopped) {
                return "网卡已经拔掉";
            }
            if (p == null || p.length < Ipv6Packet.HEADER || (p[0] & 0xF0) != 0x60) {
                return "不是 IPv6 包";
            }
            if (p.length > Ipv6Packet.MAX_PACKET) {
                return "包超过 1280 字节（" + p.length + " 字节）";
            }
            int plen = ((p[4] & 0xFF) << 8) | (p[5] & 0xFF);
            if (plen > p.length - Ipv6Packet.HEADER) {
                return "IPv6 首部的载荷长度超过实际长度";
            }
            Ipv6Address src = Ipv6Packet.sourceOf(p);
            if (!src.equals(address)) {
                return "源地址 " + src + " 不是网卡地址";
            }
            return null;
        }

        String label() {
            return "网卡 " + address + "（" + nic.getClass().getSimpleName() + "，维度 " + key.dimension + " " + key.x + ", " + key.y + ", " + key.z + "）";
        }
    }

    static final class PosKey {
        final int dimension;
        final int x;
        final int y;
        final int z;

        PosKey(int dimension, int x, int y, int z) {
            this.dimension = dimension;
            this.x = x;
            this.y = y;
            this.z = z;
        }

        @Override
        public boolean equals(Object o) {
            if (!(o instanceof PosKey)) {
                return false;
            }
            PosKey k = (PosKey) o;
            return k.dimension == dimension && k.x == x && k.y == y && k.z == z;
        }

        @Override
        public int hashCode() {
            return ((dimension * 31 + x) * 31 + y) * 31 + z;
        }

        @Override
        public String toString() {
            return "维度 " + dimension + " " + x + " " + y + " " + z;
        }
    }
}
