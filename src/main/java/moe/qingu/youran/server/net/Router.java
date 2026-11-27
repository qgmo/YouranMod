package moe.qingu.youran.server.net;

import moe.qingu.youran.api.network.Ipv6Address;
import moe.qingu.youran.api.network.packet.Icmpv6Message;
import moe.qingu.youran.api.network.packet.Ipv6Packet;
import moe.qingu.youran.api.network.packet.MalformedPacketException;
import moe.qingu.youran.api.network.packet.UdpDatagram;
import moe.qingu.youran.api.network.stack.EventLoop;
import moe.qingu.youran.api.network.stack.NeighborDiscovery;
import moe.qingu.youran.api.network.stack.NetStack;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

/**
 * 一台路由器（服主 11-27：每层都是真的路由器，共用这一种）。网络线程上跑。每台有：
 * <ul>
 * <li>自己的地址（{@link #address}，相当于真实网络里路由器的 loopback 地址）：发 ICMPv6 差错、回 ping 都用它；发给它的包交给它自己的协议栈
 * （{@link #local}，控制面：回显、端口不可达等）；</li>
 * <li>网口：接链路的网口做邻居发现（{@link LinkPort}：路由器之间的点对点链路、机房的局域网、玩家的链路）；
 * 维度路由器往坐标层网卡交包不做邻居发现（{@link Action#NICS}）；</li>
 * <li>自己的静态路由表（{@link #addRoute}，最长前缀匹配；不跑路由协议）。</li>
 * </ul>
 * 转发：坏包丢掉；目的地址是链路本地、链路范围组播的交给收到它的网口自己处理（邻居发现、DHCPv6、回显），从不转发；源地址是链路本地的
 * 不转发（回“超出源地址的范围”，RFC 4443 第 3.1 节代码 2）；发给自己的交给控制面；跳数不到 2 的回“超时”；按路由表找出口、跳数减一，
 * 到不了的回“目标不可达”（没有路由 0、地址不可达 3）。差错报文用自己的地址发，按 RFC 4443 第 2.4 节 f 限速。
 * <p>
 * 测试开关（{@link Simulation}）：收包时按概率丢掉或推迟 20–80 毫秒。所有路由器共用一个开关。
 */
final class Router {
    final String name;
    final Ipv6Address address;
    private final EventLoop loop;
    private final Random random;
    /** 控制面：发给路由器自己的地址的包（回显、端口不可达……），以及从这台路由器发出的 ping */
    final NetStack local;
    private final List<Route> routes = new ArrayList<>();
    final List<Port> ports = new ArrayList<>();
    private final Set<Ipv6Address> own = new HashSet<>();
    private final RateLimiter errorLimiter = new RateLimiter(200, 200 * Ipv6Packet.MAX_PACKET);
    private Simulation simulation;
    private Uplink uplink;
    private NicTable nics;

    // 统计（网络线程上改，读的时候不加锁，只用来显示）
    volatile long forwarded;
    volatile long received;
    volatile long unreachableSent;
    volatile long timeExceededSent;
    volatile long malformed;

    Router(String name, Ipv6Address address, EventLoop loop, Random random) {
        this.name = name;
        this.address = address;
        this.loop = loop;
        this.random = random;
        this.local = new NetStack(loop, p -> loop.execute(() -> originate(p)), random);
        local.addAddress(address);
        own.add(address);
    }

    @Override
    public String toString() {
        return name + "（" + address + "）";
    }

    void setSimulation(Simulation s) {
        simulation = s;
    }

    void setUplink(Uplink u) {
        uplink = u;
    }

    Uplink uplink() {
        return uplink;
    }

    void setNics(NicTable t) {
        nics = t;
    }

    boolean owns(Ipv6Address a) {
        return own.contains(a);
    }

    EventLoop loop() {
        return loop;
    }

    // ---- 路由表 ----

    enum Action {
        /** 交给某个网口上的下一跳（邻居的链路本地地址） */
        VIA,
        /** 在某个网口的链路上：目的地址就是下一跳 */
        ON_LINK,
        /** 维度路由器：交给插着的那张网卡（按地址查插着的网卡表，不从地址解码坐标） */
        NICS,
        /** 交给上行口（/48 以外） */
        UPLINK,
        /** 回“目标不可达” */
        REJECT
    }

    static final class Route {
        final Ipv6Address prefix;
        final int length;
        final Action action;
        final LinkPort port;
        final Ipv6Address nextHop;
        final int code;

