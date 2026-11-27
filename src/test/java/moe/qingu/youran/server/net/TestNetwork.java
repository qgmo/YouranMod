package moe.qingu.youran.server.net;

import moe.qingu.youran.api.network.ClientHost;
import moe.qingu.youran.api.network.DefaultDimensionNetwork;
import moe.qingu.youran.api.network.Ipv6Address;
import moe.qingu.youran.api.network.NetworkInterface;
import moe.qingu.youran.api.network.NetworkLayout;
import moe.qingu.youran.api.network.YouranNetworkApi;
import moe.qingu.youran.api.network.stack.ManualEventLoop;
import moe.qingu.youran.api.network.stack.NeighborDiscovery;
import moe.qingu.youran.api.network.stack.NetStack;
import moe.qingu.youran.net.NicMessages;
import moe.qingu.youran.network.ClientHostState;
import moe.qingu.youran.network.NetworkHooks;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 单元测试用的整张网（网络线程上的逻辑，世界换成假的）：同开服时一样的路由器分层（维度 → /48 核心 → 玩法 → 机房 / 玩家），
 * 机房的局域网，插着的网卡表，玩家路由器上的链路。都在一个手动事件循环上，随机数固定种子。
 * <p>
 * 地址：维度 0（序号 0）、维度 −1（序号 1）；机房 {@code youranmod:main} 序号 0，路由器地址段序号 1，另一个机房 {@code test:dc} 序号 2；
 * YouranMod 机房的路由器是 0 号机房的 {@code ::1}。
 */
final class TestNetwork {
    final ManualEventLoop loop = new ManualEventLoop();
    final NetworkLayout layout = NetworkLayout.fromGlobalId(0x123456789aL);
    final RejectLog rejects = new RejectLog(10_000);
    final Map<String, Ipv6Address> hosts = new LinkedHashMap<>();
    final AddressBook book;
    final NicTable nics;
    final RouterNetwork routers;
    final List<NicTable.Port> unplugged = new ArrayList<>();
    final Ipv6Address routerAddr;
    final Ipv6Address resolverAddr;
    final Ipv6Address ntpAddr;
    PlayerNics players;
    private int seed = 100;

    TestNetwork() {
        this(50);
    }

    TestNetwork(int nicPacketsPerSecond) {
        Map<Integer, AddressBook.Dim> dims = new LinkedHashMap<>();
        dims.put(0, new AddressBook.Dim(0, 0, DefaultDimensionNetwork.INSTANCE));
        dims.put(-1, new AddressBook.Dim(-1, 1, DefaultDimensionNetwork.INSTANCE));
        Map<String, Long> dcs = new LinkedHashMap<>();
        dcs.put(YouranNetworkApi.YOURANMOD_DATACENTER, 0L);
        dcs.put(RouterNetwork.ROUTERS_DATACENTER, 1L);
        dcs.put("test:dc", 2L);
        dcs.put(YouranNetworkApi.PLAYERS_DATACENTER, NetworkLayout.PLAYERS_DATACENTER);
        routerAddr = layout.hostAddress(0, 1);
        resolverAddr = layout.hostAddress(0, 0x53);
        ntpAddr = layout.hostAddress(0, 0x123);
        hosts.put(NetworkServer.HOST_ROUTER, routerAddr);
        hosts.put(RouterNetwork.CORE, layout.hostAddress(1, 1));
        hosts.put(RouterNetwork.GAMEPLAY, layout.hostAddress(1, 2));
        hosts.put(RouterNetwork.PLAYERS, layout.hostAddress(1, 3));
        hosts.put(RouterNetwork.dimensionKey(0), layout.hostAddress(1, 4));
        hosts.put(RouterNetwork.dimensionKey(-1), layout.hostAddress(1, 5));
        hosts.put(RouterNetwork.datacenterKey("test:dc"), layout.hostAddress(1, 6));
        book = new AddressBook(layout, dims, dcs, hosts);
        RouterNetwork[] holder = new RouterNetwork[1];
        nics = new NicTable(book::coordinateAddress, loop, rejects, (port, p) -> holder[0].fromNic(port, p), nicPacketsPerSecond,
                nicPacketsPerSecond * 1280, unplugged::add);
        routers = new RouterNetwork(loop, new Random(1), book, nics, hosts::get, resolverAddr, NetworkServer.HOST_ROUTER);
        holder[0] = routers;
    }

    Ipv6Address address(String key) {
        return hosts.get(key);
    }

    Router core() {
        return routers.core;
    }

    Router gameplay() {
        return routers.gameplay;
    }

    Router dim(int dimension) {
        return routers.dimensions.get(dimension);
    }

    Router datacenterRouter(long index) {
        return routers.datacenter(index).router;
    }

    /** 让邻居发现、RA 都走完（重复地址检测、机房路由器的通告） */
    void settle() {
        loop.advance(8000);
    }

