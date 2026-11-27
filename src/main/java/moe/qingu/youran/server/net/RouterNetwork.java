package moe.qingu.youran.server.net;

import moe.qingu.youran.api.network.Ipv6Address;
import moe.qingu.youran.api.network.NetworkLayout;
import moe.qingu.youran.api.network.YouranNetworkApi;
import moe.qingu.youran.api.network.packet.Icmpv6Message;
import moe.qingu.youran.api.network.packet.Ipv6Packet;
import moe.qingu.youran.api.network.packet.NdpMessage;
import moe.qingu.youran.api.network.stack.EventLoop;
import moe.qingu.youran.api.network.stack.NeighborDiscovery;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.function.Function;

/**
 * 服务器内网的路由器与链路（服主 11-27：每层都是真的路由器）。拓扑是一棵树，路由都是静态的：
 * <pre>
 *   维度路由器（每个联网维度一台，管这个维度的 /72，下面是插着的网卡）
 *        │ 点对点链路
 *   /48 核心路由器（往外是上行口）
 *        │ 点对点链路
 *   玩法路由器（玩法 /64）
 *        ├─ 机房路由器（每个机房一台，下面是机房的局域网 /96，主机都接在上面）
 *        └─ 玩家路由器（玩家 /96，每个在线玩家一条点对点链路，见 {@link PlayerNics}）
 * </pre>
 * 路由器之间的链路两头是 {@code fe80::1}（上面那台）与 {@code fe80::2}（下面那台），做 NS / NA；下一跳都写链路本地地址。
 * 每台路由器的全局地址在玩法 /64 里登记的“路由器地址段”（{@code youranmod:routers}，一个 /96，相当于 loopback 地址），
 * YouranMod 机房的路由器照旧用机房里的 {@code ::1}（{@code youranmod:router}）。
 * 机房路由器在局域网上发 RA：前缀（在链路上、不做 SLAAC）、MTU 1280、RDNSS（递归解析器），默认网关就是它。
 * <p>
 * 网络线程上建、上面跑。
 */
final class RouterNetwork {
    /** 路由器的登记名（主机登记在 {@link #ROUTERS_DATACENTER} 里） */
    static final String ROUTERS_DATACENTER = "youranmod:routers";
    static final String CORE = "youranmod:router/core";
    static final String GAMEPLAY = "youranmod:router/gameplay";
    static final String PLAYERS = "youranmod:router/players";
    static final Ipv6Address UPPER = Ipv6Address.linkLocal(1);
    static final Ipv6Address LOWER = Ipv6Address.linkLocal(2);
    /** RA 里的 RDNSS 寿命（RFC 8106 第 5.1 节：最长间隔到两倍之间） */
    static final long RDNSS_LIFETIME = 1200;
    /** 机房 RA 的路由器寿命（RFC 4861 的默认值：3 × 最长间隔） */
    static final int ROUTER_LIFETIME = 1800;

    static String dimensionKey(int dimension) {
        return "youranmod:router/dim/" + dimension;
    }

    static String datacenterKey(String datacenter) {
        return "youranmod:router/dc/" + datacenter.replace(':', '/');
    }

    /** 一个机房：路由器与局域网 */
    static final class Datacenter {
        final String key;
        final long index;
        final Router router;
        final Lan lan;
        final Router.LinkPort lanPort;

        Datacenter(String key, long index, Router router, Lan lan, Router.LinkPort lanPort) {
            this.key = key;
            this.index = index;
            this.router = router;
            this.lan = lan;
            this.lanPort = lanPort;
        }
    }

    final NetworkLayout layout;
    final Router core;
    final Router gameplay;
    final Router players;
    /** 维度 ID → 维度路由器 */
    final Map<Integer, Router> dimensions = new LinkedHashMap<>();
    /** 机房序号 → 机房 */
    final Map<Long, Datacenter> datacenters = new LinkedHashMap<>();
    final Router.Simulation simulation;
    private final List<Router> all = new ArrayList<>();
    private final EventLoop loop;
    private final Random random;

