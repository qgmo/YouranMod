package moe.qingu.youran.api.network.stack;

import moe.qingu.youran.api.network.Ipv6Address;
import moe.qingu.youran.api.network.packet.Icmpv6Message;
import moe.qingu.youran.api.network.packet.Ipv6Packet;
import moe.qingu.youran.api.network.packet.MalformedPacketException;
import moe.qingu.youran.api.network.packet.NdpMessage;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 邻居发现（RFC 4861）与重复地址检测（RFC 4862）：在一段假的局域网上（每台一个 48 位链路层地址，组播发给所有人），
 * 协议栈的网口与一台路由器（{@link NeighborDiscovery} 的路由器角色）。都用手动事件循环、固定种子。
 */
class NeighborDiscoveryTest {
    static final Ipv6Address PREFIX = Ipv6Address.parse("fd00:1:2:3::");
    static final Ipv6Address DNS = Ipv6Address.parse("fd00:1:2:3::53");
    final ManualEventLoop loop = new ManualEventLoop();
    /** 链路层地址 → 收帧的 */
    final Map<Long, Consumer<byte[]>> stations = new LinkedHashMap<>();
    /** 链路上发过的帧（发的人、目的链路层地址、包） */
    final List<Object[]> frames = new ArrayList<>();
    long next = 0x020000000001L;

    NeighborDiscovery.Link attach(long lla) {
        return (p, dst) -> {
            frames.add(new Object[]{lla, dst, p});
            loop.schedule(() -> {
                for (Map.Entry<Long, Consumer<byte[]>> e : new ArrayList<>(stations.entrySet())) {
                    if (e.getKey() != lla && (dst == NeighborDiscovery.BROADCAST || dst == e.getKey())) {
                        e.getValue().accept(p);
                    }
                }
            }, 1);
        };
    }

    NetStack host(Ipv6Address address, int seed) {
        long lla = next++;
        NetStack s = new NetStack(loop, new Random(seed));
        NetStack.Interface[] h = new NetStack.Interface[1];
        stations.put(lla, p -> h[0].receive(p));
        h[0] = s.addNdInterface(0, "lan", attach(lla), lla, Ipv6Address.linkLocal(NeighborDiscovery.eui64(lla)));
        s.setDefaultInterface(h[0]);
        if (address != null) {
            h[0].addAddress(address);
        }
        return s;
    }

    /** 一台路由器：发 RA（前缀在链路上、MTU、RDNSS） */
    NeighborDiscovery router(int seed) {
        long lla = next++;
        NeighborDiscovery[] r = new NeighborDiscovery[1];
        stations.put(lla, p -> {
            try {
                Ipv6Packet ip = Ipv6Packet.parse(p);
                if (r[0].accepts(ip.destination) && ip.nextHeader == Ipv6Packet.PROTO_ICMPV6) {
                    r[0].handle(ip, Icmpv6Message.parse(ip));
                }
            } catch (MalformedPacketException e) {
                // 丢掉
            }
        });
        r[0] = new NeighborDiscovery(loop, new Random(seed), attach(lla), lla, true, null);
        r[0].addAddress(Ipv6Address.linkLocal(NeighborDiscovery.eui64(lla)));
        r[0].advertise(new NeighborDiscovery.Advertising(false, false, 1800,
                Collections.singletonList(new NdpMessage.Prefix(PREFIX, 64, true, false, NdpMessage.INFINITE, NdpMessage.INFINITE)), 1280,
                Collections.singletonList(new NdpMessage.Rdnss(1200, Collections.singletonList(DNS))), 198_000, 600_000));
        return r[0];
    }

    static NdpMessage ndp(byte[] p) {
        try {
            Ipv6Packet ip = Ipv6Packet.parse(p);
            if (ip.nextHeader != Ipv6Packet.PROTO_ICMPV6) {
                return null;
            }
            Icmpv6Message m = Icmpv6Message.parse(ip);
            return NdpMessage.isNdpType(m.type) ? NdpMessage.parse(ip, m) : null;
        } catch (MalformedPacketException e) {
            return null;
        }
    }

    List<NdpMessage> ndpFrames(int type) {
        List<NdpMessage> out = new ArrayList<>();
        for (Object[] f : frames) {
            NdpMessage n = ndp((byte[]) f[2]);
            if (n != null && n.type == type) {
                out.add(n);
            }
        }
        return out;
    }

