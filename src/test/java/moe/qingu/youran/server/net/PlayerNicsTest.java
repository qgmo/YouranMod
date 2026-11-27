package moe.qingu.youran.server.net;

import moe.qingu.youran.api.network.DefaultDimensionNetwork;
import moe.qingu.youran.api.network.Ipv6Address;
import moe.qingu.youran.api.network.NetworkLayout;
import moe.qingu.youran.api.network.packet.Icmpv6Message;
import moe.qingu.youran.api.network.packet.TcpSegment;
import moe.qingu.youran.api.network.packet.UdpDatagram;
import moe.qingu.youran.api.network.stack.ManualEventLoop;
import moe.qingu.youran.api.network.stack.NetStack;
import moe.qingu.youran.api.network.stack.NetworkException;
import moe.qingu.youran.api.network.stack.PingResult;
import moe.qingu.youran.api.network.stack.TcpConnection;
import moe.qingu.youran.api.network.stack.UdpSocket;
import moe.qingu.youran.net.NicMessages;
import net.minecraft.entity.player.EntityPlayerMP;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/** 玩家临时地址：分配与回收、放行规则（往外、往里）、伪造、限频、撤销与过期 */
class PlayerNicsTest {
    static final String PURPOSE = "cfm:spectate";
    final ManualEventLoop loop = new ManualEventLoop();
    final NetworkLayout layout = NetworkLayout.fromGlobalId(0x123456789aL);
    final Ipv6Address routerAddr = layout.hostAddress(0, 1);
    final Ipv6Address resolverAddr = layout.hostAddress(0, 0x53);
    final Ipv6Address webAddr = layout.hostAddress(0, 2);
    final Ipv6Address otherAddr = layout.hostAddress(0, 3);
    final RejectLog rejects = new RejectLog(10_000);
    final Router router;
    final PlayerNics players;
    final NetStack web;
    final NetStack other;
    final NetStack resolver;

    PlayerNicsTest() {
        Random random = new Random(42);
        Map<Integer, AddressBook.Dim> dims = new LinkedHashMap<>();
        dims.put(0, new AddressBook.Dim(0, 0, DefaultDimensionNetwork.INSTANCE));
        Map<String, Long> dcs = new LinkedHashMap<>();
        dcs.put("youranmod:main", 0L);
        dcs.put("youranmod:players", NetworkLayout.PLAYERS_DATACENTER);
        AddressBook book = new AddressBook(layout, dims, dcs, Collections.<String, Ipv6Address>emptyMap());
        Router[] holder = new Router[1];
        NicTable devices = new NicTable(book::coordinateAddress, loop, rejects, p -> holder[0].forward(p), 20, 20 * 1280, p -> {
        });
        router = new Router(loop, book, devices, routerAddr);
        holder[0] = router;
        // 往外每秒 20 个包，往里每秒 400 个
        players = new PlayerNics(loop, layout, router, rejects, resolverAddr, random, 20, 20 * 1280, 400, 400 * 1280);
        router.setPlayers(players);
        router.attachDatacenter(0);
        router.attachDatacenter(NetworkLayout.PLAYERS_DATACENTER);
        attach(routerAddr, 1);
        resolver = attach(resolverAddr, 2);
        web = attach(webAddr, 3);
        other = attach(otherAddr, 4);
        // 递归解析器：UDP 53 原样回
        resolver.openUdp(resolverAddr, 53, (socket, from, fromPort, to, data) -> socket.send(from, fromPort, data));
        // 网站：80、443、8080 都回显
        for (NetStack s : Arrays.asList(web, other)) {
            for (int port : new int[]{80, 443, 8080}) {
                s.listen(null, port, c -> c.setListener((conn, data) -> conn.write(data)));
            }
        }
        loop.drain();
    }

    NetStack attach(Ipv6Address a, int seed) {
        NetStack s = new NetStack(loop, p -> loop.execute(() -> router.forward(p)), new Random(seed));
        s.addAddress(a);
        router.attachHost(a, s);
        return s;
    }