    /**
     * @param routerAddress 路由器的登记名 → 地址（{@link #CORE}、{@link #GAMEPLAY}、{@link #PLAYERS}、{@link #dimensionKey}、{@link #datacenterKey}；
     *                      YouranMod 机房的路由器是 {@code youranmod:router}）；没分到的为 null，这台路由器就不建（下面的都连不上）
     * @param resolver      递归解析器（机房 RA 的 RDNSS）
     */
    RouterNetwork(EventLoop loop, Random random, AddressBook book, NicTable nics, Function<String, Ipv6Address> routerAddress, Ipv6Address resolver,
                  String youranmodRouterKey) {
        this.loop = loop;
        this.random = random;
        this.layout = book.layout;
        this.simulation = new Router.Simulation(random);
        Ipv6Address gameplayPrefix = layout.datacenterPrefix(0);
        core = add(new Router("/48 核心路由器", require(routerAddress, CORE), loop, random));
        gameplay = add(new Router("玩法路由器", require(routerAddress, GAMEPLAY), loop, random));
        Ipv6Address playersAddress = routerAddress.apply(PLAYERS);
        players = playersAddress == null ? null : add(new Router("玩家路由器", playersAddress, loop, random));

        // 核心 ⇄ 玩法
        Router.LinkPort[] cg = link(core, "玩法", gameplay, "/48 核心");
        core.via(gameplayPrefix, 64, cg[0], LOWER);
        gameplay.via(new Ipv6Address(0, 0), 0, cg[1], UPPER);
        gameplay.via(core.address, 128, cg[1], UPPER);
        core.reject(layout.prefix(), 48, Icmpv6Message.UNREACH_NO_ROUTE);
        core.addRoute(new Ipv6Address(0, 0), 0, Router.Action.UPLINK, null, null, 0);
        gameplay.reject(gameplayPrefix, 64, Icmpv6Message.UNREACH_NO_ROUTE);

        // 维度
        for (AddressBook.Dim d : book.byDimension.values()) {
            Ipv6Address a = routerAddress.apply(dimensionKey(d.dimension));
            if (a == null) {
                continue;
            }
            Router r = add(new Router("维度 " + d.dimension + " 的路由器", a, loop, random));
            r.setNics(nics);
            Router.LinkPort[] cd = link(core, "维度 " + d.dimension, r, "/48 核心");
            Ipv6Address prefix = layout.dimensionPrefix(d.index);
            core.via(prefix, 72, cd[0], LOWER);
            core.via(a, 128, cd[0], LOWER);
            gameplay.via(a, 128, cg[1], UPPER);
            r.addRoute(prefix, 72, Router.Action.NICS, null, null, 0);
            r.via(new Ipv6Address(0, 0), 0, cd[1], UPPER);
            dimensions.put(d.dimension, r);
        }

        // 玩家
        if (players != null) {
            Router.LinkPort[] gp = link(gameplay, "玩家", players, "玩法");
            gameplay.via(layout.playersPrefix(), 96, gp[0], LOWER);
            gameplay.via(players.address, 128, gp[0], LOWER);
            players.reject(layout.playersPrefix(), 96, Icmpv6Message.UNREACH_ADDRESS);
            players.via(new Ipv6Address(0, 0), 0, gp[1], UPPER);
        }

        // 机房
        for (Map.Entry<String, Long> e : book.datacenters.entrySet()) {
            String key = e.getKey();
            if (key.equals(YouranNetworkApi.PLAYERS_DATACENTER) || key.equals(ROUTERS_DATACENTER)) {
                continue;
            }
            boolean ours = key.equals(YouranNetworkApi.YOURANMOD_DATACENTER);
            Ipv6Address a = routerAddress.apply(ours ? youranmodRouterKey : datacenterKey(key));
            if (a == null) {
                continue;
            }
            long index = e.getValue();
            Ipv6Address prefix = layout.datacenterPrefix(index);
            Router r = add(new Router("机房 " + key + " 的路由器", a, loop, random));
            Router.LinkPort[] gd = link(gameplay, "机房 " + key, r, "玩法");
            gameplay.via(prefix, 96, gd[0], LOWER);
            if (!a.inPrefix(prefix, 96)) {
                gameplay.via(a, 128, gd[0], LOWER);
            }
            Lan lan = new Lan(key, loop);
            Router.LinkPort[] holder = new Router.LinkPort[1];
            long lla = lan.attach(p -> holder[0].fromLink(p));
            Router.LinkPort port = new Router.LinkPort(r, "机房 " + key + " 的局域网", lan.linkFor(lla), lla,
                    Ipv6Address.linkLocal(NeighborDiscovery.eui64(lla)));
            holder[0] = port;
            if (a.inPrefix(prefix, 96)) {
                port.addGlobal(a);
            }
            port.advertise(new NeighborDiscovery.Advertising(false, false, ROUTER_LIFETIME,
                    Collections.singletonList(new NdpMessage.Prefix(prefix, 96, true, false, NdpMessage.INFINITE, NdpMessage.INFINITE)),
                    Ipv6Packet.MAX_PACKET,
                    resolver == null ? Collections.<NdpMessage.Rdnss>emptyList()
                            : Collections.singletonList(new NdpMessage.Rdnss(RDNSS_LIFETIME, Collections.singletonList(resolver))),
                    NeighborDiscovery.Advertising.DEFAULT_MIN_INTERVAL, NeighborDiscovery.Advertising.DEFAULT_MAX_INTERVAL));
            r.onLink(prefix, 96, port);
            r.via(new Ipv6Address(0, 0), 0, gd[1], UPPER);
            datacenters.put(index, new Datacenter(key, index, r, lan, port));
        }
        for (Router r : all) {
            r.setSimulation(simulation);
        }
    }