        Route(Ipv6Address prefix, int length, Action action, LinkPort port, Ipv6Address nextHop, int code) {
            this.prefix = prefix;
            this.length = length;
            this.action = action;
            this.port = port;
            this.nextHop = nextHop;
            this.code = code;
        }

        boolean matches(Ipv6Address a) {
            return a.inPrefix(prefix, length);
        }

        @Override
        public String toString() {
            String where;
            switch (action) {
                case VIA:
                    where = "经 " + port.name + " 交给 " + nextHop;
                    break;
                case ON_LINK:
                    where = "在 " + port.name + " 的链路上";
                    break;
                case NICS:
                    where = "插着的网卡";
                    break;
                case UPLINK:
                    where = "上行口";
                    break;
                default:
                    where = "不可达（代码 " + code + "）";
            }
            return prefix + "/" + length + " → " + where;
        }
    }

    /** 加一条静态路由（同一个前缀已有时换掉） */
    void addRoute(Ipv6Address prefix, int length, Action action, LinkPort port, Ipv6Address nextHop, int code) {
        removeRoute(prefix, length);
        Route r = new Route(prefix, length, action, port, nextHop, code);
        int i = 0;
        while (i < routes.size() && routes.get(i).length >= length) {
            i++;
        }
        routes.add(i, r);
    }

    void via(Ipv6Address prefix, int length, LinkPort port, Ipv6Address nextHop) {
        addRoute(prefix, length, Action.VIA, port, nextHop, 0);
    }

    void onLink(Ipv6Address prefix, int length, LinkPort port) {
        addRoute(prefix, length, Action.ON_LINK, port, null, 0);
    }

    void reject(Ipv6Address prefix, int length, int code) {
        addRoute(prefix, length, Action.REJECT, null, null, code);
    }

    void removeRoute(Ipv6Address prefix, int length) {
        for (int i = routes.size() - 1; i >= 0; i--) {
            Route r = routes.get(i);
            if (r.length == length && r.prefix.inPrefix(prefix, length) && prefix.inPrefix(r.prefix, length)) {
                routes.remove(i);
            }
        }
    }

    Route lookup(Ipv6Address dst) {
        for (Route r : routes) {
            if (r.matches(dst)) {
                return r;
            }
        }
        return null;
    }

    List<Route> routes() {
        return Collections.unmodifiableList(new ArrayList<>(routes));
    }

    // ---- 收包 ----

    /** 网口收到一个包（网络线程上）；{@code in} 为 null 时是坐标层网卡、上行口这类不做邻居发现的入口 */
    void receive(LinkPort in, byte[] raw) {
        if (raw.length < Ipv6Packet.HEADER || raw.length > Ipv6Packet.MAX_PACKET || (raw[0] & 0xF0) != 0x60
                || ((raw[4] & 0xFF) << 8 | (raw[5] & 0xFF)) > raw.length - Ipv6Packet.HEADER) {
            malformed++;
            return;
        }
        received++;
        Simulation s = simulation;
        if (s != null && s.active()) {
            if (s.drop()) {
                return;
            }
            long d = s.delay();
            if (d > 0) {
                loop.schedule(() -> process(in, raw), d);
                return;
            }
        }
        process(in, raw);
    }

    private void process(LinkPort in, byte[] raw) {
        Ipv6Address dst = Ipv6Packet.destinationOf(raw);
        Ipv6Address src = Ipv6Packet.sourceOf(raw);
        if (dst.isLinkScoped() || (in != null && isNdp(raw))) {
            // 链路范围的、邻居发现报文（也可能发给网口上的全局地址，如机房路由器的 ::1）：网口自己处理，从不转发
            if (in != null) {
                in.linkScoped(raw);
            }
            return;
        }
        if (dst.isMulticast()) {
            // 范围更大的组播：这张网不做组播路由
            return;
        }
        if (src.isLinkLocal() || src.isUnspecified()) {
            if (src.isLinkLocal() && !own.contains(dst) && in != null) {
                in.beyondScope(raw);
            }
            return;
        }
        if (own.contains(dst)) {
            local.receive(raw);
            return;
        }
        int hop = raw[7] & 0xFF;
        if (hop <= 1) {
            sendError(raw, Icmpv6Message.TIME_EXCEEDED, 0);
            timeExceededSent++;
            return;
        }
        byte[] p = raw.clone();
        p[7] = (byte) (hop - 1);
        forwarded++;
        dispatch(p, dst);
    }

    /** 是不是邻居发现报文（ICMPv6 类型 133–137；不看扩展首部，这张网不用） */
    static boolean isNdp(byte[] raw) {
        return raw.length > Ipv6Packet.HEADER && (raw[6] & 0xFF) == Ipv6Packet.PROTO_ICMPV6
                && moe.qingu.youran.api.network.packet.NdpMessage.isNdpType(raw[Ipv6Packet.HEADER] & 0xFF);
    }

