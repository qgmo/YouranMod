package moe.qingu.youran.server.net;

import moe.qingu.youran.api.network.DefaultDimensionNetwork;
import moe.qingu.youran.api.network.Ipv6Address;
import moe.qingu.youran.api.network.NetworkInterface;
import moe.qingu.youran.api.network.NetworkLayout;
import moe.qingu.youran.api.network.packet.Icmpv6Message;
import moe.qingu.youran.api.network.packet.Ipv6Packet;
import moe.qingu.youran.api.network.packet.UdpDatagram;
import moe.qingu.youran.api.network.stack.ManualEventLoop;
import moe.qingu.youran.api.network.stack.NetStack;
import moe.qingu.youran.api.network.stack.PingResult;
import moe.qingu.youran.network.NetworkHooks;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** /48 路由器、维度路由、机房路由与插着的网卡（端口的校验、插拔；网络线程上的逻辑，世界换成假的） */
class RouterTest {
    final ManualEventLoop loop = new ManualEventLoop();
    final NetworkLayout layout = NetworkLayout.fromGlobalId(0x123456789aL);
    final Ipv6Address routerAddr = layout.hostAddress(0, 1);
    final Ipv6Address hostAddr = layout.hostAddress(0, 0x53);
    final AddressBook book;
    NicTable nics;
    Router router;
    RejectLog rejects = new RejectLog(10_000);
    NetStack host;
    final List<NicTable.Port> unplugged = new ArrayList<>();

