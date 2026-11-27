package moe.qingu.youran.network;

import moe.qingu.youran.api.network.ClientHost;
import moe.qingu.youran.api.network.Ipv6Address;
import moe.qingu.youran.api.network.packet.Ipv6Packet;
import moe.qingu.youran.api.network.stack.HttpClient;
import moe.qingu.youran.api.network.stack.ManualEventLoop;
import moe.qingu.youran.api.network.stack.NetStack;
import moe.qingu.youran.api.network.stack.NetworkException;
import moe.qingu.youran.api.network.stack.PingResult;
import moe.qingu.youran.api.network.stack.TcpConnection;
import moe.qingu.youran.net.NicMessages;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 客户端一个协议栈、多个网口（服主 11-27）：两个网口各自收发；绑网口的套接字只走那个网口，不绑的走玩家网口；
 * 网口去掉时绑着它的连接报错；包消息带网口号，已经去掉的网口号的包丢掉。服务器这一头换成一台假的主机（同一个手动事件循环）。
 * <p>
 * 0B 起玩家网口的地址不在 PORT_UP 里，要经玩家链路上的 RA 与 DHCPv6 拿：服务器这头多了一台假的玩家路由器（{@link FakePlayerRouter}，
 * 发 RA、回 DHCPv6、回 NS，链路上的包不转发）。原来 {@code portsCarryTheirOwnAddressesAndConfig} 断言“玩家网口不许连 NTP”，
 * 现在 NTP 也由 DHCPv6 下发，改成断言拿到的是 DHCPv6 给的那台。
 */
class ClientHostStateTest {
    final ManualEventLoop loop = new ManualEventLoop();
    final Ipv6Address playerAddr = Ipv6Address.parse("fd00:0:0:1:0:0:1234:5678");
    final Ipv6Address pcAddr = Ipv6Address.parse("fd00:0:0:0:a:b:c:0");
    final Ipv6Address serverAddr = Ipv6Address.parse("fd00:0:0:1::80");
    final Ipv6Address resolver = Ipv6Address.parse("fd00:0:0:1::53");
    /** 客户端发给服务器的消息 */
    final List<byte[]> toServer = new ArrayList<>();
    final ClientHostState state = new ClientHostState(loop, new Random(7), toServer::add);
    /** 服务器那头：一台主机，收到的包按目的地址回到客户端对应的网口 */
    final NetStack server;
    final List<Integer> serverSawPorts = new ArrayList<>();

    final Ipv6Address ntpAddr = Ipv6Address.parse("fd00:0:0:1::123");
    /** 玩家链路的服务器那头 */
    final FakePlayerRouter router;

    ClientHostStateTest() {
        router = new FakePlayerRouter(loop, new Random(11), playerAddr, resolver, ntpAddr, m -> state.onMessage(m));
        server = new NetStack(loop, p -> loop.execute(() -> {
            Ipv6Address dst = Ipv6Packet.destinationOf(p);
            if (dst.equals(playerAddr)) {
                router.toClient(p);
            } else {
                state.onMessage(NicMessages.packet(1, p));
            }
        }), new Random(9));
        server.addAddress(serverAddr);
        server.listen(null, 80, c -> c.setListener(new TcpConnection.Listener() {
            @Override
            public void onData(TcpConnection connection, byte[] data) {
                connection.write(("HTTP/1.0 200 OK\r\nContent-Length: 2\r\n\r\nok").getBytes(java.nio.charset.StandardCharsets.ISO_8859_1));
                connection.close();
            }
        }));
    }

