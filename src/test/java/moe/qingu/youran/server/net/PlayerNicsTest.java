package moe.qingu.youran.server.net;

import moe.qingu.youran.api.network.Ipv6Address;
import moe.qingu.youran.api.network.NetworkLayout;
import moe.qingu.youran.api.network.packet.Icmpv6Message;
import moe.qingu.youran.api.network.packet.NtpPacket;
import moe.qingu.youran.api.network.packet.TcpSegment;
import moe.qingu.youran.api.network.packet.UdpDatagram;
import moe.qingu.youran.api.network.stack.NetStack;
import moe.qingu.youran.api.network.stack.NetworkException;
import moe.qingu.youran.api.network.stack.PingResult;
import moe.qingu.youran.api.network.stack.TcpConnection;
import moe.qingu.youran.api.network.stack.UdpSocket;
import moe.qingu.youran.net.NicMessages;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * 玩家地址：分配与回收、放行规则（往外、往里）、伪造、限频、撤销与过期，能用 /ynet 的人不过防火墙。
 * 0B 起玩家的客户端是真的 {@code ClientHostState}，地址经玩家链路上的 RA 与 DHCPv6 拿（链路本身见 {@link PlayerLinkTest}）；
 * 原来测“上线时服务器推地址”的预期改成：上线时 0 号网口接上（PORT_UP 里没有地址），地址在 DHCPv6 走完以后才有；
 * 回“管理上禁止”的是玩家路由器（原来是机房里的 {@code youranmod:router}）。
 */
class PlayerNicsTest {
    static final String PURPOSE = "cfm:spectate";
    final TestNetwork net = new TestNetwork();
    final Ipv6Address resolverAddr = net.resolverAddr;
    final Ipv6Address webAddr = net.layout.hostAddress(0, 2);
    final Ipv6Address otherAddr = net.layout.hostAddress(0, 3);
    final PlayerNics players;
    final NetStack web;
    final NetStack other;
    final NetStack resolver;
    final NetStack ntp;

    PlayerNicsTest() {
        // 往外每秒 20 个包，往里每秒 400 个
        players = net.enablePlayers(new Random(42), 20, 400);
        resolver = net.host(resolverAddr);
        web = net.host(webAddr);
        other = net.host(otherAddr);
        ntp = net.host(net.ntpAddr);
        net.settle();
        // 递归解析器：UDP 53 原样回；TCP 53 回显
        resolver.openUdp(resolverAddr, 53, (socket, from, fromPort, to, data) -> socket.send(from, fromPort, data));
        resolver.listen(null, 53, c -> c.setListener((conn, data) -> conn.write(data)));
        ntp.openUdp(null, NtpPacket.PORT, (socket, from, fromPort, to, data) -> socket.send(to, from, fromPort, data));
        // 网站：80、443、8080 都回显
        for (NetStack s : Arrays.asList(web, other)) {
            for (int port : new int[]{80, 443, 8080}) {
                s.listen(null, port, c -> c.setListener((conn, data) -> conn.write(data)));
            }
        }
        net.loop.drain();
    }

    private int nextPlayer;

    TestNetwork.Player online() {
        return net.online("P" + (++nextPlayer));
    }

    /** 连上去、发一句、等回显；连不上时抛出原因 */
    String echo(NetStack from, Ipv6Address to, int port, String text) throws Exception {
        ByteArrayOutputStream got = new ByteArrayOutputStream();
        CompletableFuture<TcpConnection> f = from.connect(null, to, port, (c, data) -> got.write(data, 0, data.length));
        net.loop.advance(500);
        assertTrue(f.isDone(), "连接还没结束");
        TcpConnection c = f.get();
        c.write(text.getBytes(StandardCharsets.UTF_8));
        net.loop.advance(500);
        return new String(got.toByteArray(), StandardCharsets.UTF_8);
    }

    static NetworkException.Reason failure(Callable c) {
        try {
            c.run();
        } catch (ExecutionException e) {
            assertTrue(e.getCause() instanceof NetworkException, String.valueOf(e.getCause()));
            return ((NetworkException) e.getCause()).reason;
        } catch (Exception e) {
            fail(e);
        }
        fail("应该失败");
        return null;
    }

    interface Callable {
        void run() throws Exception;
    }