    RouterTest() {
        Map<Integer, AddressBook.Dim> dims = new LinkedHashMap<>();
        dims.put(0, new AddressBook.Dim(0, 0, DefaultDimensionNetwork.INSTANCE));
        dims.put(-1, new AddressBook.Dim(-1, 1, DefaultDimensionNetwork.INSTANCE));
        Map<String, Long> dcs = Collections.singletonMap("youranmod:main", 0L);
        Map<String, Ipv6Address> hosts = new LinkedHashMap<>();
        hosts.put("youranmod:router", routerAddr);
        hosts.put("youranmod:resolver", hostAddr);
        book = new AddressBook(layout, dims, dcs, hosts);
        nics = new NicTable(book::coordinateAddress, loop, rejects, p -> router.forward(p), 50, 50 * 1280, unplugged::add);
        router = new Router(loop, book, nics, routerAddr);
        NetStack r = new NetStack(loop, p -> loop.execute(() -> router.forward(p)), new Random(1));
        r.addAddress(routerAddr);
        router.attachHost(routerAddr, r);
        host = new NetStack(loop, p -> loop.execute(() -> router.forward(p)), new Random(2));
        host.addAddress(hostAddr);
        router.attachHost(hostAddr, host);
        router.attachDatacenter(0);
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

    /** 在一格上插一张测试网卡，背后是一个协议栈 */
    StackNic emulated(int dim, int x, int y, int z) {
        StackNic n = new StackNic();
        n.stack = new NetStack(loop, n::out, new Random(x));
        Ipv6Address a = plug(n, dim, x, y, z);
        assertNotNull(a);
        n.stack.addAddress(a);
        return n;
    }

    PingResult ping(NetStack from, Ipv6Address to) throws Exception {
        CompletableFuture<PingResult> f = from.ping(null, to, 56, 2000);
        loop.advance(3000);
        return done(f);
    }

    @Test
    void nicsAndHostsAreReachable() throws Exception {
        StackNic dev = emulated(0, -100, 64, 200);
        assertEquals(PingResult.Status.REPLY, ping(dev.stack, hostAddr).status);
        assertEquals(PingResult.Status.REPLY, ping(dev.stack, routerAddr).status);
        assertEquals(PingResult.Status.REPLY, ping(host, nics.addressAt(0, -100, 64, 200)).status);
        // 两张网卡之间（不同维度）
        StackNic nether = emulated(-1, 5, 5, 5);
        assertEquals(PingResult.Status.REPLY, ping(nether.stack, nics.addressAt(0, -100, 64, 200)).status);
        assertEquals(dev.address(), nics.addressAt(0, -100, 64, 200), "网卡的地址就是这一格的 0 号地址");
    }

    @Test
    void emptyCoordinateIsAddressUnreachable() throws Exception {
        PingResult r = ping(host, nics.addressAt(0, 1, 2, 3));
        assertEquals(PingResult.Status.UNREACHABLE, r.status);
        assertEquals(Icmpv6Message.UNREACH_ADDRESS, r.code);
        assertEquals(routerAddr, r.from);
        // 1–255 号槽位现在也没有路由器
        Ipv6Address slot1 = layout.coordinateAddress(0, DefaultDimensionNetwork.INSTANCE.encode(1, 2, 3, 1));
        assertEquals(Icmpv6Message.UNREACH_ADDRESS, ping(host, slot1).code);
    }

    @Test
    void receiveFalseOrThrowingIsAddressUnreachable() throws Exception {
        StackNic dev = emulated(0, 10, 10, 10);
        Ipv6Address a = dev.address();
        dev.refuse = true;
        PingResult r = ping(host, a);
        assertEquals(PingResult.Status.UNREACHABLE, r.status, "receive 返回 false");
        assertEquals(Icmpv6Message.UNREACH_ADDRESS, r.code, "ICMPv6 1/3");
        dev.refuse = false;
        assertEquals(PingResult.Status.REPLY, ping(host, a).status);
        dev.explode = true;
        r = ping(host, a);
        assertEquals(PingResult.Status.UNREACHABLE, r.status, "receive 抛异常按 false 算");
        assertEquals(Icmpv6Message.UNREACH_ADDRESS, r.code);
    }

    @Test
    void unpluggedNicNeitherSendsNorReceives() throws Exception {
        StackNic dev = emulated(0, 20, 20, 20);
        Ipv6Address a = dev.address();
        assertTrue(dev.isPlugged());
        unplug(dev);
        assertFalse(dev.isPlugged());
        assertNull(dev.address());
        assertEquals(1, unplugged.size(), "拔掉以后通知（ClientRelayNic 断开用）");
        int before = dev.received;
        PingResult r = ping(host, a);
        assertEquals(PingResult.Status.UNREACHABLE, r.status, "拔掉以后发给它的回目标不可达");
        assertEquals(Icmpv6Message.UNREACH_ADDRESS, r.code);
        assertEquals(before, dev.received, "不再交给 receive");
        assertFalse(dev.out(Icmpv6Message.echo(128, 1, 1, new byte[8]).toPacket(a, hostAddr).encode()), "拔掉以后 send 返回 false");
        // 再插回去就通
        Ipv6Address again = plug(dev, 0, 20, 20, 20);
        assertEquals(a, again);
        dev.stack.addAddress(again);
        assertEquals(PingResult.Status.REPLY, ping(host, a).status);
    }

    @Test
    void unknownSubnetsAndDimensionsHaveNoRoute() throws Exception {
        assertEquals(Icmpv6Message.UNREACH_NO_ROUTE, ping(host, layout.coordinateAddress(9, 0)).code, "没分的维度序号");
        assertEquals(Icmpv6Message.UNREACH_NO_ROUTE, ping(host, layout.hostAddress(7, 1)).code, "没登记的机房");
        assertEquals(Icmpv6Message.UNREACH_ADDRESS, ping(host, layout.hostAddress(0, 0x99)).code, "机房里没登记的主机");
        assertEquals(Icmpv6Message.UNREACH_NO_ROUTE, ping(host, new Ipv6Address(layout.prefix().high() | 5, 1)).code, "第 5 号 /64");
        assertEquals(Icmpv6Message.UNREACH_NO_ROUTE, ping(host, Ipv6Address.parse("2001:db8::1")).code, "/48 以外（没有上行口）");
    }

    @Test
    void uplinkGetsOutsidePackets() throws Exception {
        List<byte[]> out = new ArrayList<>();
        router.setUplink(out::add);
        host.ping(null, Ipv6Address.parse("2001:db8::1"), 8, 100);
        loop.advance(10);
        assertEquals(1, out.size());
        // 上行口进来的包不能用 /48 里的源地址
        router.fromUplink(Icmpv6Message.echo(128, 1, 1, new byte[0]).toPacket(routerAddr, hostAddr).encode());
        loop.advance(10);
        assertEquals(1, router.malformed);
    }

    @Test
    void hopLimitExpires() throws Exception {
        byte[] p = Icmpv6Message.echo(128, 1, 1, new byte[0]).toPacket(hostAddr, routerAddr).encode();
        p[7] = 1;
        // 重新算校验和不需要：跳数不在伪首部里
        router.forward(p);
        loop.advance(10);
        assertEquals(1, router.timeExceededSent);
    }

    @Test
    void portRejectsSpoofedOversizedAndFloodingPackets() {
        StackNic d = new StackNic();
        d.stack = new NetStack(loop, p -> {
        }, new Random(3));
        Ipv6Address me = plug(d, 0, 1, 64, 1);
        // 正常的包
        assertTrue(d.out(Icmpv6Message.echo(128, 1, 1, new byte[8]).toPacket(me, hostAddr).encode()));
        loop.advance(10);
        assertEquals(1, nics.accepted);
        // 伪造源地址
        assertFalse(d.out(Icmpv6Message.echo(128, 1, 2, new byte[8]).toPacket(hostAddr, routerAddr).encode()));
        // 1281 字节的“包”
        byte[] big = new byte[1281];
        big[0] = 0x60;
        assertFalse(d.out(big));
        // 不是 IPv6
        assertFalse(d.out(new byte[40]));
        // 载荷长度超过实际长度
        byte[] lying = Icmpv6Message.echo(128, 1, 3, new byte[8]).toPacket(me, hostAddr).encode();
        lying[5] = (byte) 0xFF;
        assertFalse(d.out(lying));
        loop.advance(10);
        assertEquals(1, nics.accepted);
        assertEquals(4, rejects.total());
        // 超频：每秒 50 个，第 51 个起丢
        int ok = 0;
        for (int i = 0; i < 100; i++) {
            if (d.out(new UdpDatagram(1000, 9, new byte[1]).toPacket(me, hostAddr).encode())) {
                ok++;
            }
        }
        loop.advance(0);
        // 桶里第一包后剩 49 个，过了 20 毫秒又补回 1 个
        assertEquals(50, ok);
        assertEquals(1 + 50, nics.accepted);
        assertEquals(4 + 50, rejects.total());
    }

    @Test
    void plugDoesNotReplaceAndHasNoAddressOutOfRange() {
        assertNull(nics.plug(new StackNic(), 0, 600_000, 64, 0));
        assertNull(nics.plug(new StackNic(), 0, 0, 300, 0));
        assertNull(nics.plug(new StackNic(), 7, 0, 64, 0), "维度 7 没有全局 Wi-Fi");
        StackNic a = new StackNic();
        StackNic b = new StackNic();
        Ipv6Address addr = plug(a, 0, 0, 64, 0);
        assertNotNull(addr);
        assertNull(plug(b, 0, 0, 64, 0), "这一格已经插着网卡：返回 null");
        assertTrue(a.isPlugged(), "不顶掉旧的");
        assertFalse(b.isPlugged());
        assertSame(a, nics.at(0, 0, 64, 0).nic);
        assertNull(plug(a, 0, 5, 64, 5), "这张网卡已经插着：返回 null");
        assertNull(nics.at(0, 5, 64, 5));
        // 拔掉以后别的网卡能插这一格
        unplug(a);
        assertEquals(addr, plug(b, 0, 0, 64, 0));
    }

    @Test
    void clearUnplugsEverything() {
        StackNic a = emulated(0, 30, 64, 30);
        nics.clear();
        assertFalse(a.isPlugged(), "关服时网卡上的端口也拿下，下次开服模组能重新插");
        assertNull(nics.plug(new StackNic(), 0, 31, 64, 31), "网络停了插不上");
    }

    @Test
    void simulatedLossDropsPackets() throws Exception {
        StackNic dev = emulated(0, 3, 3, 3);
        router.setSimulation(1.0, 0);
        assertEquals(PingResult.Status.TIMEOUT, ping(dev.stack, hostAddr).status);
        router.setSimulation(0, 1.0);
        PingResult r = ping(dev.stack, hostAddr);
        assertEquals(PingResult.Status.REPLY, r.status);
        assertTrue(r.rttMillis >= 40, "两个方向都推迟 20–80 毫秒");
        assertTrue(Ipv6Packet.MAX_PACKET == 1280);
    }

    static <T> T done(java.util.concurrent.CompletableFuture<T> f) throws Exception {
        org.junit.jupiter.api.Assertions.assertTrue(f.isDone(), "future 还没完成");
        return f.get();
    }
}