    /** 把客户端发出的消息交给服务器那头（记下从哪个网口来的） */
    void pump() {
        for (int i = 0; i < 1000; i++) {
            loop.drain();
            if (toServer.isEmpty()) {
                loop.advance(1);
                if (toServer.isEmpty() && loop.pending() == 0) {
                    return;
                }
                continue;
            }
            List<byte[]> batch = new ArrayList<>(toServer);
            toServer.clear();
            for (byte[] m : batch) {
                assertEquals(NicMessages.PACKET, m[0]);
                byte[] raw = NicMessages.packetBody(m);
                if (m[1] == 0 && router.fromClient(raw)) {
                    // 链路上的报文（邻居发现、DHCPv6）：玩家路由器自己收下
                    continue;
                }
                serverSawPorts.add(m[1] & 0xFF);
                // 服务端的规则：0 号网口只收玩家地址发的，1 号只收电脑地址发的（同服务端网卡的源地址校验）
                Ipv6Address src = Ipv6Packet.sourceOf(raw);
                if ((m[1] == 0 && src.equals(playerAddr)) || (m[1] == 1 && src.equals(pcAddr))) {
                    server.receive(raw);
                }
            }
        }
    }

    void up() {
        state.onMessage(NicMessages.portUp(0, NicMessages.KIND_PLAYER, Ipv6Address.UNSPECIFIED, null, null));
        state.onMessage(NicMessages.portUp(1, NicMessages.KIND_RELAY, pcAddr, resolver, Ipv6Address.parse("fd00:0:0:1::123")));
        // 玩家网口：链路本地地址的重复地址检测、RS → RA → DHCPv6 四步 → 拿到的地址的重复地址检测
        for (int i = 0; i < 100 && (state.port(0).address() == null); i++) {
            pump();
            loop.advance(100);
        }
        assertEquals(playerAddr, state.port(0).address(), "玩家网口用 DHCPv6 拿到了地址");
        serverSawPorts.clear();
    }

    static <T> T get(CompletableFuture<T> f) throws Exception {
        assertTrue(f.isDone(), "还没完成");
        return f.get();
    }

    @Test
    void portsCarryTheirOwnAddressesAndConfig() {
        up();
        ClientHost.Port player = state.port(0);
        ClientHost.Port pc = state.port(1);
        assertEquals(ClientHost.Kind.PLAYER, player.kind());
        assertEquals(ClientHost.Kind.RELAY, pc.kind());
        assertEquals(playerAddr, player.address());
        assertEquals(pcAddr, pc.address());
        assertEquals(ntpAddr, player.ntpAddress(), "玩家网口的 NTP 由 DHCPv6 下发");
        assertEquals(resolver, player.resolverAddress(), "DNS 也是");
        assertNotNull(pc.ntpAddress());
        assertTrue(player.stack() == pc.stack(), "一个协议栈");
        assertEquals(2, state.stack().interfaces().size());
        assertTrue(state.stack().defaultInterface() == player.iface(), "默认网口是玩家自己的");
    }

    @Test
    void eachPortSendsAndReceivesOnItsOwn() throws Exception {
        up();
        CompletableFuture<PingResult> viaPc = state.stack().pingVia(state.port(1).iface(), serverAddr, 8, 1000);
        CompletableFuture<PingResult> viaPlayer = state.stack().pingVia(state.port(0).iface(), serverAddr, 8, 1000);
        pump();
        assertEquals(PingResult.Status.REPLY, get(viaPc).status);
        assertEquals(PingResult.Status.REPLY, get(viaPlayer).status);
        assertEquals(java.util.Arrays.asList(1, 0), serverSawPorts, "各走各的网口");
        // 发给电脑地址的包从 0 号网口进来：丢掉（目的地址要是收到它的那个网口的）
        long dropped = state.stack().droppedCount();
        byte[] stray = moe.qingu.youran.api.network.packet.Icmpv6Message.echo(128, 1, 1, new byte[0]).toPacket(serverAddr, pcAddr).encode();
        state.onMessage(NicMessages.packet(0, stray));
        loop.drain();
        assertEquals(dropped + 1, state.stack().droppedCount());
    }