    /** 在机房 {@code index} 的局域网上接一台主机（同开服时：做邻居发现的网口，地址先做重复地址检测）；没 {@link #settle} 前还不能用 */
    NetStack host(long index, Ipv6Address address) {
        RouterNetwork.Datacenter dc = routers.datacenter(index);
        NetStack stack = new NetStack(loop, new Random(seed++));
        NetStack.Interface[] holder = new NetStack.Interface[1];
        long lla = dc.lan.attach(p -> holder[0].receive(p));
        holder[0] = stack.addNdInterface(0, "lan", dc.lan.linkFor(lla), lla, Ipv6Address.linkLocal(NeighborDiscovery.eui64(lla)));
        stack.setDefaultInterface(holder[0]);
        holder[0].addAddress(address);
        return stack;
    }

    /** 同上，在 YouranMod 的机房里 */
    NetStack host(Ipv6Address address) {
        return host(0, address);
    }

    RawHostPort rawHost(String key, Ipv6Address address) {
        return new RawHostPort(key, address, loop, routers.datacenter(NetworkLayout.datacenterIndexOf(address)).lan, new Random(seed++), rejects);
    }

    // ---- 坐标层的网卡 ----

    /** 插网卡（同 {@code YouranNetworkApi.plug}：表里插上，再把端口装到网卡上）；插不上返回 null */
    Ipv6Address plug(NetworkInterface nic, int dim, int x, int y, int z) {
        if (nic.isPlugged()) {
            return null;
        }
        NicTable.Port p = nics.plug(nic, dim, x, y, z);
        if (p == null) {
            return null;
        }
        assertTrue(NetworkHooks.nicAccess().attach(nic, p));
        return p.address;
    }

    /** 拔网卡（同 {@code YouranNetworkApi.unplug}） */
    void unplug(NetworkInterface nic) {
        NicTable.Port p = (NicTable.Port) NetworkHooks.nicAccess().portOf(nic);
        if (p != null && NetworkHooks.nicAccess().detach(nic, p)) {
            nics.unplug(p);
        }
    }

    /** 测试网卡：收到的包交给协议栈；{@link #refuse} 为真时收不了，{@link #explode} 为真时收包抛异常 */
    static final class StackNic extends NetworkInterface {
        NetStack stack;
        boolean refuse;
        boolean explode;
        int received;

        @Override
        public boolean receive(byte[] packet) {
            if (explode) {
                throw new IllegalStateException("测试：网卡收包时出错");
            }
            if (refuse) {
                return false;
            }
            received++;
            stack.receive(packet);
            return true;
        }

        boolean out(byte[] packet) {
            return send(packet);
        }
    }

    /** 在一格上插一张测试网卡，背后是一个协议栈（坐标层：静态地址，不做邻居发现） */
    StackNic emulated(int dim, int x, int y, int z) {
        StackNic n = new StackNic();
        n.stack = new NetStack(loop, n::out, new Random(seed++));
        Ipv6Address a = plug(n, dim, x, y, z);
        assertNotNull(a);
        n.stack.addAddress(a);
        return n;
    }

    // ---- 玩家 ----

    /** 开玩家地址（往外每秒 {@code outPps} 个包，往里每秒 {@code inPps} 个） */
    PlayerNics enablePlayers(Random random, int outPps, int inPps) {
        players = new PlayerNics(loop, layout, routers.players, rejects, resolverAddr, ntpAddr, random, outPps, outPps * 1280, inPps, inPps * 1280);
        return players;
    }

    /** 一个上线的玩家：他的客户端是真的 {@link ClientHostState}（一个协议栈、0 号网口做邻居发现与 DHCPv6） */
    final class Player {
        final UUID id = UUID.randomUUID();
        final String name;
        final List<byte[]> control = new ArrayList<>();
        final ClientHostState client;

        Player(String name, boolean unrestricted) {
            this.name = name;
            client = new ClientHostState(loop, new Random(seed++), m -> {
                if (m[0] == NicMessages.PACKET && m[1] == NicMessages.PLAYER_PORT) {
                    byte[] raw = NicMessages.packetBody(m);
                    loop.execute(() -> players.fromPlayer(id, name, raw));
                }
            });
            players.attach(id, name, new PlayerNics.Link() {
                @Override
                public void deliver(byte[] packet) {
                    loop.execute(() -> client.onMessage(NicMessages.packet(NicMessages.PLAYER_PORT, packet)));
                }

                @Override
                public void control(byte[] message) {
                    control.add(message);
                    loop.execute(() -> client.onMessage(message));
                }
            }, unrestricted);
        }

        ClientHost.Port port() {
            return client.port(NicMessages.PLAYER_PORT);
        }

        NetStack stack() {
            return client.stack();
        }

        /** 现在的地址（DHCPv6 拿到、过了重复地址检测以后才有） */
        Ipv6Address address() {
            ClientHost.Port p = port();
            return p == null ? null : p.address();
        }
    }

    /** 上线一个玩家并等他拿到地址 */
    Player online(String name) {
        return online(name, false);
    }

    Player online(String name, boolean unrestricted) {
        Player p = new Player(name, unrestricted);
        settle();
        assertNotNull(p.address(), name + " 没用 DHCPv6 拿到地址");
        return p;
    }
}