    private static Ipv6Address require(Function<String, Ipv6Address> f, String key) {
        Ipv6Address a = f.apply(key);
        if (a == null) {
            throw new IllegalStateException("路由器 " + key + " 没分到地址");
        }
        return a;
    }

    private Router add(Router r) {
        all.add(r);
        return r;
    }

    /** 两台路由器之间的点对点链路：上面那台是 fe80::1，下面那台 fe80::2 */
    private Router.LinkPort[] link(Router upper, String upperName, Router lower, String lowerName) {
        Router.LinkPort[] ends = new Router.LinkPort[2];
        ends[0] = new Router.LinkPort(upper, "到" + upperName, (packet, dst) -> loop.execute(() -> ends[1].fromLink(packet)),
                NdpMessage.NO_LINK_ADDRESS, UPPER);
        ends[1] = new Router.LinkPort(lower, "到" + lowerName, (packet, dst) -> loop.execute(() -> ends[0].fromLink(packet)),
                NdpMessage.NO_LINK_ADDRESS, LOWER);
        return ends;
    }

    /** 网卡发出的包：交给它那个维度的路由器 */
    void fromNic(NicTable.Port port, byte[] packet) {
        Router r = dimensions.get(port.key.dimension);
        if (r != null) {
            r.receive(null, packet);
        }
    }

    /** 上行口进来的包：源地址不能是这个 /48 里的（防伪造） */
    void fromUplink(byte[] raw) {
        if (raw.length < Ipv6Packet.HEADER || layout.contains(Ipv6Packet.sourceOf(raw))) {
            core.malformed++;
            return;
        }
        core.receive(null, raw);
    }

    Datacenter datacenter(long index) {
        return datacenters.get(index);
    }

    List<Router> routers() {
        return Collections.unmodifiableList(all);
    }

    /** 某个地址是哪台路由器的（traceroute 看用）；不是为 null */
    Router routerAt(Ipv6Address a) {
        for (Router r : all) {
            if (r.owns(a)) {
                return r;
            }
        }
        return null;
    }

    void close() {
        for (Router r : all) {
            r.close();
        }
    }

    // ---- 统计 ----

    long sum(java.util.function.ToLongFunction<Router> f) {
        long n = 0;
        for (Router r : all) {
            n += f.applyAsLong(r);
        }
        return n;
    }
}