    @Test
    void boundSocketsUseTheirPortUnboundUseThePlayerPort() throws Exception {
        up();
        CompletableFuture<HttpClient.Response> bound = HttpClient.send(state.stack(), state.port(1).iface(), state.port(1).dns(),
                HttpClient.Url.parse("http://[" + serverAddr + "]/"), "GET", null, null, 5000, null);
        pump();
        assertEquals(200, get(bound).status);
        assertEquals(Collections.singleton(1), new java.util.HashSet<>(serverSawPorts), "绑了电脑网口的连接只走 1 号网口");
        serverSawPorts.clear();
        CompletableFuture<HttpClient.Response> unbound = HttpClient.fetch(state.stack(), state.port(0).dns(), "http://[" + serverAddr + "]/", 5000);
        pump();
        assertEquals(200, get(unbound).status);
        assertEquals(Collections.singleton(0), new java.util.HashSet<>(serverSawPorts), "不绑的走玩家自己的网口");
    }

    @Test
    void removingAPortBreaksItsConnections() throws Exception {
        up();
        // 一条挂着不动的连接（服务器那头不回数据）
        server.listen(null, 81, c -> {
        });
        CompletableFuture<TcpConnection> conn = state.stack().connectVia(state.port(1).iface(), serverAddr, 81, null);
        pump();
        TcpConnection c = get(conn);
        NetworkException[] closed = new NetworkException[1];
        boolean[] notified = new boolean[1];
        c.setListener(new TcpConnection.Listener() {
            @Override
            public void onData(TcpConnection connection, byte[] data) {
            }

            @Override
            public void onClosed(TcpConnection connection, NetworkException error) {
                notified[0] = true;
                closed[0] = error;
            }
        });
        CompletableFuture<PingResult> ping = state.stack().pingVia(state.port(1).iface(), Ipv6Address.parse("fd00::dead"), 8, 60_000);
        // DNS 查询绑在电脑网口上，还没回来
        CompletableFuture<?> dns = state.port(1).dns().resolveAaaa("test.yr");
        loop.drain();
        ClientHost.Port pc = state.port(1);
        state.onMessage(NicMessages.portDown(1));
        loop.drain();
        assertTrue(pc.isRemoved());
        assertNull(state.port(1));
        assertTrue(notified[0], "绑着这个网口的连接报错");
        assertEquals(NetworkException.Reason.CLOSED, closed[0].reason);
        ExecutionException e = assertThrows(ExecutionException.class, () -> get(ping));
        assertTrue(e.getCause() instanceof NetworkException);
        assertThrows(ExecutionException.class, () -> get(dns));
        // 玩家网口照常
        CompletableFuture<PingResult> still = state.stack().ping(null, serverAddr, 8, 1000);
        pump();
        assertEquals(PingResult.Status.REPLY, get(still).status);
        // 绑去掉的网口：马上报错
        assertThrows(ExecutionException.class, () -> get(state.stack().connectVia(pc.iface(), serverAddr, 80, null)));
    }

    @Test
    void packetsForAGonePortAreDropped() {
        up();
        state.onMessage(NicMessages.portDown(1));
        loop.drain();
        long before = state.droppedCount();
        byte[] p = moe.qingu.youran.api.network.packet.Icmpv6Message.echo(128, 1, 1, new byte[0]).toPacket(serverAddr, pcAddr).encode();
        state.onMessage(NicMessages.packet(1, p));
        state.onMessage(NicMessages.packet(9, p));
        assertEquals(before + 2, state.droppedCount(), "已经去掉的和从来没有的网口号都丢掉");
        // 同一个网口号再加上：是新的网口，换了地址
        Ipv6Address other = Ipv6Address.parse("fd00:0:0:0:d:e:f:0");
        state.onMessage(NicMessages.portUp(1, NicMessages.KIND_RELAY, other, resolver, null));
        loop.drain();
        assertEquals(other, state.port(1).address());
        assertFalse(state.stack().hasAddress(pcAddr));
        assertTrue(state.stack().hasAddress(other));
    }

    @Test
    void closingRemovesAllPorts() {
        up();
        ClientHost.Port player = state.port(0);
        state.close();
        loop.drain();
        assertTrue(player.isRemoved());
        assertTrue(state.stack().isClosed());
        assertNull(state.port(0));
    }
}
