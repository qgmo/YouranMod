package moe.qingu.youran.server.net;

import moe.qingu.youran.api.network.DefaultDimensionNetwork;
import moe.qingu.youran.api.network.Ipv6Address;
import moe.qingu.youran.api.network.packet.Icmpv6Message;
import moe.qingu.youran.api.network.packet.Ipv6Packet;
import moe.qingu.youran.api.network.packet.UdpDatagram;
import moe.qingu.youran.api.network.stack.NetStack;
import moe.qingu.youran.api.network.stack.PingResult;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 插着的网卡与路由（端口的校验、插拔、到不了时回什么）。0B 起每层都是真的路由器（{@link RouterLayersTest} 测分层本身），
 * 这里原来的预期跟着变了的地方：到不了时回差错的是那一层的路由器（空的坐标、网卡收不了的回维度路由器的地址，没登记的机房回玩法路由器，
 * /48 里没分的回核心路由器），不再都是机房里的 {@code youranmod:router}；机房里没有的主机要等邻居解析失败（三次 NS，约 3 秒）才回“地址不可达”。
 */
class RouterTest {
    final TestNetwork net = new TestNetwork();
    final Ipv6Address hostAddr = net.resolverAddr;
    final NetStack host = net.host(hostAddr);

    RouterTest() {
        net.settle();
    }

    PingResult ping(NetStack from, Ipv6Address to) throws Exception {
        CompletableFuture<PingResult> f = from.ping(null, to, 56, 5000);
        net.loop.advance(6000);
        return done(f);
    }

    @Test
    void nicsAndHostsAreReachable() throws Exception {
        TestNetwork.StackNic dev = net.emulated(0, -100, 64, 200);
        assertEquals(PingResult.Status.REPLY, ping(dev.stack, hostAddr).status);
        assertEquals(PingResult.Status.REPLY, ping(dev.stack, net.routerAddr).status, "机房路由器自己的地址（::1）回 ping");
        assertEquals(PingResult.Status.REPLY, ping(host, net.nics.addressAt(0, -100, 64, 200)).status);
        // 两张网卡之间（不同维度）
        TestNetwork.StackNic nether = net.emulated(-1, 5, 5, 5);
        assertEquals(PingResult.Status.REPLY, ping(nether.stack, net.nics.addressAt(0, -100, 64, 200)).status);
        assertEquals(dev.address(), net.nics.addressAt(0, -100, 64, 200), "网卡的地址就是这一格的 0 号地址");
    }

    @Test
    void emptyCoordinateIsAddressUnreachable() throws Exception {
        PingResult r = ping(host, net.nics.addressAt(0, 1, 2, 3));
        assertEquals(PingResult.Status.UNREACHABLE, r.status);
        assertEquals(Icmpv6Message.UNREACH_ADDRESS, r.code);
        assertEquals(net.dim(0).address, r.from, "维度路由器回的");
        // 1–255 号槽位现在也没有路由器
        Ipv6Address slot1 = net.layout.coordinateAddress(0, DefaultDimensionNetwork.INSTANCE.encode(1, 2, 3, 1));
        assertEquals(Icmpv6Message.UNREACH_ADDRESS, ping(host, slot1).code);
    }

    @Test
    void receiveFalseOrThrowingIsAddressUnreachable() throws Exception {
        TestNetwork.StackNic dev = net.emulated(0, 10, 10, 10);
        Ipv6Address a = dev.address();
        dev.refuse = true;
        PingResult r = ping(host, a);
        assertEquals(PingResult.Status.UNREACHABLE, r.status, "receive 返回 false");
        assertEquals(Icmpv6Message.UNREACH_ADDRESS, r.code, "ICMPv6 1/3");
        assertEquals(net.dim(0).address, r.from);
        dev.refuse = false;
        assertEquals(PingResult.Status.REPLY, ping(host, a).status);
        dev.explode = true;
        r = ping(host, a);
        assertEquals(PingResult.Status.UNREACHABLE, r.status, "receive 抛异常按 false 算");
        assertEquals(Icmpv6Message.UNREACH_ADDRESS, r.code);
    }

