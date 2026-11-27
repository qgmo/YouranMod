package moe.qingu.youran.server.net;

import moe.qingu.youran.api.network.Ipv6Address;
import moe.qingu.youran.api.network.packet.Icmpv6Message;
import moe.qingu.youran.api.network.packet.Ipv6Packet;
import moe.qingu.youran.api.network.packet.UdpDatagram;
import moe.qingu.youran.api.network.stack.NetStack;
import moe.qingu.youran.api.network.stack.PingResult;
import moe.qingu.youran.api.network.stack.UdpSocket;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 路由器分层（服主 11-27）：每层是真的路由器，有自己的地址、路由表，转发时跳数减一，跳数用完回“超时”，差错用那一层路由器自己的地址；
 * traceroute 式逐跳探测看到 维度 → /48 → 玩法 → 机房 → 主机；路由器之间的链路上做 NS / NA。
 */
class RouterLayersTest {
    final TestNetwork net = new TestNetwork();
    final Ipv6Address hostAddr = net.layout.hostAddress(0, 0x80);
    final NetStack host = net.host(hostAddr);

    RouterLayersTest() {
        net.settle();
    }

    /** 逐跳：跳数 1、2、3……，每一跳回“超时”的是谁，到了以后是回显应答 */
    List<Ipv6Address> trace(NetStack from, Ipv6Address to) {
        List<Ipv6Address> hops = new ArrayList<>();
        for (int hop = 1; hop <= 10; hop++) {
            CompletableFuture<PingResult> f = from.ping(null, to, 8, 2000, hop);
            net.loop.advance(3000);
            PingResult r = f.join();
            hops.add(r.from);
            if (r.status == PingResult.Status.REPLY) {
                return hops;
            }
            assertEquals(PingResult.Status.TIME_EXCEEDED, r.status, "第 " + hop + " 跳");
        }
        throw new AssertionError("10 跳内没到：" + hops);
    }

    @Test
    void traceFromADeviceSeesEveryLayer() {
        TestNetwork.StackNic dev = net.emulated(0, 7, 64, 7);
        List<Ipv6Address> hops = trace(dev.stack, hostAddr);
        assertEquals(Arrays.asList(net.dim(0).address, net.core().address, net.gameplay().address, net.routerAddr, hostAddr), hops,
                "维度 → /48 → 玩法 → 机房 → 主机");
        // 回去的方向也一样逐跳
        assertEquals(Arrays.asList(net.routerAddr, net.gameplay().address, net.core().address, net.dim(0).address, dev.address()),
                trace(host, dev.address()));
    }

    @Test
    void traceFromAPlayerGoesThroughThePlayerRouter() {
        net.enablePlayers(new Random(5), 1000, 1000);
        TestNetwork.Player p = net.online("Tracer", true);
        assertEquals(Arrays.asList(net.address(RouterNetwork.PLAYERS), net.gameplay().address, net.routerAddr, hostAddr), trace(p.stack(), hostAddr));
        // 别的机房里的主机
        Ipv6Address other = net.layout.hostAddress(2, 9);
        NetStack o = net.host(2, other);
        net.settle();
        assertEquals(Arrays.asList(net.address(RouterNetwork.PLAYERS), net.gameplay().address, net.datacenterRouter(2).address, other),
                trace(p.stack(), other));
        assertNotNull(o);
    }

    @Test
    void everyRouterDecrementsTheHopLimit() throws Exception {
        // 原始主机收到的是原样的包，看得到跳数
        Ipv6Address rawAddr = net.layout.hostAddress(0, 0x81);
        RawHostPort raw = net.rawHost("test:hops", rawAddr);
        List<Integer> hops = new ArrayList<>();
        raw.setReceiver(p -> hops.add(p[7] & 0xFF));
        net.settle();
        TestNetwork.StackNic dev = net.emulated(0, 8, 64, 8);
        dev.stack.openUdp(null, 0, (s, from, fromPort, to, data) -> {
        }).get().send(rawAddr, 9000, new byte[]{1});
        net.loop.advance(100);
        host.openUdp(null, 0, (s, from, fromPort, to, data) -> {
        }).get().send(rawAddr, 9000, new byte[]{1});
        net.loop.advance(100);
        assertEquals(Arrays.asList(Ipv6Packet.DEFAULT_HOP_LIMIT - 4, Ipv6Packet.DEFAULT_HOP_LIMIT), hops,
                "从坐标层来：维度、核心、玩法、机房四台路由器各减一；同一个局域网上：不经路由器");
    }