    @Test
    void dadComesBeforeTheAddressCanBeUsed() throws Exception {
        Ipv6Address a = Ipv6Address.parse("fd00:1:2:3::a");
        NetStack s = host(a, 1);
        CompletableFuture<Boolean> ready = s.defaultInterface().whenReady(a);
        loop.drain();
        assertFalse(s.usable(a), "暂定地址不能用");
        assertNull(s.defaultInterface().address());
        loop.advance(2100);
        assertTrue(ready.isDone() && ready.join(), "随机等待（不到 1 秒）加 1 秒没人应：能用了");
        assertEquals(a, s.defaultInterface().address());
        // 检测用的 NS：源地址未指定、发给请求节点组播地址、目标是这个地址
        boolean seen = false;
        for (Object[] f : frames) {
            byte[] p = (byte[]) f[2];
            NdpMessage n = ndp(p);
            if (n != null && n.type == NdpMessage.NEIGHBOR_SOLICITATION && a.equals(n.target)) {
                assertEquals(Ipv6Address.UNSPECIFIED, Ipv6Packet.sourceOf(p));
                assertEquals(a.solicitedNode(), Ipv6Packet.destinationOf(p));
                assertEquals(NdpMessage.NO_LINK_ADDRESS, n.sourceLinkAddress, "不带源链路层地址");
                assertEquals(255, p[7] & 0xFF, "跳数 255");
                seen = true;
            }
        }
        assertTrue(seen);
    }

    @Test
    void duplicateAddressIsNotEnabled() {
        Ipv6Address a = Ipv6Address.parse("fd00:1:2:3::b");
        NetStack first = host(a, 2);
        loop.advance(3000);
        assertTrue(first.usable(a));
        NetStack second = host(a, 3);
        List<Ipv6Address> dup = new ArrayList<>();
        second.defaultInterface().addListener(new NetStack.Interface.Listener() {
            @Override
            public void onDuplicate(NetStack.Interface iface, Ipv6Address address) {
                dup.add(address);
            }
        });
        CompletableFuture<Boolean> ready = second.defaultInterface().whenReady(a);
        loop.advance(3000);
        assertTrue(ready.isDone());
        assertFalse(ready.join(), "先在的那台防守（回 NA），后来的这个地址不启用");
        assertEquals(Collections.singletonList(a), dup);
        assertFalse(second.hasAddress(a));
        assertNull(second.defaultInterface().address());
        assertTrue(first.usable(a), "先在的那台照常");
        // 两台同时检测同一个地址：互相看到对方的 NS，都不启用
        Ipv6Address c = Ipv6Address.parse("fd00:1:2:3::c");
        NetStack x = host(null, 4);
        NetStack y = host(null, 5);
        loop.advance(3000);
        x.defaultInterface().addAddress(c);
        y.defaultInterface().addAddress(c);
        CompletableFuture<Boolean> rx = x.defaultInterface().whenReady(c);
        CompletableFuture<Boolean> ry = y.defaultInterface().whenReady(c);
        loop.advance(3000);
        assertFalse(rx.join());
        assertFalse(ry.join());
    }

    @Test
    void routerAdvertisementAndSolicitation() {
        NeighborDiscovery r = router(10);
        loop.advance(3000);
        frames.clear();
        NetStack s = host(Ipv6Address.parse("fd00:1:2:3::d"), 6);
        loop.advance(4000);
        // 主机发了 RS（链路本地地址能用以后），路由器 0.5 秒内回了 RA
        List<NdpMessage> rs = ndpFrames(NdpMessage.ROUTER_SOLICITATION);
        assertEquals(1, rs.size(), "收到 RA 以后不再请求");
        long rsAt = -1;
        long raAt = -1;
        int i = 0;
        for (Object[] f : frames) {
            NdpMessage n = ndp((byte[]) f[2]);
            if (n != null && n.type == NdpMessage.ROUTER_SOLICITATION) {
                rsAt = i;
            }
            if (n != null && n.type == NdpMessage.ROUTER_ADVERTISEMENT && rsAt >= 0 && raAt < 0) {
                raAt = i;
                byte[] p = (byte[]) f[2];
                assertTrue(Ipv6Packet.sourceOf(p).isLinkLocal(), "RA 从链路本地地址发");
                assertEquals(Ipv6Address.ALL_NODES, Ipv6Packet.destinationOf(p));
                assertEquals(1800, n.routerLifetime);
                assertEquals(1280, n.mtu);
                assertEquals(1, n.prefixes.size());
                assertTrue(n.prefixes.get(0).onLink);
                assertFalse(n.prefixes.get(0).autonomous, "不做 SLAAC");
                assertEquals(Collections.singletonList(DNS), n.rdnss.get(0).servers);
                assertEquals(r.linkAddress(), n.sourceLinkAddress);
            }
            i++;
        }
        assertTrue(raAt > rsAt, "RS 之后有 RA");
        NeighborDiscovery nd = s.defaultInterface().neighborDiscovery();
        assertEquals(1, nd.defaultRouters().size(), "默认路由器就是这台");
        assertEquals(Collections.singletonList(PREFIX + "/64"), nd.onLinkPrefixes());
        assertEquals(1280, nd.mtu());
        assertEquals(DNS, s.defaultInterface().dnsServer(), "RDNSS 设成了网口的 DNS");
        assertEquals(Ipv6Address.parse("fd00:1:2:3::99"), nd.nextHop(Ipv6Address.parse("fd00:1:2:3::99")), "在链路上的前缀里：直接发");
        assertEquals(nd.defaultRouters().get(0), nd.nextHop(Ipv6Address.parse("fd00:9::1")), "别的发给默认路由器");
    }