    /** 一个上线的玩家：客户端上的协议栈接到他的临时地址 */
    final class Client {
        final UUID id = UUID.randomUUID();
        final List<byte[]> control = new ArrayList<>();
        final NetStack stack;
        final Ipv6Address address;

        Client() {
            NetStack[] holder = new NetStack[1];
            address = players.attach(id, "P" + id.toString().substring(0, 4), new PlayerNics.Link() {
                @Override
                public void deliver(byte[] packet) {
                    loop.execute(() -> holder[0].receive(packet));
                }

                @Override
                public void control(byte[] message) {
                    control.add(message);
                }
            });
            stack = new NetStack(loop, p -> loop.execute(() -> players.fromPlayer(id, "Tester", p)), new Random(7));
            stack.addAddress(address);
            holder[0] = stack;
            loop.drain();
        }

        /** 连上去、发一句、等回显；连不上时抛出原因 */
        String echo(Ipv6Address to, int port, String text) throws Exception {
            ByteArrayOutputStream got = new ByteArrayOutputStream();
            CompletableFuture<TcpConnection> f = stack.connect(null, to, port, (c, data) -> got.write(data, 0, data.length));
            loop.advance(500);
            assertTrue(f.isDone(), "连接还没结束");
            TcpConnection c = f.get();
            c.write(text.getBytes(StandardCharsets.UTF_8));
            loop.advance(500);
            return new String(got.toByteArray(), StandardCharsets.UTF_8);
        }
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

    // ---- 分配与回收 ----

    @Test
    void addressesAreRandomUniqueAndReleased() {
        Set<Ipv6Address> seen = new HashSet<>();
        List<Client> clients = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            Client c = new Client();
            clients.add(c);
            assertTrue(layout.isPlayerAddress(c.address), c.address.toString());
            assertTrue(c.address.inPrefix(layout.playersPrefix(), 96));
            assertTrue(seen.add(c.address), "同一时刻不重复");
            assertEquals(NicMessages.PORT_UP, c.control.get(0)[0], "上线：客户端多了 0 号网口");
            assertEquals(NicMessages.PLAYER_PORT, c.control.get(0)[1]);
            assertEquals(NicMessages.KIND_PLAYER, c.control.get(0)[2]);
            assertEquals(c.address, Ipv6Address.read(c.control.get(0), 3));
            assertEquals(resolverAddr, Ipv6Address.read(c.control.get(0), 19));
            assertEquals(c.address, players.address(c.id));
        }
        assertEquals(20, players.online());
        Client c = clients.get(0);
        players.detach(c.id);
        loop.drain();
        assertNull(players.address(c.id), "下线收回");
        assertEquals(NicMessages.PORT_DOWN, c.control.get(1)[0], "下线：去掉 0 号网口");
        assertEquals(NicMessages.PLAYER_PORT, c.control.get(1)[1]);
        assertEquals(19, players.online());
        // 收回以后发给旧地址的：地址不可达
        PingResult r = ping(web, c.address);
        assertEquals(PingResult.Status.UNREACHABLE, r.status);
        assertEquals(Icmpv6Message.UNREACH_ADDRESS, r.code);
        // 再上线：重新随机
        Client again = new Client();
        assertNotEquals(c.address, again.address);
    }

    @Test
    void collisionsAndReservedPartsAreSkipped() {
        // 随机数依次给 0、0xFFFFFFFF、5、5、9：第一个玩家拿到 5，第二个跳过重复的 5 拿到 9
        int[] seq = {0, -1, 5, 5, 9};
        PlayerNics p = new PlayerNics(loop, layout, router, rejects, resolverAddr, new Random() {
            int i;

            @Override
            public int nextInt() {
                return seq[Math.min(i++, seq.length - 1)];
            }
        }, 20, 20 * 1280, 400, 400 * 1280);
        PlayerNics.Link link = new PlayerNics.Link() {
            @Override
            public void deliver(byte[] packet) {
            }

            @Override
            public void control(byte[] message) {
            }
        };
        Ipv6Address a = p.attach(UUID.randomUUID(), "A", link);
        Ipv6Address b = p.attach(UUID.randomUUID(), "B", link);
        assertEquals(5, NetworkLayout.hostPartOf(a));
        assertEquals(9, NetworkLayout.hostPartOf(b));
    }