    PingResult ping(NetStack from, Ipv6Address to) {
        CompletableFuture<PingResult> f = from.ping(null, to, 8, 1000);
        net.loop.advance(1500);
        try {
            return f.get();
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }

    // ---- 分配与回收 ----

    @Test
    void addressesAreRandomUniqueAndReleased() {
        Set<Ipv6Address> seen = new HashSet<>();
        List<TestNetwork.Player> clients = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            TestNetwork.Player c = online();
            clients.add(c);
            Ipv6Address a = c.address();
            assertTrue(net.layout.isPlayerAddress(a), a.toString());
            assertTrue(a.inPrefix(net.layout.playersPrefix(), 96));
            assertTrue(seen.add(a), "同一时刻不重复");
            assertEquals(NicMessages.PORT_UP, c.control.get(0)[0], "上线：客户端多了 0 号网口");
            assertEquals(NicMessages.PLAYER_PORT, c.control.get(0)[1]);
            assertEquals(NicMessages.KIND_PLAYER, c.control.get(0)[2]);
            assertEquals(Ipv6Address.UNSPECIFIED, Ipv6Address.read(c.control.get(0), 3), "地址不在消息里，用 DHCPv6 要");
            assertEquals(a, players.address(c.id), "服务端记的租约和客户端拿到的一样");
            assertEquals(resolverAddr, c.port().resolverAddress(), "DNS 由 DHCPv6 下发");
            assertEquals(net.ntpAddr, c.port().ntpAddress(), "NTP 由 DHCPv6 下发");
        }
        assertEquals(20, players.online());
        TestNetwork.Player c = clients.get(0);
        Ipv6Address old = c.address();
        players.detach(c.id);
        net.loop.drain();
        assertNull(players.address(c.id), "下线收回");
        assertEquals(NicMessages.PORT_DOWN, c.control.get(1)[0], "下线：去掉 0 号网口");
        assertEquals(NicMessages.PLAYER_PORT, c.control.get(1)[1]);
        assertEquals(19, players.online());
        // 收回以后发给旧地址的：地址不可达（玩家路由器回）
        PingResult r = ping(web, old);
        assertEquals(PingResult.Status.UNREACHABLE, r.status);
        assertEquals(Icmpv6Message.UNREACH_ADDRESS, r.code);
        assertEquals(net.address(RouterNetwork.PLAYERS), r.from);
        // 再上线：重新随机
        TestNetwork.Player again = online();
        assertNotEquals(old, again.address());
    }

    @Test
    void collisionsAndReservedPartsAreSkipped() {
        // 地址池的随机数依次给 0、0xFFFFFFFF、5、5、9：第一个玩家拿到 5，第二个跳过重复的 5 拿到 9
        int[] seq = {0, -1, 5, 5, 9};
        TestNetwork n = new TestNetwork();
        n.enablePlayers(new Random() {
            int i;

            @Override
            public int nextInt() {
                return seq[Math.min(i++, seq.length - 1)];
            }
        }, 20, 400);
        n.settle();
        // 两个人同时上线、同时走 DHCPv6
        TestNetwork.Player a = n.new Player("A", false);
        TestNetwork.Player b = n.new Player("B", false);
        n.settle();
        assertNotNull(a.address());
        assertNotNull(b.address());
        assertEquals(new HashSet<>(Arrays.asList(5L, 9L)),
                new HashSet<>(Arrays.asList(NetworkLayout.hostPartOf(a.address()), NetworkLayout.hostPartOf(b.address()))), "不撞地址");
    }

    // ---- 往外 ----

    @Test
    void dnsAndNtpAreAllowed() throws Exception {
        TestNetwork.Player c = online();
        List<String> answers = new ArrayList<>();
        UdpSocket s = c.stack().openUdp(null, 0, (socket, from, fromPort, to, data) -> answers.add(new String(data, StandardCharsets.UTF_8))).get();
        net.loop.drain();
        s.send(resolverAddr, 53, "query".getBytes(StandardCharsets.UTF_8));
        net.loop.advance(100);
        assertEquals(Collections.singletonList("query"), answers);
        // NTP 服务器的 123（0B 起 DHCPv6 下发 NTP，防火墙也放行）
        s.send(net.ntpAddr, NtpPacket.PORT, "time".getBytes(StandardCharsets.UTF_8));
        net.loop.advance(100);
        assertEquals(Arrays.asList("query", "time"), answers);
        // 递归解析器的别的端口、别的主机的 53：拒绝
        long before = net.rejects.total();
        s.send(resolverAddr, 54, new byte[1]);
        s.send(webAddr, 53, new byte[1]);
        net.loop.advance(100);
        assertEquals(2, answers.size());
        assertEquals(before + 2, net.rejects.total());
        // TCP 53 到递归解析器（截断以后改用 TCP 重问）：不用放行也行
        assertEquals("dns", echo(c.stack(), resolverAddr, 53, "dns"));
        assertEquals(NetworkException.Reason.UNREACHABLE, failure(() -> echo(c.stack(), webAddr, 53, "x")), "别的主机的 TCP 53 不行");
    }