    @Test
    void unpluggedNicNeitherSendsNorReceives() throws Exception {
        TestNetwork.StackNic dev = net.emulated(0, 20, 20, 20);
        Ipv6Address a = dev.address();
        assertTrue(dev.isPlugged());
        net.unplug(dev);
        assertFalse(dev.isPlugged());
        assertNull(dev.address());
        assertEquals(1, net.unplugged.size(), "拔掉以后通知（ClientRelayNic 断开用）");
        int before = dev.received;
        PingResult r = ping(host, a);
        assertEquals(PingResult.Status.UNREACHABLE, r.status, "拔掉以后发给它的回目标不可达");
        assertEquals(Icmpv6Message.UNREACH_ADDRESS, r.code);
        assertEquals(before, dev.received, "不再交给 receive");
        assertFalse(dev.out(Icmpv6Message.echo(128, 1, 1, new byte[8]).toPacket(a, hostAddr).encode()), "拔掉以后 send 返回 false");
        // 再插回去就通
        Ipv6Address again = net.plug(dev, 0, 20, 20, 20);
        assertEquals(a, again);
        dev.stack.addAddress(again);
        assertEquals(PingResult.Status.REPLY, ping(host, a).status);
    }

    @Test
    void unknownSubnetsAndDimensionsHaveNoRoute() throws Exception {
        PingResult r = ping(host, net.layout.coordinateAddress(9, 0));
        assertEquals(Icmpv6Message.UNREACH_NO_ROUTE, r.code, "没分的维度序号");
        assertEquals(net.core().address, r.from, "/48 核心路由器回的");
        r = ping(host, net.layout.hostAddress(7, 1));
        assertEquals(Icmpv6Message.UNREACH_NO_ROUTE, r.code, "没登记的机房");
        assertEquals(net.gameplay().address, r.from, "玩法路由器回的");
        r = ping(host, net.layout.hostAddress(0, 0x99));
        assertEquals(Icmpv6Message.UNREACH_ADDRESS, r.code, "机房里没登记的主机：邻居解析失败");
        assertEquals(hostAddr, r.from, "在同一个局域网上：主机自己解析失败，按本机的“地址不可达”报");
        TestNetwork.StackNic dev = net.emulated(0, 1, 64, 1);
        r = ping(dev.stack, net.layout.hostAddress(0, 0x99));
        assertEquals(Icmpv6Message.UNREACH_ADDRESS, r.code, "从机房外面来的：机房路由器解析失败");
        assertEquals(net.routerAddr, r.from);
        assertEquals(Icmpv6Message.UNREACH_NO_ROUTE, ping(host, new Ipv6Address(net.layout.prefix().high() | 5, 1)).code, "第 5 号 /64");
        r = ping(host, Ipv6Address.parse("2001:db8::1"));
        assertEquals(Icmpv6Message.UNREACH_NO_ROUTE, r.code, "/48 以外（没有上行口）");
        assertEquals(net.core().address, r.from);
    }

    @Test
    void uplinkGetsOutsidePackets() throws Exception {
        List<byte[]> out = new ArrayList<>();
        net.core().setUplink(out::add);
        host.ping(null, Ipv6Address.parse("2001:db8::1"), 8, 100);
        net.loop.advance(10);
        assertEquals(1, out.size());
        assertEquals(Ipv6Packet.DEFAULT_HOP_LIMIT - 3, out.get(0)[7] & 0xFF, "经过机房、玩法、核心三台路由器");
        // 上行口进来的包不能用 /48 里的源地址
        long before = net.core().malformed;
        net.routers.fromUplink(Icmpv6Message.echo(128, 1, 1, new byte[0]).toPacket(net.routerAddr, hostAddr).encode());
        net.loop.advance(10);
        assertEquals(before + 1, net.core().malformed);
        // 外面的地址进来：照常转发到主机
        CompletableFuture<PingResult> f = new CompletableFuture<>();
        net.routers.fromUplink(Icmpv6Message.echo(128, 1, 1, new byte[0]).toPacket(Ipv6Address.parse("2001:db8::7"), hostAddr).encode());
        net.loop.advance(10);
        assertEquals(2, out.size(), "主机回的 echo reply 又从上行口出去");
        assertEquals(Ipv6Address.parse("2001:db8::7"), Ipv6Packet.destinationOf(out.get(1)));
        f.complete(null);
    }