    // ---- 往外 ----

    @Test
    void dnsToResolverIsAllowed() throws Exception {
        Client c = new Client();
        List<String> answers = new ArrayList<>();
        UdpSocket s = c.stack.openUdp(null, 0, (socket, from, fromPort, to, data) -> answers.add(new String(data, StandardCharsets.UTF_8))).get();
        loop.drain();
        s.send(resolverAddr, 53, "query".getBytes(StandardCharsets.UTF_8));
        loop.advance(100);
        assertEquals(Collections.singletonList("query"), answers);
        // 递归解析器的别的端口、别的主机的 53：拒绝
        long before = rejects.total();
        s.send(resolverAddr, 54, new byte[1]);
        s.send(webAddr, 53, new byte[1]);
        loop.advance(100);
        assertEquals(1, answers.size());
        assertEquals(before + 2, rejects.total());
    }

    @Test
    void tcpOnlyToAllowedHostsOnWebPorts() throws Exception {
        Client c = new Client();
        // 没放行：连不上（目标不可达：管理上禁止）
        assertEquals(NetworkException.Reason.UNREACHABLE, failure(() -> c.echo(webAddr, 80, "x")));
        assertTrue(players.allow(c.id, PURPOSE, Collections.singleton(webAddr), 60_000));
        loop.drain();
        assertEquals("hello", c.echo(webAddr, 80, "hello"));
        assertEquals("https", c.echo(webAddr, 443, "https"));
        // 放行的主机的别的端口、没放行的主机：不行
        assertEquals(NetworkException.Reason.UNREACHABLE, failure(() -> c.echo(webAddr, 8080, "x")));
        assertEquals(NetworkException.Reason.UNREACHABLE, failure(() -> c.echo(otherAddr, 80, "x")));
        // ping 也不行
        PingResult r = ping(c.stack, webAddr);
        assertEquals(PingResult.Status.UNREACHABLE, r.status);
        assertEquals(Icmpv6Message.UNREACH_PROHIBITED, r.code);
        assertEquals(routerAddr, r.from);
    }

    @Test
    void cannotActAsServer() throws Exception {
        Client c = new Client();
        c.stack.listen(null, 80, conn -> conn.setListener((cc, d) -> cc.write(d)));
        players.allow(c.id, PURPOSE, Collections.singleton(webAddr), 60_000);
        loop.drain();
        // 放行的主机主动连玩家：拒绝
        CompletableFuture<TcpConnection> f = web.connect(null, c.address, 80, (cc, d) -> { });
        loop.advance(500);
        assertTrue(f.isDone());
        assertEquals(NetworkException.Reason.UNREACHABLE, failure(f::get));
        // 主动 ping 玩家：拒绝
        assertEquals(Icmpv6Message.UNREACH_PROHIBITED, ping(web, c.address).code);
        // 玩家这边发 SYN+ACK（冒充服务器）：拒绝
        long before = rejects.total();
        byte[] synAck = new TcpSegment(80, 40000, 1, 1, TcpSegment.SYN | TcpSegment.ACK, 65535, 0, new byte[0]).toPacket(c.address, webAddr).encode();
        players.fromPlayer(c.id, "Tester", synAck);
        loop.drain();
        assertEquals(before + 1, rejects.total());
    }