    @Test
    void addressResolutionQueuesThenDelivers() throws Exception {
        router(11);
        NetStack a = host(Ipv6Address.parse("fd00:1:2:3::1"), 7);
        NetStack b = host(Ipv6Address.parse("fd00:1:2:3::2"), 8);
        loop.advance(5000);
        frames.clear();
        CompletableFuture<PingResult> f = a.ping(null, Ipv6Address.parse("fd00:1:2:3::2"), 8, 1000);
        loop.advance(100);
        assertEquals(PingResult.Status.REPLY, f.join().status);
        // A 先发组播 NS（带源链路层地址），B 单播回 NA（S、O 位，带目标链路层地址），然后才是回显请求
        NdpMessage ns = ndpFrames(NdpMessage.NEIGHBOR_SOLICITATION).get(0);
        assertEquals(Ipv6Address.parse("fd00:1:2:3::2"), ns.target);
        assertTrue(ns.sourceLinkAddress != NdpMessage.NO_LINK_ADDRESS);
        NdpMessage na = ndpFrames(NdpMessage.NEIGHBOR_ADVERTISEMENT).get(0);
        assertTrue(na.solicited && na.override && !na.router);
        assertTrue(na.targetLinkAddress != NdpMessage.NO_LINK_ADDRESS);
        assertEquals("REACHABLE", a.defaultInterface().neighborDiscovery().neighborState(Ipv6Address.parse("fd00:1:2:3::2")));
        // 过了可达时间变陈旧；再发包：先照发、5 秒后单播 NS 探测，对方在就又可达
        loop.advance(50_000);
        assertEquals("STALE", a.defaultInterface().neighborDiscovery().neighborState(Ipv6Address.parse("fd00:1:2:3::2")));
        a.ping(null, Ipv6Address.parse("fd00:1:2:3::2"), 8, 1000);
        loop.advance(10);
        assertEquals("DELAY", a.defaultInterface().neighborDiscovery().neighborState(Ipv6Address.parse("fd00:1:2:3::2")));
        loop.advance(6000);
        assertEquals("REACHABLE", a.defaultInterface().neighborDiscovery().neighborState(Ipv6Address.parse("fd00:1:2:3::2")));
        assertNotNull(b);
    }

    @Test
    void failedResolutionIsAddressUnreachable() throws Exception {
        router(12);
        NetStack a = host(Ipv6Address.parse("fd00:1:2:3::1"), 9);
        loop.advance(5000);
        frames.clear();
        CompletableFuture<PingResult> f = a.ping(null, Ipv6Address.parse("fd00:1:2:3::77"), 8, 10_000);
        loop.advance(2500);
        assertFalse(f.isDone(), "还在解析（一秒一次 NS）");
        loop.advance(1000);
        PingResult r = f.join();
        assertEquals(PingResult.Status.UNREACHABLE, r.status);
        assertEquals(Icmpv6Message.UNREACH_ADDRESS, r.code);
        assertEquals(NeighborDiscovery.MAX_MULTICAST_SOLICIT, ndpFrames(NdpMessage.NEIGHBOR_SOLICITATION).size());
        // 没有默认路由器时链路外的地址：没有路由（不假定在链路上）
        NetStack lonely = host(Ipv6Address.parse("fd00:5::1"), 13);
        stations.clear();
        loop.advance(5000);
        CompletableFuture<PingResult> g = lonely.ping(null, Ipv6Address.parse("fd00:9::1"), 8, 1000);
        loop.advance(10);
        assertEquals(Icmpv6Message.UNREACH_NO_ROUTE, g.join().code);
    }

    @Test
    void forgedOrBrokenNdpIsIgnored() {
        NetStack s = host(Ipv6Address.parse("fd00:1:2:3::5"), 14);
        loop.advance(3000);
        Ipv6Address victim = Ipv6Address.parse("fd00:1:2:3::5");
        // 跳数不是 255 的 NA（可能是从别的链路转发进来的伪造）：不理，地址照旧能用
        byte[] na = NdpMessage.neighborAdvertisement(victim, false, false, true, 0x020000000099L).toPacket(victim, Ipv6Address.ALL_NODES).encode();
        na[7] = 64;
        s.defaultInterface().receive(na);
        // 乱码：只丢不崩
        Random rnd = new Random(99);
        for (int i = 0; i < 500; i++) {
            byte[] junk = new byte[41 + rnd.nextInt(80)];
            rnd.nextBytes(junk);
            junk[0] = 0x60;
            junk[6] = 58;
            junk[7] = (byte) 255;
            junk[40] = (byte) (133 + rnd.nextInt(4));
            s.defaultInterface().receive(junk);
        }
        loop.advance(100);
        assertTrue(s.usable(victim));
    }
}