    @Test
    void errorsComeFromTheLayerThatSendsThem() throws Exception {
        TestNetwork.StackNic dev = net.emulated(0, 9, 64, 9);
        // 跳数在核心用完：核心回超时
        CompletableFuture<PingResult> f = dev.stack.ping(null, hostAddr, 8, 2000, 2);
        net.loop.advance(3000);
        assertEquals(net.core().address, f.join().from);
        // 别的机房里没有的主机：那个机房的路由器解析失败、回地址不可达
        f = dev.stack.ping(null, net.layout.hostAddress(2, 0x4444), 8, 5000);
        net.loop.advance(6000);
        PingResult r = f.join();
        assertEquals(Icmpv6Message.UNREACH_ADDRESS, r.code);
        assertEquals(net.datacenterRouter(2).address, r.from);
        // 路由器自己的地址都能 ping 通（控制面）
        for (Router x : net.routers.routers()) {
            f = dev.stack.ping(null, x.address, 8, 2000);
            net.loop.advance(3000);
            assertEquals(PingResult.Status.REPLY, f.join().status, x.name);
            assertEquals(x.address, f.join().from);
        }
        // 路由器的 UDP：端口不可达，也从它自己的地址回
        List<Integer> codes = new ArrayList<>();
        List<Ipv6Address> reporters = new ArrayList<>();
        UdpSocket s = dev.stack.openUdp(null, 0, new UdpSocket.Handler() {
            @Override
            public void onDatagram(UdpSocket socket, Ipv6Address from, int fromPort, Ipv6Address to, byte[] data) {
            }

            @Override
            public void onError(UdpSocket socket, Ipv6Address destination, int icmpType, int icmpCode, Ipv6Address reporter) {
                codes.add(icmpCode);
                reporters.add(reporter);
            }
        }).get();
        s.send(net.gameplay().address, 33434, new byte[1]);
        net.loop.advance(100);
        assertEquals(Arrays.asList(Icmpv6Message.UNREACH_PORT), codes);
        assertEquals(Arrays.asList(net.gameplay().address), reporters);
    }

    @Test
    void routerLinksUseNeighborDiscovery() throws Exception {
        TestNetwork.StackNic dev = net.emulated(0, 10, 64, 10);
        CompletableFuture<PingResult> f = dev.stack.ping(null, hostAddr, 8, 2000);
        net.loop.advance(3000);
        assertEquals(PingResult.Status.REPLY, f.join().status);
        // 路过的每段路由器之间的链路上：对方的 fe80:: 解析过了（NS / NA），是可达或陈旧的邻居
        for (Router r : Arrays.asList(net.dim(0), net.core(), net.gameplay(), net.datacenterRouter(0))) {
            boolean any = false;
            for (Router.Port p : r.ports) {
                Router.LinkPort lp = (Router.LinkPort) p;
                for (String n : lp.nd.describeNeighbors()) {
                    if (n.startsWith("fe80::1 ") || n.startsWith("fe80::2 ")) {
                        assertTrue(n.contains("REACHABLE") || n.contains("STALE") || n.contains("DELAY"), r.name + " " + n);
                        any = true;
                    }
                }
                assertTrue(lp.nd.solicitationsSent > 0, r.name + " 的 " + lp.name + " 发过 NS（至少重复地址检测）");
            }
            assertTrue(any, r.name + " 解析过邻居路由器");
        }
    }

    @Test
    void linkLocalSourcesAreNotForwarded() throws Exception {
        // 链路本地的源地址发到链路外：路由器不转发，从它在这段链路上的链路本地地址回“超出源地址的范围”（代码 2）
        Ipv6Address ll = host.defaultInterface().linkLocalAddress();
        List<Integer> codes = new ArrayList<>();
        List<Ipv6Address> reporters = new ArrayList<>();
        UdpSocket s = host.openUdp(ll, 1000, new UdpSocket.Handler() {
            @Override
            public void onDatagram(UdpSocket socket, Ipv6Address from, int fromPort, Ipv6Address to, byte[] data) {
            }

            @Override
            public void onError(UdpSocket socket, Ipv6Address destination, int icmpType, int icmpCode, Ipv6Address reporter) {
                codes.add(icmpCode);
                reporters.add(reporter);
            }
        }).get();
        long before = net.datacenterRouter(0).forwarded;
        s.send(net.layout.hostAddress(2, 1), 53, new byte[1]);
        net.loop.advance(3000);
        assertEquals(Arrays.asList(2), codes);
        assertTrue(reporters.get(0).isLinkLocal(), "差错从路由器的链路本地地址回（目的地址是链路本地的）");
        assertEquals(before, net.datacenterRouter(0).forwarded, "没有转发出去");
    }
}