    /** 这台路由器自己发出的包（控制面、差错）：照路由表发，不减跳数 */
    void originate(byte[] raw) {
        if (raw.length < Ipv6Packet.HEADER) {
            return;
        }
        Ipv6Address dst = Ipv6Packet.destinationOf(raw);
        if (own.contains(dst)) {
            local.receive(raw);
            return;
        }
        if (dst.isLinkScoped() || dst.isMulticast()) {
            return;
        }
        dispatch(raw, dst);
    }

    private void dispatch(byte[] p, Ipv6Address dst) {
        Route r = lookup(dst);
        if (r == null) {
            unreachable(p, Icmpv6Message.UNREACH_NO_ROUTE);
            return;
        }
        switch (r.action) {
            case VIA:
                r.port.output(p, r.nextHop);
                return;
            case ON_LINK:
                r.port.output(p, dst);
                return;
            case NICS:
                if (nics == null || !nics.deliver(dst, p)) {
                    unreachable(p, Icmpv6Message.UNREACH_ADDRESS);
                }
                return;
            case UPLINK:
                if (uplink == null) {
                    unreachable(p, Icmpv6Message.UNREACH_NO_ROUTE);
                } else {
                    uplink.send(p);
                }
                return;
            default:
                unreachable(p, r.code);
        }
    }

    // ---- 差错 ----

    void unreachable(byte[] raw, int code) {
        unreachableSent++;
        sendError(raw, Icmpv6Message.DEST_UNREACHABLE, code);
    }

    /** 用自己的地址回一个 ICMPv6 差错（排进队列再发，不在这里递归） */
    void sendError(byte[] raw, int type, int code) {
        if (!Icmpv6Message.mayReplyWithError(raw) || !errorLimiter.tryAcquire(Ipv6Packet.MAX_PACKET, loop.nowMillis())) {
            return;
        }
        byte[] err = Icmpv6Message.error(type, code, 0, raw).toPacket(address, Ipv6Packet.sourceOf(raw)).encode();
        loop.execute(() -> originate(err));
    }

    void close() {
        for (LinkPort p : new ArrayList<>(linkPorts())) {
            p.close();
        }
        local.close();
    }

    private List<LinkPort> linkPorts() {
        List<LinkPort> out = new ArrayList<>();
        for (Port p : ports) {
            if (p instanceof LinkPort) {
                out.add((LinkPort) p);
            }
        }
        return out;
    }

    // ---- 网口 ----

    /** 路由器的一个网口 */
    static class Port {
        final Router router;
        final String name;

        Port(Router router, String name) {
            this.router = router;
            this.name = name;
        }

        @Override
        public String toString() {
            return name;
        }
    }

    /**
     * 接着一段链路、做邻居发现的网口（路由器一侧）：链路本地地址先做重复地址检测；回 NS、回 RS、按 {@link #advertise} 发 RA。
     * 收到的帧交给 {@link #fromLink}；子类可以在进出两个方向上加过滤（{@link #ingress}、{@link #egress}，玩家链路的防火墙），
     * 可以处理发给网口自己的 UDP（{@link #onLinkUdp}，玩家链路上的 DHCPv6 服务器）。
     */
    static class LinkPort extends Port {
        final NeighborDiscovery nd;
        final Ipv6Address linkLocal;
        private final Set<Ipv6Address> globals = new HashSet<>();
        volatile boolean closed;

        LinkPort(Router router, String name, NeighborDiscovery.Link link, long ownLinkAddress, Ipv6Address linkLocal) {
            super(router, name);
            this.linkLocal = linkLocal;
            this.nd = new NeighborDiscovery(router.loop, router.random, link, ownLinkAddress, true, new NeighborDiscovery.Events() {
                @Override
                public void resolutionFailed(byte[] packet) {
                    // RFC 4861 第 7.2.2 节：解析失败时给排着的每个包回“地址不可达”
                    router.unreachable(packet, Icmpv6Message.UNREACH_ADDRESS);
                }

                @Override
                public void addressDuplicated(Ipv6Address a) {
                    NetworkServer.LOG.error("游戏内网络：路由器 {} 的网口 {} 的地址 {} 重复（重复地址检测），没有启用", router.name, name, a);
                }
            });
            nd.addAddress(linkLocal);
            router.ports.add(this);
        }