    @Test
    void hopLimitExpires() throws Exception {
        CompletableFuture<PingResult> f = host.ping(null, net.layout.hostAddress(2, 5), 0, 1000, 1);
        net.loop.advance(1500);
        PingResult r = done(f);
        assertEquals(PingResult.Status.TIME_EXCEEDED, r.status);
        assertEquals(net.routerAddr, r.from, "跳数在第一台路由器（机房路由器）用完");
        assertEquals(1, net.datacenterRouter(0).timeExceededSent);
    }

    @Test
    void portRejectsSpoofedOversizedAndFloodingPackets() {
        TestNetwork.StackNic d = new TestNetwork.StackNic();
        d.stack = new NetStack(net.loop, p -> {
        }, new Random(3));
        Ipv6Address me = net.plug(d, 0, 1, 64, 1);
        // 正常的包
        assertTrue(d.out(Icmpv6Message.echo(128, 1, 1, new byte[8]).toPacket(me, hostAddr).encode()));
        net.loop.advance(10);
        assertEquals(1, net.nics.accepted);
        long rejectsBefore = net.rejects.total();
        // 伪造源地址
        assertFalse(d.out(Icmpv6Message.echo(128, 1, 2, new byte[8]).toPacket(hostAddr, net.routerAddr).encode()));
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
        net.loop.advance(10);
        assertEquals(1, net.nics.accepted);
        assertEquals(rejectsBefore + 4, net.rejects.total());
        // 超频：每秒 50 个，第 51 个起丢
        int ok = 0;
        for (int i = 0; i < 100; i++) {
            if (d.out(new UdpDatagram(1000, 9, new byte[1]).toPacket(me, hostAddr).encode())) {
                ok++;
            }
        }
        net.loop.advance(0);
        // 桶里第一包后剩 49 个，过了 20 毫秒又补回 1 个
        assertEquals(50, ok);
        assertEquals(1 + 50, net.nics.accepted);
        assertEquals(rejectsBefore + 4 + 50, net.rejects.total());
    }

    @Test
    void plugDoesNotReplaceAndHasNoAddressOutOfRange() {
        assertNull(net.nics.plug(new TestNetwork.StackNic(), 0, 600_000, 64, 0));
        assertNull(net.nics.plug(new TestNetwork.StackNic(), 0, 0, 300, 0));
        assertNull(net.nics.plug(new TestNetwork.StackNic(), 7, 0, 64, 0), "维度 7 没有全局 Wi-Fi");
        TestNetwork.StackNic a = new TestNetwork.StackNic();
        TestNetwork.StackNic b = new TestNetwork.StackNic();
        Ipv6Address addr = net.plug(a, 0, 0, 64, 0);
        assertNotNull(addr);
        assertNull(net.plug(b, 0, 0, 64, 0), "这一格已经插着网卡：返回 null");
        assertTrue(a.isPlugged(), "不顶掉旧的");
        assertFalse(b.isPlugged());
        assertSame(a, net.nics.at(0, 0, 64, 0).nic);
        assertNull(net.plug(a, 0, 5, 64, 5), "这张网卡已经插着：返回 null");
        assertNull(net.nics.at(0, 5, 64, 5));
        // 拔掉以后别的网卡能插这一格
        net.unplug(a);
        assertEquals(addr, net.plug(b, 0, 0, 64, 0));
    }

    @Test
    void clearUnplugsEverything() {
        TestNetwork.StackNic a = net.emulated(0, 30, 64, 30);
        net.nics.clear();
        assertFalse(a.isPlugged(), "关服时网卡上的端口也拿下，下次开服模组能重新插");
        assertNull(net.nics.plug(new TestNetwork.StackNic(), 0, 31, 64, 31), "网络停了插不上");
    }

    @Test
    void simulatedLossDropsPackets() throws Exception {
        TestNetwork.StackNic dev = net.emulated(0, 3, 3, 3);
        net.routers.simulation.set(1.0, 0);
        assertEquals(PingResult.Status.TIMEOUT, ping(dev.stack, hostAddr).status);
        net.routers.simulation.set(0, 1.0);
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
