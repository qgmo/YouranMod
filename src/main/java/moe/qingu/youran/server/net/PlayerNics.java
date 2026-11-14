package moe.qingu.youran.server.net;

import moe.qingu.youran.api.network.Ipv6Address;
import moe.qingu.youran.api.network.NetworkLayout;
import moe.qingu.youran.api.network.packet.Icmpv6Message;
import moe.qingu.youran.api.network.packet.Ipv6Packet;
import moe.qingu.youran.api.network.stack.EventLoop;
import moe.qingu.youran.net.NicMessages;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 玩家临时地址（服主 11-14）：玩法 /64 里的最后一个 /96（{@link NetworkLayout#PLAYERS_DATACENTER}）。
 * 玩家上线时分一个随机的 /128（每次上线都重新随机，同一时刻不重复），下线收回，不进存档。
 * 协议栈跑在玩家自己的客户端上（同设备网卡的方案 A），这里是服务端路由器上的一道防火墙，规则只在服务端执行：
 * <ul>
 * <li>源地址必须是这个玩家自己的临时地址；</li>
 * <li>往外只许：递归解析器的 UDP 53（DNS），以及“放行的目标主机”的 TCP 80 / 443，且 TCP 只能由玩家这边发起（第一个段是 SYN）；</li>
 * <li>往里只许：属于玩家发起的连接 / 查询的回包，以及关于玩家自己发的包的 ICMPv6 差错。别人主动连入（SYN、ping）一律拒绝；</li>
 * <li>两个方向各有每秒包数、字节数上限（令牌桶）；同时最多 {@value #MAX_FLOWS} 条连接；</li>
 * <li>不合规则的包丢掉、回“目标不可达：管理上禁止”（ICMPv6 1/1），并记限频日志。伪造源地址、超频、坏包只丢不回（不给伪造的地址回包）。</li>
 * </ul>
 * “放行的目标主机”按用途登记（{@link #allow}，如家具电脑旁观 {@code cfm:spectate}），每个用途一组地址、有有效期；
 * 允许的主机是所有没过期用途的并集。撤销或过期后，到那些主机的已有连接也立刻断（之后的包都按规则丢）。
 * <p>
 * 线程：{@link #attach}、{@link #detach}、{@link #allow}、{@link #revoke} 可以在任意线程调用（排进网络线程做）；
 * 收发包的检查都在网络线程上，这里的表只在网络线程上改。
 */
final class PlayerNics {
    static final int MAX_FLOWS = 64;
    static final int MAX_PURPOSES = 16;
    static final int MAX_HOSTS_PER_PURPOSE = 64;
    static final long MAX_TTL_MILLIS = 3_600_000L;
    static final long TCP_IDLE_MILLIS = 120_000L;
    static final long TCP_CLOSING_MILLIS = 30_000L;
    static final long UDP_IDLE_MILLIS = 30_000L;
    static final int DNS_PORT = 53;

    private static final int PROTO_TCP = 6;
    private static final int PROTO_UDP = 17;
    private static final int PROTO_ICMPV6 = 58;
    private static final int TCP_FIN = 0x01;
    private static final int TCP_SYN = 0x02;
    private static final int TCP_RST = 0x04;
    private static final int TCP_ACK = 0x10;

    /** 到玩家客户端的连接（真实的是 {@code NicChannel}；单元测试换成假的） */
    interface Link {
        /** 网络线程：把包交给客户端 */
        void deliver(byte[] packet);

        /** 任意线程：控制消息（接上、收回） */
        void control(byte[] message);
    }

    private final EventLoop loop;
    private final NetworkLayout layout;
    private final Router router;
    private final RejectLog rejects;
    private final Ipv6Address resolver;
    private final Random random;
    private final int outPackets;
    private final int outBytes;
    private final int inPackets;
    private final int inBytes;
    private final AtomicInteger generations = new AtomicInteger();

    /** 任意线程读：玩家 → 临时地址 */
    private final Map<UUID, Ipv6Address> addresses = new ConcurrentHashMap<>();
    /** 已经分出去的主机号（分配时用 putIfAbsent 保证不重复；网络线程上拿掉网卡以后才放回） */
    private final Map<Long, UUID> parts = new ConcurrentHashMap<>();

    // ---- 只在网络线程上 ----
    private final Map<UUID, Nic> byPlayer = new HashMap<>();
    private final Map<Long, Nic> byPart = new HashMap<>();

    // 统计（网络线程上改，读的时候不加锁，只用来显示）
    volatile long outAccepted;
    volatile long outRejected;
    volatile long inDelivered;
    volatile long inRejected;

    PlayerNics(EventLoop loop, NetworkLayout layout, Router router, RejectLog rejects, Ipv6Address resolver, Random random,
               int outPacketsPerSecond, int outBytesPerSecond, int inPacketsPerSecond, int inBytesPerSecond) {
        this.loop = loop;
        this.layout = layout;
        this.router = router;
        this.rejects = rejects;
        this.resolver = resolver;
        this.random = random;
        this.outPackets = outPacketsPerSecond;
        this.outBytes = outBytesPerSecond;
        this.inPackets = inPacketsPerSecond;
        this.inBytes = inBytesPerSecond;
    }

    // ---- 分配与收回（任意线程） ----

    /** 玩家上线：分一个随机的临时地址，告诉客户端。已经有的先收回（重新随机） */
    Ipv6Address attach(UUID player, String name, Link link) {
        detach(player);
        long part;
        do {
            part = random.nextInt() & 0xFFFFFFFFL;
        } while (part == 0 || part == 0xFFFFFFFFL || parts.putIfAbsent(part, player) != null);
        Ipv6Address address = layout.playerAddress(part);
        int gen = generations.incrementAndGet();
        Nic n = new Nic(player, name, address, part, link, gen);
        addresses.put(player, address);
        loop.execute(() -> {
            Nic old = byPlayer.put(player, n);
            if (old != null) {
                closeInLoop(old);
            }
            byPart.put(n.part, n);
        });
        link.control(NicMessages.playerAttach(gen, address, resolver));
        return address;
    }

    /** 玩家下线（或网络停）：收回地址。客户端还连着时告诉它 */
    void detach(UUID player) {
        Ipv6Address a = addresses.remove(player);
        if (a == null) {
            return;
        }
        long part = NetworkLayout.hostPartOf(a);
        loop.execute(() -> {
            Nic n = byPart.get(part);
            if (n != null && n.player.equals(player)) {
                byPart.remove(part);
                if (byPlayer.get(player) == n) {
                    byPlayer.remove(player);
                }
                closeInLoop(n);
                n.link.control(NicMessages.playerDetach(n.generation));
            }
            parts.remove(part, player);
        });
    }

    private void closeInLoop(Nic n) {
        n.closed = true;
        n.flows.clear();
        n.grants.clear();
        byPart.remove(n.part, n);
        parts.remove(n.part, n.player);
    }

    /** 关服：全部收回（不发消息，连接都要断了） */
    void clear() {
        addresses.clear();
        loop.execute(() -> {
            byPlayer.clear();
            byPart.clear();
            parts.clear();
        });
    }

    Ipv6Address address(UUID player) {
        return addresses.get(player);
    }

    int online() {
        return addresses.size();
    }

    // ---- 放行（任意线程） ----

    /**
     * 给玩家的临时地址按用途放行一组目标主机（替换这个用途原来的那组），{@code ttlMillis} 毫秒后过期（最长 1 小时）。
     * 只收这个 /48 里、不是玩家临时地址的主机；超过 {@value #MAX_HOSTS_PER_PURPOSE} 个、玩家没有临时地址时返回 false。
     */
    boolean allow(UUID player, String purpose, Collection<Ipv6Address> hosts, long ttlMillis) {
        if (purpose == null || hosts == null || ttlMillis <= 0 || hosts.size() > MAX_HOSTS_PER_PURPOSE || !addresses.containsKey(player)) {
            return false;
        }
        Set<Ipv6Address> set = new HashSet<>();
        for (Ipv6Address h : hosts) {
            if (h != null && layout.contains(h) && !layout.isPlayerAddress(h)) {
                set.add(h);
            }
        }
        Set<Ipv6Address> fixed = Collections.unmodifiableSet(set);
        long ttl = Math.min(ttlMillis, MAX_TTL_MILLIS);
        loop.execute(() -> {
            Nic n = byPlayer.get(player);
            if (n == null || n.closed) {
                return;
            }
            n.pruneGrants(loop.nowMillis());
            if (!n.grants.containsKey(purpose) && n.grants.size() >= MAX_PURPOSES) {
                rejects.reject(n.label(), "放行的用途超过 " + MAX_PURPOSES + " 个，" + purpose + " 不登记", loop.nowMillis());
                return;
            }
            n.grants.put(purpose, new Grant(fixed, loop.nowMillis() + ttl));
        });
        return true;
    }

    void revoke(UUID player, String purpose) {
        loop.execute(() -> {
            Nic n = byPlayer.get(player);
            if (n != null) {
                n.grants.remove(purpose);
            }
        });
    }

    // ---- 往外（网络线程）：玩家客户端发来的包 ----

    void fromPlayer(UUID player, String name, byte[] p) {
        long now = loop.nowMillis();
        Nic n = byPlayer.get(player);
        String source = "玩家 " + name + " 的临时地址";
        if (n == null || n.closed) {
            reject(source, "没有临时地址", now);
            return;
        }
        String bad = basicCheck(p);
        if (bad != null) {
            reject(source, bad, now);
            return;
        }
        Ipv6Address src = Ipv6Packet.sourceOf(p);
        if (!src.equals(n.address)) {
            reject(source, "源地址 " + src + " 不是他的临时地址 " + n.address, now);
            return;
        }
        if (!n.out.tryAcquire(p.length, now)) {
            reject(source, "超过速率上限（每秒 " + outPackets + " 个包、" + outBytes + " 字节）", now);
            return;
        }
        Verdict v = outbound(n, p, now);
        if (v != Verdict.OK) {
            if (v.reason != null) {
                reject(source, v.reason, now);
            }
            if (v.reply) {
                router.unreachable(p, Icmpv6Message.UNREACH_PROHIBITED);
            }
            return;
        }
        outAccepted++;
        router.forward(p);
    }

    private Verdict outbound(Nic n, byte[] p, long now) {
        int nh = p[6] & 0xFF;
        int plen = payloadLength(p);
        Ipv6Address dst = Ipv6Packet.destinationOf(p);
        if (nh == PROTO_UDP) {
            if (plen < 8) {
                return Verdict.reject("UDP 包太短");
            }
            int sport = u16(p, 40);
            int dport = u16(p, 42);
            if (!dst.equals(resolver) || dport != DNS_PORT) {
                return Verdict.reject("只能向递归解析器的 UDP 53 发 DNS 查询（目标 [" + dst + "]:" + dport + "）");
            }
            FlowKey k = new FlowKey(PROTO_UDP, sport, dst, dport);
            Flow f = n.flow(k, now);
            if (f == null) {
                if (!n.hasRoom(now)) {
                    return Verdict.reject("同时进行的连接超过 " + MAX_FLOWS + " 条");
                }
                f = new Flow(false);
                n.flows.put(k, f);
            }
            f.lastSeen = now;
            return Verdict.OK;
        }
        if (nh == PROTO_TCP) {
            if (plen < 20) {
                return Verdict.reject("TCP 段太短");
            }
            int sport = u16(p, 40);
            int dport = u16(p, 42);
            int flags = p[40 + 13] & 0xFF;
            if (dport != 80 && dport != 443) {
                return Verdict.reject("只能连 TCP 80 / 443（目标 [" + dst + "]:" + dport + "）");
            }
            FlowKey k = new FlowKey(PROTO_TCP, sport, dst, dport);
            if (!n.allowed(dst, now)) {
                n.flows.remove(k);
                return Verdict.reject("目标 " + dst + " 不在放行的主机里");
            }
            Flow f = n.flow(k, now);
            if (f == null) {
                if ((flags & TCP_RST) != 0) {
                    // 已经没有的连接上的复位：正常现象（比如客户端超时放弃），只丢不记
                    return Verdict.SILENT;
                }
                if ((flags & TCP_SYN) == 0 || (flags & TCP_ACK) != 0) {
                    return Verdict.reject("TCP 只能由玩家这边发起（没有这条连接，标志 0x" + Integer.toHexString(flags) + "）");
                }
                if (!n.hasRoom(now)) {
                    return Verdict.reject("同时进行的连接超过 " + MAX_FLOWS + " 条");
                }
                f = new Flow(true);
                n.flows.put(k, f);
            }
            f.update(flags, true, now);
            if (f.reset) {
                n.flows.remove(k);
            }
            return Verdict.OK;
        }
        if (nh == PROTO_ICMPV6) {
            if (plen >= 1 && (p[40] & 0xFF) < 128) {
                // 客户端协议栈回的差错（如端口不可达）：不往外发，也不回差错
                return Verdict.SILENT;
            }
            return Verdict.reject("不许发 ICMPv6 信息报文（类型 " + (plen >= 1 ? p[40] & 0xFF : -1) + "）");
        }
        return Verdict.reject("不许的协议（下一首部 " + nh + "）");
    }

    // ---- 往里（网络线程）：路由器把目的地址在玩家 /96 里的包交过来 ----

    void deliver(byte[] p) {
        long now = loop.nowMillis();
        Ipv6Address dst = Ipv6Packet.destinationOf(p);
        Nic n = byPart.get(NetworkLayout.hostPartOf(dst));
        if (n == null || n.closed || !n.address.equals(dst)) {
            router.unreachable(p, Icmpv6Message.UNREACH_ADDRESS);
            return;
        }
        Verdict v = inbound(n, p, now);
        if (v != Verdict.OK) {
            inRejected++;
            if (v.reason != null) {
                rejects.reject("发给" + n.label() + "的包（来自 " + Ipv6Packet.sourceOf(p) + "）", v.reason, now);
            }
            if (v.reply) {
                router.unreachable(p, Icmpv6Message.UNREACH_PROHIBITED);
            }
            return;
        }
        if (!n.in.tryAcquire(p.length, now)) {
            inRejected++;
            rejects.reject("发给" + n.label() + "的包", "超过速率上限（每秒 " + inPackets + " 个包、" + inBytes + " 字节）", now);
            return;
        }
        inDelivered++;
        n.link.deliver(p);
    }

    private Verdict inbound(Nic n, byte[] p, long now) {
        int nh = p[6] & 0xFF;
        int plen = payloadLength(p);
        Ipv6Address src = Ipv6Packet.sourceOf(p);
        if (nh == PROTO_UDP) {
            if (plen < 8) {
                return Verdict.logOnly("UDP 包太短");
            }
            int sport = u16(p, 40);
            int dport = u16(p, 42);
            Flow f = src.equals(resolver) && sport == DNS_PORT ? n.flow(new FlowKey(PROTO_UDP, dport, src, sport), now) : null;
            if (f == null) {
                return Verdict.reject("不接受主动发来的 UDP（只收递归解析器对他的查询的回答）");
            }
            f.lastSeen = now;
            return Verdict.OK;
        }
        if (nh == PROTO_TCP) {
            if (plen < 20) {
                return Verdict.logOnly("TCP 段太短");
            }
            int sport = u16(p, 40);
            int dport = u16(p, 42);
            int flags = p[40 + 13] & 0xFF;
            FlowKey k = new FlowKey(PROTO_TCP, dport, src, sport);
            Flow f = n.flow(k, now);
            if ((flags & TCP_SYN) != 0 && (flags & TCP_ACK) == 0) {
                return Verdict.reject("不接受主动连入（TCP SYN 到端口 " + dport + "）");
            }
            if (f == null) {
                return (flags & TCP_RST) != 0 ? Verdict.SILENT : Verdict.reject("不接受主动连入（没有这条连接，端口 " + dport + "）");
            }
            if (!n.allowed(src, now)) {
                n.flows.remove(k);
                return Verdict.reject("来源 " + src + " 已经不在放行的主机里");
            }
            f.update(flags, false, now);
            if (f.reset) {
                n.flows.remove(k);
            }
            return Verdict.OK;
        }
        if (nh == PROTO_ICMPV6) {
            if (plen < 8) {
                return Verdict.SILENT;
            }
            int type = p[40] & 0xFF;
            if (type < 128) {
                // 差错报文：只收关于他自己发的包的（里面附带的原始包的源地址是他）
                if (plen >= 8 + Ipv6Packet.HEADER && Ipv6Address.read(p, 40 + 8 + 8).equals(n.address)) {
                    return Verdict.OK;
                }
                return Verdict.SILENT;
            }
            return Verdict.reject("不接受主动发来的 ICMPv6（类型 " + type + "）");
        }
        return Verdict.reject("不许的协议（下一首部 " + nh + "）");
    }

    // ---- 工具 ----

    private static String basicCheck(byte[] p) {
        if (p.length > Ipv6Packet.MAX_PACKET) {
            return "包超过 1280 字节（" + p.length + " 字节）";
        }
        if (p.length < Ipv6Packet.HEADER || (p[0] & 0xF0) != 0x60) {
            return "不是 IPv6 包";
        }
        if (payloadLength(p) > p.length - Ipv6Packet.HEADER) {
            return "IPv6 首部的载荷长度超过实际长度";
        }
        return null;
    }

    private static int payloadLength(byte[] p) {
        return ((p[4] & 0xFF) << 8) | (p[5] & 0xFF);
    }

    private static int u16(byte[] b, int i) {
        return ((b[i] & 0xFF) << 8) | (b[i + 1] & 0xFF);
    }

    private void reject(String source, String reason, long now) {
        outRejected++;
        rejects.reject(source, reason, now);
    }

    /** 给 /ynet 看：每个在线玩家的地址、连接数、放行的用途（网络线程上调用） */
    List<String> describe() {
        List<String> out = new ArrayList<>();
        long now = loop.nowMillis();
        for (Nic n : byPlayer.values()) {
            n.pruneGrants(now);
            StringBuilder sb = new StringBuilder();
            sb.append(n.name).append(' ').append(n.address).append("  连接 ").append(n.flows.size());
            for (Map.Entry<String, Grant> e : n.grants.entrySet()) {
                sb.append("  ").append(e.getKey()).append('=').append(e.getValue().hosts.size()).append(" 台主机（")
                        .append((e.getValue().expiresAt - now) / 1000).append(" 秒后过期）");
            }
            out.add(sb.toString());
        }
        return out;
    }

    /** 测试用：玩家现在放行的主机（网络线程上调用） */
    Set<Ipv6Address> allowedHosts(UUID player) {
        Nic n = byPlayer.get(player);
        Set<Ipv6Address> out = new HashSet<>();
        if (n != null) {
            n.pruneGrants(loop.nowMillis());
            for (Grant g : n.grants.values()) {
                out.addAll(g.hosts);
            }
        }
        return out;
    }

    /** 测试用：玩家现在的连接数 */
    int flowCount(UUID player) {
        Nic n = byPlayer.get(player);
        return n == null ? 0 : n.flows.size();
    }

    private static final class Verdict {
        static final Verdict OK = new Verdict(null, false);
        /** 只丢，不记、不回 */
        static final Verdict SILENT = new Verdict(null, false);
        final String reason;
        final boolean reply;

        private Verdict(String reason, boolean reply) {
            this.reason = reason;
            this.reply = reply;
        }

        /** 丢掉、记日志、回“管理上禁止” */
        static Verdict reject(String reason) {
            return new Verdict(reason, true);
        }

        /** 丢掉、记日志，不回 */
        static Verdict logOnly(String reason) {
            return new Verdict(reason, false);
        }
    }

    private static final class Grant {
        final Set<Ipv6Address> hosts;
        final long expiresAt;

        Grant(Set<Ipv6Address> hosts, long expiresAt) {
            this.hosts = hosts;
            this.expiresAt = expiresAt;
        }
    }

    private static final class FlowKey {
        final int protocol;
        final int localPort;
        final Ipv6Address remote;
        final int remotePort;

        FlowKey(int protocol, int localPort, Ipv6Address remote, int remotePort) {
            this.protocol = protocol;
            this.localPort = localPort;
            this.remote = remote;
            this.remotePort = remotePort;
        }

        @Override
        public boolean equals(Object o) {
            if (!(o instanceof FlowKey)) {
                return false;
            }
            FlowKey k = (FlowKey) o;
            return k.protocol == protocol && k.localPort == localPort && k.remotePort == remotePort && k.remote.equals(remote);
        }

        @Override
        public int hashCode() {
            return ((protocol * 31 + localPort) * 31 + remotePort) * 31 + remote.hashCode();
        }
    }

    private static final class Flow {
        final boolean tcp;
        long lastSeen;
        boolean finOut;
        boolean finIn;
        boolean reset;

        Flow(boolean tcp) {
            this.tcp = tcp;
        }

        void update(int flags, boolean outbound, long now) {
            lastSeen = now;
            if ((flags & TCP_RST) != 0) {
                reset = true;
            }
            if ((flags & TCP_FIN) != 0) {
                if (outbound) {
                    finOut = true;
                } else {
                    finIn = true;
                }
            }
        }

        boolean expired(long now) {
            long idle = !tcp ? UDP_IDLE_MILLIS : (finOut && finIn) ? TCP_CLOSING_MILLIS : TCP_IDLE_MILLIS;
            return reset || now - lastSeen > idle;
        }
    }

    private final class Nic {
        final UUID player;
        final String name;
        final Ipv6Address address;
        final long part;
        final Link link;
        final int generation;
        final RateLimiter out = new RateLimiter(outPackets, outBytes);
        final RateLimiter in = new RateLimiter(inPackets, inBytes);
        final Map<String, Grant> grants = new LinkedHashMap<>();
        final Map<FlowKey, Flow> flows = new HashMap<>();
        boolean closed;

        Nic(UUID player, String name, Ipv6Address address, long part, Link link, int generation) {
            this.player = player;
            this.name = name;
            this.address = address;
            this.part = part;
            this.link = link;
            this.generation = generation;
        }

        String label() {
            return "玩家 " + name + " 的临时地址 " + address;
        }

        /** 没过期的连接；过期的顺手删掉 */
        Flow flow(FlowKey k, long now) {
            Flow f = flows.get(k);
            if (f != null && f.expired(now)) {
                flows.remove(k);
                return null;
            }
            return f;
        }

        boolean hasRoom(long now) {
            if (flows.size() < MAX_FLOWS) {
                return true;
            }
            flows.values().removeIf(f -> f.expired(now));
            return flows.size() < MAX_FLOWS;
        }

        void pruneGrants(long now) {
            Iterator<Grant> it = grants.values().iterator();
            while (it.hasNext()) {
                if (it.next().expiresAt <= now) {
                    it.remove();
                }
            }
        }

        boolean allowed(Ipv6Address host, long now) {
            boolean found = false;
            Iterator<Grant> it = grants.values().iterator();
            while (it.hasNext()) {
                Grant g = it.next();
                if (g.expiresAt <= now) {
                    it.remove();
                } else if (g.hosts.contains(host)) {
                    found = true;
                }
            }
            return found;
        }
    }
}