        /** 网口上再配一个全局地址（YouranMod 机房路由器在局域网上的 ::1）：做重复地址检测、回 NS，发给它的包交给路由器的控制面 */
        void addGlobal(Ipv6Address a) {
            globals.add(a);
            router.own.add(a);
            nd.addAddress(a);
        }

        void advertise(NeighborDiscovery.Advertising a) {
            nd.advertise(a);
        }

        /** 链路上来的帧（网络线程上） */
        void fromLink(byte[] raw) {
            if (closed || !ingress(raw)) {
                return;
            }
            router.receive(this, raw);
        }

        /** 往里的过滤；返回 false 丢掉（原因由子类记） */
        boolean ingress(byte[] raw) {
            return true;
        }

        /** 往外（转发出去）的过滤；返回 false 丢掉 */
        boolean egress(byte[] raw) {
            return true;
        }

        /** 转发到链路上的 {@code nextHop} */
        void output(byte[] p, Ipv6Address nextHop) {
            if (closed || !egress(p)) {
                return;
            }
            nd.send(p, nextHop);
        }

        /** 发给链路范围地址的包：邻居发现、回显、子类的 UDP */
        void linkScoped(byte[] raw) {
            Ipv6Packet p;
            try {
                p = Ipv6Packet.parse(raw);
            } catch (MalformedPacketException e) {
                router.malformed++;
                return;
            }
            if (!nd.accepts(p.destination)) {
                return;
            }
            try {
                if (p.nextHeader == Ipv6Packet.PROTO_ICMPV6) {
                    Icmpv6Message m = Icmpv6Message.parse(p);
                    if (nd.handle(p, m)) {
                        return;
                    }
                    if (m.type == Icmpv6Message.ECHO_REQUEST && !p.source.isUnspecified()) {
                        Ipv6Address src = p.destination.isMulticast() ? nd.linkLocal() : p.destination;
                        if (src != null) {
                            nd.send(new Icmpv6Message(Icmpv6Message.ECHO_REPLY, 0, m.body).toPacket(src, p.source).encode(), p.source);
                        }
                    }
                    return;
                }
                if (p.nextHeader == Ipv6Packet.PROTO_UDP) {
                    onLinkUdp(p, UdpDatagram.parse(p));
                }
            } catch (MalformedPacketException e) {
                router.malformed++;
            }
        }

        /** 发给网口自己（链路范围）的 UDP；默认不管 */
        void onLinkUdp(Ipv6Packet p, UdpDatagram d) {
        }

        /** 从这个网口直接发一个包给链路上的 {@code to}（网口自己的报文，不过往外的过滤） */
        void sendOnLink(byte[] packet, Ipv6Address to) {
            if (!closed) {
                nd.send(packet, to);
            }
        }

        /** 源地址是链路本地的包要转发出去：回“超出源地址的范围”（从这个网口的链路本地地址回） */
        void beyondScope(byte[] raw) {
            Ipv6Address ll = nd.linkLocal();
            if (ll == null || !Icmpv6Message.mayReplyWithError(raw)
                    || !router.errorLimiter.tryAcquire(Ipv6Packet.MAX_PACKET, router.loop.nowMillis())) {
                return;
            }
            router.unreachableSent++;
            Ipv6Address to = Ipv6Packet.sourceOf(raw);
            nd.send(Icmpv6Message.error(Icmpv6Message.DEST_UNREACHABLE, 2, 0, raw).toPacket(ll, to).encode(), to);
        }

        void close() {
            closed = true;
            nd.close();
            for (Ipv6Address a : globals) {
                router.own.remove(a);
            }
            router.ports.remove(this);
        }
    }

    /**
     * 测试开关：按概率丢包、把包推迟 20–80 毫秒（造成乱序）。所有路由器共用一个（{@code /ynet sim}），每台路由器收包时判断。
     */
    static final class Simulation {
        private final Random random;
        private volatile double loss;
        private volatile double reorder;
        volatile long drops;
        volatile long delays;

        Simulation(Random random) {
            this.random = random;
        }

        void set(double lossRate, double reorderRate) {
            loss = Math.max(0, Math.min(1, lossRate));
            reorder = Math.max(0, Math.min(1, reorderRate));
        }

        double loss() {
            return loss;
        }

        double reorder() {
            return reorder;
        }

        boolean active() {
            return loss > 0 || reorder > 0;
        }

        boolean drop() {
            if (loss > 0 && random.nextDouble() < loss) {
                drops++;
                return true;
            }
            return false;
        }

        long delay() {
            if (reorder > 0 && random.nextDouble() < reorder) {
                delays++;
                return 20 + random.nextInt(61);
            }
            return 0;
        }
    }
}