    @Test
    void tcpOnlyToAllowedHostsOnWebPorts() throws Exception {
        TestNetwork.Player c = online();
        // 没放行：连不上（目标不可达：管理上禁止）
        assertEquals(NetworkException.Reason.UNREACHABLE, failure(() -> echo(c.stack(), webAddr, 80, "x")));
        assertTrue(players.allow(c.id, PURPOSE, Collections.singleton(webAddr), 60_000));
        net.loop.drain();
        assertEquals("hello", echo(c.stack(), webAddr, 80, "hello"));
        assertEquals("https", echo(c.stack(), webAddr, 443, "https"));
        // 放行的主机的别的端口、没放行的主机：不行
        assertEquals(NetworkException.Reason.UNREACHABLE, failure(() -> echo(c.stack(), webAddr, 8080, "x")));
        assertEquals(NetworkException.Reason.UNREACHABLE, failure(() -> echo(c.stack(), otherAddr, 80, "x")));
        // ping 也不行
        PingResult r = ping(c.stack(), webAddr);
        assertEquals(PingResult.Status.UNREACHABLE, r.status);
        assertEquals(Icmpv6Message.UNREACH_PROHIBITED, r.code);
        assertEquals(net.address(RouterNetwork.PLAYERS), r.from, "玩家路由器回的");
    }

    @Test
    void cannotActAsServer() throws Exception {
        TestNetwork.Player c = online();
        c.stack().listen(null, 80, conn -> conn.setListener((cc, d) -> cc.write(d)));
        players.allow(c.id, PURPOSE, Collections.singleton(webAddr), 60_000);
        net.loop.drain();
        // 放行的主机主动连玩家：拒绝
        CompletableFuture<TcpConnection> f = web.connect(null, c.address(), 80, (cc, d) -> {
        });
        net.loop.advance(500);
        assertTrue(f.isDone());
        assertEquals(NetworkException.Reason.UNREACHABLE, failure(f::get));
        // 主动 ping 玩家：拒绝
        assertEquals(Icmpv6Message.UNREACH_PROHIBITED, ping(web, c.address()).code);
        // 玩家这边发 SYN+ACK（冒充服务器）：拒绝
        long before = net.rejects.total();
        byte[] synAck = new TcpSegment(80, 40000, 1, 1, TcpSegment.SYN | TcpSegment.ACK, 65535, 0, new byte[0]).toPacket(c.address(), webAddr).encode();
        players.fromPlayer(c.id, "Tester", synAck);
        net.loop.drain();
        assertEquals(before + 1, net.rejects.total());
    }

    @Test
    void spoofedOversizedAndFloodingAreDropped() throws Exception {
        TestNetwork.Player c = online();
        TestNetwork.Player d = online();
        players.allow(c.id, PURPOSE, Collections.singleton(webAddr), 60_000);
        net.loop.advance(2000);
        long accepted = players.outAccepted;
        long before = net.rejects.total();
        // 用别人的地址、机房地址当源地址
        players.fromPlayer(c.id, "Tester", new UdpDatagram(1000, 53, new byte[1]).toPacket(d.address(), resolverAddr).encode());
        players.fromPlayer(c.id, "Tester", new UdpDatagram(1000, 53, new byte[1]).toPacket(webAddr, resolverAddr).encode());
        // 超大、不是 IPv6
        byte[] big = new byte[1281];
        big[0] = 0x60;
        players.fromPlayer(c.id, "Tester", big);
        players.fromPlayer(c.id, "Tester", new byte[40]);
        // 不在线的玩家
        players.fromPlayer(UUID.randomUUID(), "Ghost", new UdpDatagram(1000, 53, new byte[1]).toPacket(c.address(), resolverAddr).encode());
        // 链路本地的源地址不能发到链路以外
        players.fromPlayer(c.id, "Tester", new UdpDatagram(1000, 53, new byte[1]).toPacket(Ipv6Address.linkLocal(7), resolverAddr).encode());
        net.loop.drain();
        assertEquals(accepted, players.outAccepted);
        assertEquals(before + 6, net.rejects.total());
        // 超频：每秒 20 个
        net.loop.advance(2000);
        accepted = players.outAccepted;
        for (int i = 0; i < 100; i++) {
            players.fromPlayer(c.id, "Tester", new UdpDatagram(1000 + i, 53, new byte[1]).toPacket(c.address(), resolverAddr).encode());
        }
        assertEquals(accepted + 20, players.outAccepted);
    }