    @Test
    void spoofedOversizedAndFloodingAreDropped() throws Exception {
        Client c = new Client();
        Client d = new Client();
        players.allow(c.id, PURPOSE, Collections.singleton(webAddr), 60_000);
        loop.drain();
        long accepted = players.outAccepted;
        long before = rejects.total();
        // 用别人的临时地址、机房地址当源地址
        players.fromPlayer(c.id, "Tester", new UdpDatagram(1000, 53, new byte[1]).toPacket(d.address, resolverAddr).encode());
        players.fromPlayer(c.id, "Tester", new UdpDatagram(1000, 53, new byte[1]).toPacket(webAddr, resolverAddr).encode());
        // 超大、不是 IPv6
        byte[] big = new byte[1281];
        big[0] = 0x60;
        players.fromPlayer(c.id, "Tester", big);
        players.fromPlayer(c.id, "Tester", new byte[40]);
        // 不在线的玩家
        players.fromPlayer(UUID.randomUUID(), "Ghost", new UdpDatagram(1000, 53, new byte[1]).toPacket(c.address, resolverAddr).encode());
        loop.drain();
        assertEquals(accepted, players.outAccepted);
        assertEquals(before + 5, rejects.total());
        // 超频：每秒 20 个
        loop.advance(2000);
        accepted = players.outAccepted;
        for (int i = 0; i < 100; i++) {
            players.fromPlayer(c.id, "Tester", new UdpDatagram(1000 + i, 53, new byte[1]).toPacket(c.address, resolverAddr).encode());
        }
        assertEquals(accepted + 20, players.outAccepted);
    }

    @Test
    void revokeAndExpiryCutConnections() throws Exception {
        Client c = new Client();
        players.allow(c.id, PURPOSE, Collections.singleton(webAddr), 60_000);
        loop.drain();
        ByteArrayOutputStream got = new ByteArrayOutputStream();
        CompletableFuture<TcpConnection> f = c.stack.connect(null, webAddr, 80, (cc, data) -> got.write(data, 0, data.length));
        loop.advance(200);
        TcpConnection conn = f.get();
        conn.write("a".getBytes(StandardCharsets.UTF_8));
        loop.advance(200);
        assertEquals("a", got.toString("UTF-8"));
        assertEquals(1, players.flowCount(c.id));
        players.revoke(c.id, PURPOSE);
        loop.drain();
        conn.write("b".getBytes(StandardCharsets.UTF_8));
        loop.advance(200);
        assertEquals("a", got.toString("UTF-8"), "撤销后已有连接上的数据也不过");
        assertEquals(0, players.flowCount(c.id));
        // 有效期 1 秒：过期后连不上
        players.allow(c.id, PURPOSE, Collections.singleton(webAddr), 1000);
        loop.drain();
        assertEquals("x", c.echo(webAddr, 80, "x"));
        loop.advance(1500);
        assertTrue(players.allowedHosts(c.id).isEmpty());
        assertEquals(NetworkException.Reason.UNREACHABLE, failure(() -> c.echo(webAddr, 80, "y")));
    }

    @Test
    void grantsAreCheckedAndUnionedByPurpose() {
        Client c = new Client();
        Client d = new Client();
        // 别的玩家的临时地址、/48 以外的地址不收
        assertTrue(players.allow(c.id, PURPOSE, Arrays.asList(d.address, Ipv6Address.parse("2001:db8::1"), webAddr), 60_000));
        assertTrue(players.allow(c.id, "test:other", Collections.singleton(otherAddr), 60_000));
        loop.drain();
        assertEquals(new HashSet<>(Arrays.asList(webAddr, otherAddr)), players.allowedHosts(c.id));
        // 同一用途再调用是整组替换
        players.allow(c.id, PURPOSE, Collections.<Ipv6Address>emptySet(), 60_000);
        loop.drain();
        assertEquals(Collections.singleton(otherAddr), players.allowedHosts(c.id));
        // 太多、有效期不对、不在线
        List<Ipv6Address> many = new ArrayList<>();
        for (int i = 0; i < PlayerNics.MAX_HOSTS_PER_PURPOSE + 1; i++) {
            many.add(layout.hostAddress(0, 100 + i));
        }
        assertFalse(players.allow(c.id, PURPOSE, many, 60_000));
        assertFalse(players.allow(c.id, PURPOSE, Collections.singleton(webAddr), 0));
        assertFalse(players.allow(UUID.randomUUID(), PURPOSE, Collections.singleton(webAddr), 60_000));
        // 下线后放行的都没了
        players.detach(c.id);
        loop.drain();
        assertTrue(players.allowedHosts(c.id).isEmpty());
    }

    PingResult ping(NetStack from, Ipv6Address to) {
        CompletableFuture<PingResult> f = from.ping(null, to, 8, 1000);
        loop.advance(1500);
        try {
            return f.get();
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }
}