    @Test
    void revokeAndExpiryCutConnections() throws Exception {
        TestNetwork.Player c = online();
        players.allow(c.id, PURPOSE, Collections.singleton(webAddr), 60_000);
        net.loop.drain();
        ByteArrayOutputStream got = new ByteArrayOutputStream();
        CompletableFuture<TcpConnection> f = c.stack().connect(null, webAddr, 80, (cc, data) -> got.write(data, 0, data.length));
        net.loop.advance(200);
        TcpConnection conn = RouterTest.done(f);
        conn.write("a".getBytes(StandardCharsets.UTF_8));
        net.loop.advance(200);
        assertEquals("a", got.toString("UTF-8"));
        assertEquals(1, players.flowCount(c.id));
        players.revoke(c.id, PURPOSE);
        net.loop.drain();
        conn.write("b".getBytes(StandardCharsets.UTF_8));
        net.loop.advance(200);
        assertEquals("a", got.toString("UTF-8"), "撤销后已有连接上的数据也不过");
        assertEquals(0, players.flowCount(c.id));
        // 有效期 1 秒：过期后连不上
        players.allow(c.id, PURPOSE, Collections.singleton(webAddr), 1000);
        net.loop.drain();
        assertEquals("x", echo(c.stack(), webAddr, 80, "x"));
        net.loop.advance(1500);
        assertTrue(players.allowedHosts(c.id).isEmpty());
        assertEquals(NetworkException.Reason.UNREACHABLE, failure(() -> echo(c.stack(), webAddr, 80, "y")));
    }

    @Test
    void grantsAreCheckedAndUnionedByPurpose() {
        TestNetwork.Player c = online();
        TestNetwork.Player d = online();
        // 别的玩家的地址、/48 以外的地址不收
        assertTrue(players.allow(c.id, PURPOSE, Arrays.asList(d.address(), Ipv6Address.parse("2001:db8::1"), webAddr), 60_000));
        assertTrue(players.allow(c.id, "test:other", Collections.singleton(otherAddr), 60_000));
        net.loop.drain();
        assertEquals(new HashSet<>(Arrays.asList(webAddr, otherAddr)), players.allowedHosts(c.id));
        // 同一用途再调用是整组替换
        players.allow(c.id, PURPOSE, Collections.<Ipv6Address>emptySet(), 60_000);
        net.loop.drain();
        assertEquals(Collections.singleton(otherAddr), players.allowedHosts(c.id));
        // 太多、有效期不对、不在线
        List<Ipv6Address> many = new ArrayList<>();
        for (int i = 0; i < PlayerNics.MAX_HOSTS_PER_PURPOSE + 1; i++) {
            many.add(net.layout.hostAddress(0, 100 + i));
        }
        assertFalse(players.allow(c.id, PURPOSE, many, 60_000));
        assertFalse(players.allow(c.id, PURPOSE, Collections.singleton(webAddr), 0));
        assertFalse(players.allow(UUID.randomUUID(), PURPOSE, Collections.singleton(webAddr), 60_000));
        // 下线后放行的都没了
        players.detach(c.id);
        net.loop.drain();
        assertTrue(players.allowedHosts(c.id).isEmpty());
    }

    // ---- 能用 /ynet 的人（服主 11-27） ----

    @Test
    void unrestrictedPlayerSkipsTheFirewallButNotTheSourceCheck() throws Exception {
        TestNetwork.Player admin = net.online("Admin", true);
        TestNetwork.Player plain = online();
        // 不限目标、端口、协议
        assertEquals("x", echo(admin.stack(), otherAddr, 8080, "x"));
        assertEquals(PingResult.Status.REPLY, ping(admin.stack(), webAddr).status);
        // 别人也能主动连他、ping 他
        admin.stack().listen(null, 7000, conn -> conn.setListener((cc, d) -> cc.write(d)));
        assertEquals("in", echo(web, admin.address(), 7000, "in"));
        assertEquals(PingResult.Status.REPLY, ping(web, admin.address()).status);
        // 普通玩家照旧受限
        assertEquals(Icmpv6Message.UNREACH_PROHIBITED, ping(plain.stack(), webAddr).code);
        assertEquals(Icmpv6Message.UNREACH_PROHIBITED, ping(web, plain.address()).code);
        // 不限速：一下发 100 个都收下（普通玩家每秒 20 个）
        long accepted = players.outAccepted;
        for (int i = 0; i < 100; i++) {
            players.fromPlayer(admin.id, admin.name, new UdpDatagram(2000 + i, 9, new byte[1]).toPacket(admin.address(), webAddr).encode());
        }
        assertEquals(accepted + 100, players.outAccepted);
        // 源地址照旧必须是他自己的
        long before = net.rejects.total();
        players.fromPlayer(admin.id, admin.name, new UdpDatagram(1, 9, new byte[1]).toPacket(plain.address(), webAddr).encode());
        net.loop.drain();
        assertEquals(before + 1, net.rejects.total());
        assertNotNull(admin.address());
    }
}
